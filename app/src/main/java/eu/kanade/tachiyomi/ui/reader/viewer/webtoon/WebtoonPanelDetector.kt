package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A comic panel found in a page, as fractions of the page's height. It includes the speech
 * bubbles and captions that overlap it or sit in the gutter right above it.
 */
data class WebtoonPanel(
    val top: Float,
    val bottom: Float,
)

/**
 * Finds the panels of a page from the gutters between them.
 *
 * The page's background color is taken from the rows that are a single flat color. Anything else
 * is content, and content that touches makes one panel, so a bubble overlapping a panel's border
 * belongs to it and a slanted gutter still splits two panels. A page without a gutter is a single
 * panel.
 */
object WebtoonPanelDetector {

    /** Columns of the grid the page is looked at through. */
    private const val GRID_COLUMNS = 96

    /** At most this many rows are looked at for a page, whatever its height. */
    private const val MAX_SAMPLED_ROWS = 4000

    /** How far, per channel, a pixel may be from the background color to still be background. */
    private const val COLOR_TOLERANCE = 24

    /** Share of a row that may break its color for it to still count as flat, for JPEG noise. */
    private const val FLAT_ROW_SHARE = 0.96f

    /** Share of the flat rows a color needs to count as a background color. */
    private const val BACKGROUND_SHARE = 0.1f

    /** Pixels of a cell that must be content for the cell to be, so lone noisy pixels don't count. */
    private const val MIN_CONTENT_PIXELS = 2

    /** Specks of fewer cells than this, like dust in a gutter, are ignored. */
    private const val MIN_SPECK_CELLS = 4

    /** Pieces overlapping this share of the smaller one's height are one panel, like art floating in it. */
    private const val MERGE_OVERLAP_SHARE = 0.5f

    /**
     * Pieces shorter than this, as a share of the page's width, are captions or bubbles rather
     * than panels, and go with the panel below them.
     */
    private const val MIN_PANEL_SHARE = 0.4f

    /**
     * Returns the panels of the page drawn by [strips], from top to bottom. Panels split by a
     * slanted gutter overlap a little. [isCancelled] is checked between rows so a page that went
     * away stops early.
     */
    fun detect(strips: List<Bitmap>, isCancelled: () -> Boolean = { false }): List<WebtoonPanel> {
        val width = strips.firstOrNull()?.width ?: return emptyList()
        val height = strips.sumOf { it.height }
        if (width <= 0 || height <= 0) return emptyList()

        val rowStep = max(1, (height + MAX_SAMPLED_ROWS - 1) / MAX_SAMPLED_ROWS)
        val rowCount = (height + rowStep - 1) / rowStep
        val rows = RowReader(strips, width, rowStep)

        val backgrounds = backgroundColors(rows, rowCount, isCancelled) ?: return emptyList()
        val columns = min(GRID_COLUMNS, width)
        val content = contentGrid(rows, rowCount, columns, backgrounds, isCancelled) ?: return emptyList()

        val pieces = connectedPieces(content, rowCount, columns)
        val panels = mergePieces(pieces, rowCount, minRows = width * MIN_PANEL_SHARE / rowStep)

        return panels.map { (top, bottom) ->
            WebtoonPanel(
                top = (top * rowStep).toFloat() / height,
                bottom = min(height, bottom * rowStep).toFloat() / height,
            )
        }
    }

    /**
     * Reads every [rowStep]th row of the page into [row], one at a time, so the page is never
     * copied whole.
     */
    private class RowReader(private val strips: List<Bitmap>, val width: Int, private val rowStep: Int) {
        val row = IntArray(width)

        /** Reads the [index]th sampled row, or returns false when the strips were recycled. */
        fun read(index: Int): Boolean {
            var y = index * rowStep
            for (strip in strips) {
                if (y < strip.height) {
                    if (strip.isRecycled) return false
                    strip.getPixels(row, 0, width, 0, y, width, 1)
                    return true
                }
                y -= strip.height
            }
            return false
        }
    }

    /** The colors of the page's gutters, from its flat rows. White when it has none. */
    private fun backgroundColors(rows: RowReader, rowCount: Int, isCancelled: () -> Boolean): List<Int>? {
        val width = rows.width
        val row = rows.row
        // Count the flat rows' colors, roughly, keeping the first exact color of each
        val counts = mutableMapOf<Int, Int>()
        val firstColor = mutableMapOf<Int, Int>()
        var flatRows = 0
        for (i in 0..<rowCount) {
            if (isCancelled() || !rows.read(i)) return null
            val reference = row[0]
            var matching = 0
            for (x in 0..<width) {
                if (isClose(row[x], reference)) matching++
            }
            if (matching < width * FLAT_ROW_SHARE) continue
            flatRows++
            val key = (Color.red(reference) / 32 shl 6) or (Color.green(reference) / 32 shl 3) or
                (Color.blue(reference) / 32)
            counts[key] = (counts[key] ?: 0) + 1
            firstColor.putIfAbsent(key, reference)
        }
        if (flatRows == 0) return listOf(Color.WHITE)
        return counts
            .filterValues { it >= max(1f, flatRows * BACKGROUND_SHARE) }
            .keys
            .map { firstColor.getValue(it) }
    }

