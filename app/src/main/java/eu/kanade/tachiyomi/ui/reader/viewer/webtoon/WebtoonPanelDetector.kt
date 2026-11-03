package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max

/**
 * A comic panel found in a page, as fractions of the page's height.
 */
data class WebtoonPanel(
    val top: Float,
    val bottom: Float,
)

/**
 * Finds the panels of a page by looking for gutters: bands of rows that are a single flat color.
 *
 * It's a heuristic and works best on the usual webtoon layout of panels stacked vertically on a
 * plain background. A page without a gutter is a single panel.
 */
object WebtoonPanelDetector {

    /** How many columns of a row are compared, which is plenty to tell art from a flat gutter. */
    private const val SAMPLED_COLUMNS = 48

    /** At most this many rows are looked at for a page, whatever its height. */
    private const val MAX_SAMPLED_ROWS = 3000

    /** How far, per channel, a pixel may be from the row's first one for the row to stay flat. */
    private const val COLOR_TOLERANCE = 24

    /** Share of a row's pixels that may break the color, for JPEG noise and thin borders. */
    private const val OUTLIER_SHARE = 0.04f

    /** Shortest gutter, as a share of the page's width. */
    private const val MIN_GUTTER_SHARE = 0.02f

    /** Shortest panel, as a share of the page's width. Smaller ones are joined to a neighbour. */
    private const val MIN_PANEL_SHARE = 0.12f

    /**
     * Returns the panels of the page drawn by [strips], stacked from top to bottom.
     * [isCancelled] is checked between rows so a page that went away stops early.
     */
    fun detect(strips: List<Bitmap>, isCancelled: () -> Boolean = { false }): List<WebtoonPanel> {
        val width = strips.firstOrNull()?.width ?: return emptyList()
        val height = strips.sumOf { it.height }
        if (width <= 0 || height <= 0) return emptyList()

        val rowStep = max(1, height / MAX_SAMPLED_ROWS)
        val columnStep = max(1, width / SAMPLED_COLUMNS)
        val row = IntArray(width)

        // Whether each sampled row is flat
        val flat = BooleanArray((height + rowStep - 1) / rowStep)
        var stripTop = 0
        var stripIndex = 0
        for (i in flat.indices) {
            if (isCancelled()) return emptyList()
            val y = i * rowStep
            while (y >= stripTop + strips[stripIndex].height) {
                stripTop += strips[stripIndex].height
                stripIndex++
            }
            val strip = strips[stripIndex]
            if (strip.isRecycled) return emptyList()
            strip.getPixels(row, 0, width, 0, y - stripTop, width, 1)
            flat[i] = isFlat(row, columnStep)
        }

        // Runs of rows that aren't flat are panels, split where a gutter is tall enough
        val minGutterRows = max(1, (width * MIN_GUTTER_SHARE / rowStep).toInt())
        val runs = mutableListOf<IntRange>()
        var start = -1
        var gap = 0
        for (i in flat.indices) {
            if (!flat[i]) {
                if (start == -1) start = i
                gap = 0
            } else if (start != -1) {
                gap++
                if (gap >= minGutterRows) {
                    runs += start..<(i - gap + 1)
                    start = -1
                    gap = 0
                }
            }
        }
        if (start != -1) runs += start..<(flat.size - gap)

        // Too short to be a panel, like a caption or a sound effect between panels: join it to the closer neighbour
        val minPanelRows = max(1, (width * MIN_PANEL_SHARE / rowStep).toInt())
        val panels = runs.toMutableList()
        var i = 0
        while (i < panels.size && panels.size > 1) {
            val run = panels[i]
            if (run.last - run.first + 1 >= minPanelRows) {
                i++
                continue
            }
            val gapAbove = if (i > 0) run.first - panels[i - 1].last else Int.MAX_VALUE
            val gapBelow = if (i < panels.lastIndex) panels[i + 1].first - run.last else Int.MAX_VALUE
            if (gapAbove <= gapBelow) {
                panels[i - 1] = panels[i - 1].first..run.last
                panels.removeAt(i)
            } else {
                panels[i + 1] = run.first..panels[i + 1].last
                panels.removeAt(i)
            }
        }

        return panels.map { run ->
            WebtoonPanel(
                top = (run.first * rowStep).toFloat() / height,
                bottom = ((run.last + 1) * rowStep).coerceAtMost(height).toFloat() / height,
            )
        }
    }

    private fun isFlat(row: IntArray, columnStep: Int): Boolean {
        val reference = row[0]
        var samples = 0
        var outliers = 0
        var x = 0
        while (x < row.size) {
            samples++
            if (!isClose(row[x], reference)) outliers++
            x += columnStep
        }
        return outliers <= samples * OUTLIER_SHARE
    }

    private fun isClose(a: Int, b: Int): Boolean {
        return abs(Color.red(a) - Color.red(b)) <= COLOR_TOLERANCE &&
            abs(Color.green(a) - Color.green(b)) <= COLOR_TOLERANCE &&
            abs(Color.blue(a) - Color.blue(b)) <= COLOR_TOLERANCE
    }
}
