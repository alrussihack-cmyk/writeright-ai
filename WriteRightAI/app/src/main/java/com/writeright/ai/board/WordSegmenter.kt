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

    fun group(
        strokes: List<StrokeData>,
        lineSpacing: Float,
        lineOffset: Float,
        wordGap: Float,
    ): List<WordGroup> {
        if (strokes.isEmpty()) return emptyList()

        val byLine = strokes.groupBy {
            floor((it.bounds.center.y - lineOffset) / lineSpacing).toInt()
        }

        val result = mutableListOf<WordGroup>()
        for (line in byLine.keys.sorted()) {
            val sorted = byLine.getValue(line).sortedBy { it.bounds.left }
            var current = mutableListOf<StrokeData>()
            var right = Float.NEGATIVE_INFINITY
            for (s in sorted) {
                if (current.isNotEmpty() && s.bounds.left - right > wordGap) {
                    result.add(build(current))
                    current = mutableListOf()
                    right = Float.NEGATIVE_INFINITY
                }
                current.add(s)
                right = max(right, s.bounds.right)
            }
            if (current.isNotEmpty()) result.add(build(current))
        }
        return result
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
