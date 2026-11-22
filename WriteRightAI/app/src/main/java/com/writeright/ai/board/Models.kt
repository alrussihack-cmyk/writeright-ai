package com.writeright.ai.board

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import com.writeright.ai.analysis.MistakeInfo

/** Sizes shared by the board drawing and the word segmentation, in dp. */
object BoardMetrics {
    const val LINE_SPACING_DP = 72f
    const val LINE_OFFSET_DP = 20f
    const val WORD_GAP_DP = 22f
    const val ERASE_RADIUS_DP = 22f
}

data class InkPoint(val x: Float, val y: Float, val t: Long)

class StrokeData(val id: Int, val points: List<InkPoint>) {
    val bounds: Rect = run {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < l) l = p.x
            if (p.x > r) r = p.x
            if (p.y < t) t = p.y
            if (p.y > b) b = p.y
        }
        Rect(l, t, r, b)
    }

    val path: Path by lazy { buildInkPath(points) }
}

fun buildInkPath(points: List<InkPoint>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    val first = points.first()
    path.moveTo(first.x, first.y)
    if (points.size == 1) {
        // a dot (for example the dot of the letter i)
        path.lineTo(first.x + 0.1f, first.y)
        return path
    }
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val cur = points[i]
        path.quadraticTo(prev.x, prev.y, (prev.x + cur.x) / 2f, (prev.y + cur.y) / 2f)
    }
    val last = points.last()
    path.lineTo(last.x, last.y)
    return path
}

/** Strokes that were judged to be one written word. */
class WordGroup(val strokes: List<StrokeData>, val bounds: Rect) {
    val signature: String = strokes.joinToString(",") { it.id.toString() }
}

/** A word the handwriting recognizer read and the spell checker accepted. */
data class RecognizedWord(val id: String, val text: String, val bounds: Rect)

/** A written word that is spelled wrong, with its suggested fix. */
data class WordIssue(
    val id: String,
    val strokeIds: Set<Int>,
    val bounds: Rect,
    val written: String,
    val suggestion: String,
    val info: MistakeInfo,
)
