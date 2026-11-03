package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.ColorFilter
import android.graphics.Rect
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.GeneralSettings
import eu.kanade.tachiyomi.ui.reader.viewer.LocalPageColorFilter
import eu.kanade.tachiyomi.ui.reader.viewer.LongStripSettings
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.collectChanges
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Implementation of a [Viewer] to display pages with a [RecyclerView].
 */
class WebtoonViewer(val activity: ReaderActivity, val isContinuous: Boolean = true) : Viewer {

    val graph by lazy { activity.appGraph }
    val downloadManager by lazy { graph.downloadManager }
    val readerPreferences by lazy { graph.readerPreferences }

    private val scope = MainScope()

    /**
     * Recycler view used by this viewer.
     */
    val recycler = WebtoonRecyclerView(activity)

    /**
     * Frame containing the recycler view.
     */
    private val frame = WebtoonFrame(activity)

    /**
     * Distance to scroll when the user taps on one side of the recycler view.
     */
    private val scrollDistance = activity.resources.displayMetrics.heightPixels * 3 / 4

    /**
     * Layout manager of the recycler view.
     */
    private val layoutManager = WebtoonLayoutManager(activity, scrollDistance)

    private val settingsViewModel = activity.settingsViewModel

    /**
     * Settings every long strip viewer uses, like crop image borders.
     */
    val longStrip: LongStripSettings
        get() = settingsViewModel.longStrip.value

    /**
     * Settings only the webtoon viewers use, like side padding or tap zones.
     */
    val simpleLongStrip: SimpleLongStripSettings
        get() = settingsViewModel.simpleLongStrip.value

    /**
     * Settings shared by every viewer, like allow taps or page transitions.
     */
    val general: GeneralSettings
        get() = settingsViewModel.general.value

    private val webtoonItems = WebtoonItems()

    /**
     * Adapter of the recycler view.
     */
    private val adapter = WebtoonAdapter(this, webtoonItems)

    private val tracker = WebtoonPageTracker(
        activity,
        webtoonItems,
        readerPreferences.readerHideThreshold.get().threshold,
    )

