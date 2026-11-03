package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import eu.kanade.tachiyomi.data.coil.ImageDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Draws a webtoon page as a stack of strips, so a very tall page never becomes one huge GPU texture.
 *
 * Ignores all touch events, because the webtoon viewer handles all the gestures.
 */
class WebtoonStripImageView(context: Context) : View(context) {

    private var strips: List<Bitmap> = emptyList()
    private var imageWidth = 0
    private var imageHeight = 0

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Applied to the strips as they draw. */
    var colorFilter: ColorFilter?
        get() = paint.colorFilter
        set(value) {
            paint.colorFilter = value
            invalidate()
        }
    private val clipRect = Rect()
    private val dstRect = Rect()

    /** Decodes the page again after its strips were freed off screen. */
    var onReload: (() -> Unit)? = null

    private var isReleasedOffScreen = false

    /** Whether to look for the page's comic panels once it's decoded, see [panels]. */
    var detectPanels = false

    /**
     * Comic panels of the page as fractions of its height, from top to bottom.
     * Empty while they're being looked for, or when [detectPanels] is off.
     */
    var panels: List<WebtoonPanel> = emptyList()
        private set

    private var detectJob: Job? = null

    /** Held while the strips are read off the main thread, so they aren't recycled meanwhile. */
    private val stripsLock = Any()

    /**
     * Shows [image]. [isReload] tells it's the same page decoded again after going off screen,
     * which keeps the panels found the first time.
     */
    fun setImage(image: ImageDecoder.StripsImage, isReload: Boolean = false) {
        releaseStrips()
        isReleasedOffScreen = false
        strips = image.strips
        imageWidth = image.width
        imageHeight = image.height
        if (!isReload || panels.isEmpty()) {
            panels = emptyList()
            startPanelDetection()
        }
        requestLayout()
        invalidate()
    }

    fun recycle() {
        releaseStrips()
        isReleasedOffScreen = false
        onReload = null
        imageWidth = 0
        imageHeight = 0
        panels = emptyList()
        requestLayout()
    }

    private fun startPanelDetection() {
        if (!detectPanels) return
        val strips = strips
        detectJob = detectionScope.launch {
            val found = synchronized(stripsLock) {
                WebtoonPanelDetector.detect(strips) { !isActive }
            }
            withContext(Dispatchers.Main) {
                // The strips may have been swapped for another page's meanwhile
                if (this@WebtoonStripImageView.strips === strips) {
                    panels = found
                }
            }
        }
    }

    // The viewer keeps a few pages off screen without recycling them, which adds up for huge pages.
    // The size stays, so the layout doesn't move while the page decodes again.
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (strips.sumOf { it.allocationByteCount.toLong() } > LARGE_PAGE_BYTES) {
            releaseStrips()
            isReleasedOffScreen = true
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isReleasedOffScreen) {
            isReleasedOffScreen = false
            onReload?.invoke()
        }
    }

    // A page's strips can be hundreds of MB, which would otherwise wait for a garbage collection to be freed
    private fun releaseStrips() {
        detectJob?.cancel()
        detectJob = null
        val released = strips
        strips = emptyList()
        synchronized(stripsLock) {
            released.forEach(Bitmap::recycle)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (imageWidth > 0) (width.toLong() * imageHeight / imageWidth).toInt() else 0
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (imageWidth <= 0) return
        canvas.getClipBounds(clipRect)

        val scale = width.toFloat() / imageWidth
        var srcTop = 0
        for (strip in strips) {
            val top = (srcTop * scale).roundToInt()
            srcTop += strip.height
            val bottom = (srcTop * scale).roundToInt()
            if (bottom <= clipRect.top) continue
            if (top >= clipRect.bottom) break
            dstRect.set(0, top, width, bottom)
            canvas.drawBitmap(strip, null, dstRect, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false
}

private val detectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private const val LARGE_PAGE_BYTES = 64L * 1024 * 1024