    /** Whether each cell of the grid holds something other than the background. */
    private fun contentGrid(
        rows: RowReader,
        rowCount: Int,
        columns: Int,
        backgrounds: List<Int>,
        isCancelled: () -> Boolean,
    ): BooleanArray? {
        val width = rows.width
        val row = rows.row
        val content = BooleanArray(rowCount * columns)
        for (i in 0..<rowCount) {
            if (isCancelled() || !rows.read(i)) return null
            for (column in 0..<columns) {
                val start = column * width / columns
                val end = (column + 1) * width / columns
                var pixels = 0
                for (x in start..<end) {
                    val color = row[x]
                    if (backgrounds.none { isClose(color, it) }) {
                        pixels++
                        if (pixels >= MIN_CONTENT_PIXELS) break
                    }
                }
                content[i * columns + column] = pixels >= MIN_CONTENT_PIXELS
            }
        }
        return content
    }

    /** The row ranges, end exclusive, of the groups of touching content cells. */
    private fun connectedPieces(content: BooleanArray, rowCount: Int, columns: Int): List<IntArray> {
        val parent = IntArray(content.size) { it }
        fun find(cell: Int): Int {
            var c = cell
            while (parent[c] != c) {
                parent[c] = parent[parent[c]]
                c = parent[c]
            }
            return c
        }
        fun union(a: Int, b: Int) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) parent[rootA] = rootB
        }

        for (y in 0..<rowCount) {
            for (x in 0..<columns) {
                val cell = y * columns + x
                if (!content[cell]) continue
                if (x > 0 && content[cell - 1]) union(cell, cell - 1)
                if (y == 0) continue
                for (dx in -1..1) {
                    val nx = x + dx
                    if (nx in 0..<columns && content[cell - columns + dx]) union(cell, cell - columns + dx)
                }
            }
        }

        // Root -> top, bottom (exclusive) and size
        val pieces = mutableMapOf<Int, IntArray>()
        for (y in 0..<rowCount) {
            for (x in 0..<columns) {
                val cell = y * columns + x
                if (!content[cell]) continue
                val piece = pieces.getOrPut(find(cell)) { intArrayOf(y, y + 1, 0) }
                piece[1] = max(piece[1], y + 1)
                piece[2]++
            }
        }
        return pieces.values.filter { it[2] >= MIN_SPECK_CELLS }
    }

    /**
     * Joins the pieces into panels: pieces mostly within another are part of it, and pieces
     * shorter than [minRows] go with the panel below. A piece cut by the page's top or bottom
     * edge stays apart, as it's likely part of a panel on the page next to it.
     */
    private fun mergePieces(pieces: List<IntArray>, rowCount: Int, minRows: Float): List<Pair<Int, Int>> {
        val panels = pieces.map { intArrayOf(it[0], it[1]) }.sortedBy { it[0] }.toMutableList()

        var merged = true
        while (merged) {
            merged = false
            panels.sortBy { it[0] }
            for (i in 0..<panels.lastIndex) {
                val a = panels[i]
                val b = panels[i + 1]
                val overlap = min(a[1], b[1]) - max(a[0], b[0])
                if (overlap > MERGE_OVERLAP_SHARE * min(a[1] - a[0], b[1] - b[0])) {
                    panels[i] = intArrayOf(min(a[0], b[0]), max(a[1], b[1]))
                    panels.removeAt(i + 1)
                    merged = true
                    break
                }
            }
        }

        var i = 0
        while (i < panels.size && panels.size > 1) {
            val panel = panels[i]
            val isCutByEdge = panel[0] == 0 || panel[1] >= rowCount
            if (panel[1] - panel[0] >= minRows || isCutByEdge) {
                i++
                continue
            }
            val target = if (i < panels.lastIndex) i + 1 else i - 1
            val other = panels[target]
            panels[target] = intArrayOf(min(panel[0], other[0]), max(panel[1], other[1]))
            panels.removeAt(i)
            // The joined panel may now be far enough up to be looked at again
            i = max(0, min(i, target) - 1)
        }

        return panels.sortedBy { it[0] }.map { it[0] to it[1] }
    }

    private fun isClose(a: Int, b: Int): Boolean {
        return abs(Color.red(a) - Color.red(b)) <= COLOR_TOLERANCE &&
            abs(Color.green(a) - Color.green(b)) <= COLOR_TOLERANCE &&
            abs(Color.blue(a) - Color.blue(b)) <= COLOR_TOLERANCE
    }
}