    init {
        recycler.sidePaddingPercent = simpleLongStrip.page.sidePadding
        recycler.setItemViewCacheSize(RECYCLER_VIEW_CACHE_SIZE)
        recycler.isVisible = false // Don't let the recycler layout yet
        recycler.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        recycler.isFocusable = false
        recycler.itemAnimator = null
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    onScrolled()
                    tracker.onScrolledBy(
                        dy = dy.toFloat(),
                        firstVisible = layoutManager.findFirstVisibleItemPosition(),
                        lastEndVisible = layoutManager.findLastEndVisibleItemPosition(),
                    )
                }
            },
        )
        recycler.tapListener = { event ->
            val viewPosition = IntArray(2)
            recycler.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            recycler.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = Offset(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / recycler.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / recycler.originalHeight,
            )
            activity.onViewerTap(pos)
        }
        recycler.longTapListener = f@{ event ->
            if (activity.viewModel.state.value.menuVisible || general.longTapEnabled) {
                val child = recycler.findChildViewUnder(event.x, event.y)
                if (child != null) {
                    val position = recycler.getChildAdapterPosition(child)
                    val item = adapter.items.getOrNull(position)
                    if (item is ReaderPage) {
                        activity.onPageLongTap(item)
                        return@f true
                    }
                }
            }
            false
        }

        settingsViewModel.general.collectChanges(scope) { old, new ->
            if (old != null && old.theme != new.theme) {
                ActivityCompat.recreate(activity)
            }
        }
        settingsViewModel.longStrip.collectChanges(scope) { old, new ->
            if (old != null && old.cropBorders != new.cropBorders) {
                refreshAdapter()
            }
            if (old?.zoomOutDisabled != new.zoomOutDisabled) {
                frame.zoomOutDisabled = new.zoomOutDisabled
            }
        }
        settingsViewModel.simpleLongStrip.collectChanges(scope) { old, new ->
            if (old != null && old.page != new.page) {
                recycler.sidePaddingPercent = new.page.sidePadding
                refreshAdapter()
            }
            if (old?.doubleTapZoom != new.doubleTapZoom) {
                frame.doubleTapZoom = new.doubleTapZoom
            }
        }

        frame.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        frame.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        frame.addView(recycler)
    }

    /** The page images' colours, for pages bound later - see [LocalPageColorFilter]. */
    var pageColorFilter: ColorFilter? = null
        private set

    /** Every page view made, as the recycler's cached ones are neither children nor always bound again. */
    private val pageViews = Collections.newSetFromMap(WeakHashMap<ReaderPageImageView, Boolean>())

    fun onPageViewCreated(view: ReaderPageImageView) {
        pageViews += view
    }

    @Composable
    override fun Content(modifier: Modifier) {
        val filter = LocalPageColorFilter.current
        val pageFilter = remember(filter) { filter?.asAndroidColorFilter() }
        SideEffect {
            pageColorFilter = pageFilter
            pageViews.forEach { it.pageColorFilter = pageFilter }
        }
        AndroidView(factory = { frame }, modifier = modifier)
    }

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    override fun destroy() {
        super.destroy()
        scope.cancel()
    }

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    override fun setChapters(chapters: ViewerChapters) {
        val forceTransition = general.alwaysShowChapterTransition || tracker.currentPage is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        if (recycler.isGone) {
            logcat { "Recycler first layout" }
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            recycler.isVisible = true
        }
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            layoutManager.scrollToPositionWithOffset(position, 0)
            if (layoutManager.findLastEndVisibleItemPosition() == -1) {
                onScrolled(pos = position)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    private fun onScrolled(pos: Int? = null) {
        tracker.onScrolled(pos ?: layoutManager.findLastEndVisibleItemPosition())
    }

    /**
     * Scrolls up to the previous panel when panel navigation is on and panels were found,
     * otherwise by [scrollDistance].
     */
    override fun moveBackward() {
        scrollBy(panelScrollDistance(forward = false) ?: -scrollDistance)
    }

    /**
     * Scrolls down to the next panel when panel navigation is on and panels were found,
     * otherwise by [scrollDistance].
     */
    override fun moveForward() {
        scrollBy(panelScrollDistance(forward = true) ?: scrollDistance)
    }

    private fun scrollBy(dy: Int) {
        if (dy == 0) return
        if (general.usePageTransitions) {
            recycler.smoothScrollBy(0, dy)
        } else {
            recycler.scrollBy(0, dy)
        }
    }

    /**
     * The distance to scroll to bring the next panel, or the previous one when going back, to
     * the center of the screen. A panel taller than the screen is read through from its top
     * to its bottom instead.
     *
     * Null when panel navigation is off or there's no panel to go to among the laid out pages,
     * so the caller scrolls a fixed distance instead.
     */
    private fun panelScrollDistance(forward: Boolean): Int? {
        if (!simpleLongStrip.page.panelNavigation) return null
        // Zoomed in, the screen shows only part of the recycler, so keep to the plain scroll
        if (recycler.scaleY != 1f) return null

        val panels = visiblePanels()
        if (panels.isEmpty()) return null

        val viewport = recycler.height
        val center = viewport / 2
        // Small differences are left alone, or a panel already in place would be moved to again
        val tolerance = viewport / 20
        // Never jump further than a screen and a half, when the next panel isn't laid out yet
        val maxJump = viewport * 3 / 2

        if (forward) {
            for (panel in panels) {
                val dy = if (panel.height() > viewport) {
                    when {
                        panel.top > tolerance -> panel.top
                        panel.bottom > viewport + tolerance -> min(scrollDistance, panel.bottom - viewport)
                        else -> continue
                    }
                } else {
                    panel.centerY() - center
                }
                if (dy > tolerance) return dy.takeIf { it <= maxJump }
            }
        } else {
            for (panel in panels.asReversed()) {
                val dy = if (panel.height() > viewport) {
                    when {
                        panel.bottom < viewport - tolerance -> panel.bottom - viewport
                        panel.top < -tolerance -> max(-scrollDistance, panel.top)
                        else -> continue
                    }
                } else {
                    panel.centerY() - center
                }
                if (dy < -tolerance) return dy.takeIf { it >= -maxJump }
            }
        }
        return null
    }

    /**
     * The panels of the laid out pages, in the recycler's coordinates and from top to bottom.
     * A panel a source cut across two images is joined back into one. An item where no panel
     * was found, like a page still loading, counts as a single panel.
     */
    private fun visiblePanels(): List<Rect> {
        // Each panel with the adapter position of its page
        val panels = mutableListOf<Pair<Int, Rect>>()
        val bounds = Rect()
        for (i in 0..<recycler.childCount) {
            val child = recycler.getChildAt(i)
            val holder = recycler.getChildViewHolder(child)
            val position = holder.bindingAdapterPosition
            val pageView = (holder as? WebtoonPageHolder)?.frame?.pageContentView
            val pagePanels = (holder as? WebtoonPageHolder)?.frame?.panels.orEmpty()

            if (pageView == null || pagePanels.isEmpty() || pageView.height <= 0) {
                // Still loading, a chapter transition, or nothing found in it: the whole item is one panel
                if (child.height > 0) {
                    panels += position to Rect(child.left, child.top, child.right, child.bottom)
                }
                continue
            }

            bounds.set(0, 0, pageView.width, pageView.height)
            recycler.offsetDescendantRectToMyCoords(pageView, bounds)
            pagePanels.mapTo(panels) {
                position to Rect(
                    bounds.left,
                    bounds.top + (it.top * bounds.height()).roundToInt(),
                    bounds.right,
                    bounds.top + (it.bottom * bounds.height()).roundToInt(),
                )
            }
        }
        panels.sortBy { it.second.top }

        // Pages that aren't continuous have a margin between them
        val joinDistance = 4.dpToPx + if (isContinuous) 0 else 15.dpToPx
        val joined = mutableListOf<Rect>()
        var lastPosition = -1
        for ((position, panel) in panels) {
            val last = joined.lastOrNull()
            if (last != null && position != lastPosition && panel.top - last.bottom <= joinDistance) {
                last.bottom = max(last.bottom, panel.bottom)
            } else {
                joined += panel
            }
            lastPosition = position
        }
        return joined
    }

    /**
     * Notifies adapter of changes around the current page to trigger a relayout in the recycler.
     * Used when an image configuration is changed.
     */
    private fun refreshAdapter() {
        val position = layoutManager.findLastEndVisibleItemPosition()
        adapter.refresh()
        adapter.notifyItemRangeChanged(
            max(0, position - 3),
            min(position + 3, adapter.itemCount - 1),
        )
    }
}

// Double the cache size to reduce rebinds/recycles incurred by the extra layout space on scroll direction changes
private const val RECYCLER_VIEW_CACHE_SIZE = 4
