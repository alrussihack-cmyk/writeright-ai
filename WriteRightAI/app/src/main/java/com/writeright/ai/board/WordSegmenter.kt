package com.writeright.ai.board

import androidx.compose.ui.geometry.Rect
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Splits the strokes on the board into words.
 *
 * 1. Strokes are put on a line according to the ruled lines of the board.
 * 2. On each line, strokes are sorted left to right and a new word starts
 *    when the gap to the previous stroke is bigger than [wordGap].
 */
object WordSegmenter {

    /**
     * Strokes belong to the same word when they are close horizontally and overlap vertically.
     * The allowed gap grows with the size of the letters, so big handwriting that crosses
     * several ruled lines still stays one word.
     */
    fun group(
        strokes: List<StrokeData>,
        lineSpacing: Float,
        lineOffset: Float,
        wordGap: Float,
    ): List<WordGroup> {
        if (strokes.isEmpty()) return emptyList()

        val n = strokes.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }

        val vTolerance = lineSpacing * 0.15f
        for (i in 0 until n) {
            val a = strokes[i].bounds
            for (j in i + 1 until n) {
                val b = strokes[j].bounds
                val hGap = max(a.left, b.left) - min(a.right, b.right)
                val vGap = max(a.top, b.top) - min(a.bottom, b.bottom)
                val letterHeight = max(a.height, b.height)
                val allowedGap = max(wordGap, letterHeight * 0.35f)
                if (hGap <= allowedGap && vGap <= vTolerance) {
                    parent[find(i)] = find(j)
                }
            }
        }

        val clusters = HashMap<Int, MutableList<StrokeData>>()
        for (i in 0 until n) clusters.getOrPut(find(i)) { mutableListOf() }.add(strokes[i])

        return clusters.values.map { build(it) }.sortedWith(
            compareBy<WordGroup>({ floor((it.bounds.center.y - lineOffset) / lineSpacing).toInt() })
                .thenBy { it.bounds.left }
        )
    }

    private fun build(list: List<StrokeData>): WordGroup {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (s in list) {
            l = min(l, s.bounds.left)
            t = min(t, s.bounds.top)
            r = max(r, s.bounds.right)
            b = max(b, s.bounds.bottom)
        }
        // keep drawing order, it matters for the recognizer
        return WordGroup(list.sortedBy { it.id }, Rect(l, t, r, b))
    }
}
