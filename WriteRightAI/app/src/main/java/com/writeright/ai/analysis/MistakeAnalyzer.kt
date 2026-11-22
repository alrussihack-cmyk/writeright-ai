package com.writeright.ai.analysis

enum class MistakeKind { Case, Transposed, MissingLetter, ExtraLetter, WrongLetters, Other }

/**
 * What went wrong in a word.
 * [marks] are the indexes of the letters of the CORRECT word to highlight.
 */
data class MistakeInfo(
    val kind: MistakeKind,
    val title: String,
    val tip: String,
    val marks: Set<Int>,
)

object MistakeAnalyzer {

    fun analyze(written: String, correct: String): MistakeInfo {
        val w = written.lowercase()
        val c = correct.lowercase()

        // same letters, different capital/small
        if (w == c) {
            val marks = correct.indices.filter { i -> i < written.length && written[i] != correct[i] }.toSet()
            return MistakeInfo(
                MistakeKind.Case,
                "الحروف صح، بس الكابيتال والسمول غلط",
                "الأسماء وأول الجملة بحرف كبير، وغير كده حروف صغيرة.",
                marks,
            )
        }

        // two neighbour letters swapped
        if (w.length == c.length) {
            val i = w.indices.firstOrNull { w[it] != c[it] }
            if (i != null && i + 1 < w.length &&
                w[i] == c[i + 1] && w[i + 1] == c[i] &&
                w.substring(i + 2) == c.substring(i + 2)
            ) {
                val pair = c.substring(i, i + 2)
                val tip = when {
                    c.contains("cei") ->
                        "بعد حرف c ييجي e الأول وبعدين i، زي receive و ceiling."
                    pair == "ie" || pair == "ei" ->
                        "القاعدة: i قبل e، إلا بعد حرف c. زي believe و friend."
                    else ->
                        "الحرفين دول مكانهم اتبدل. اسمع الكلمة تاني واكتبهم بالترتيب."
                }
                return MistakeInfo(MistakeKind.Transposed, "حرفين مقلوبين", tip, setOf(i, i + 1))
            }
        }

        // common prefix and suffix, what is left in the middle is the mistake
        var p = 0
        while (p < w.length && p < c.length && w[p] == c[p]) p++
        var s = 0
        while (s < w.length - p && s < c.length - p && w[w.length - 1 - s] == c[c.length - 1 - s]) s++

        if (c.length == w.length + 1 && p + s >= w.length) {
            val letter = correct[p]
            val doubled = (p > 0 && c[p] == c[p - 1]) || (p + 1 < c.length && c[p] == c[p + 1])
            val tip = if (doubled) {
                "الحرف $letter بيتكتب مرتين ورا بعض في الكلمة دي."
            } else {
                "اسمع الكلمة تاني، فيه حرف $letter مش مكتوب."
            }
            return MistakeInfo(MistakeKind.MissingLetter, "ناقصك حرف: $letter", tip, setOf(p))
        }

        if (w.length == c.length + 1 && p + s >= c.length) {
            val extra = written.substring(p, written.length - s)
            return MistakeInfo(
                MistakeKind.ExtraLetter,
                "فيه حرف زيادة: $extra",
                "شيل حرف $extra والكلمة هتبقى صح.",
                emptySet(),
            )
        }

        val end = max0(c.length - s)
        val marks = (p until end).toSet()

        if (w.length == c.length) {
            val title = if (marks.size == 1) {
                val i = marks.first()
                "حرف غلط: بدل ${written[i]} اكتب ${correct[i]}"
            } else {
                "فيه حروف غلط"
            }
            return MistakeInfo(
                MistakeKind.WrongLetters,
                title,
                "اسمع الكلمة تاني وركّز في الحروف اللي متعلّم عليها.",
                marks,
            )
        }

        return MistakeInfo(
            MistakeKind.Other,
            "الكلمة محتاجة مراجعة",
            "قسّم الكلمة لمقاطع واكتبها حرف حرف.",
            marks,
        )
    }

    private fun max0(v: Int): Int = if (v < 0) 0 else v
}

/** Which letters of the written word are wrong, and where a letter is missing. */
data class WrittenMarks(val wrong: Set<Int>, val gaps: Set<Int>)

fun MistakeAnalyzer.writtenMarks(written: String, correct: String): WrittenMarks {
    val a = written
    val b = correct
    val la = a.length
    val lb = b.length
    fun same(i: Int, j: Int) = a[i].lowercaseChar() == b[j].lowercaseChar()
    val d = Array(la + 1) { IntArray(lb + 1) }
    for (i in 0..la) d[i][0] = i
    for (j in 0..lb) d[0][j] = j
    for (i in 1..la) for (j in 1..lb) {
        val cost = if (same(i - 1, j - 1)) 0 else 1
        var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
        if (i > 1 && j > 1 && same(i - 1, j - 2) && same(i - 2, j - 1)) v = minOf(v, d[i - 2][j - 2] + 1)
        d[i][j] = v
    }
    val wrong = HashSet<Int>()
    val gaps = HashSet<Int>()
    var i = la
    var j = lb
    while (i > 0 || j > 0) {
        if (i > 0 && j > 0) {
            val cost = if (same(i - 1, j - 1)) 0 else 1
            if (d[i][j] == d[i - 1][j - 1] + cost) {
                if (cost == 1 || a[i - 1] != b[j - 1]) wrong.add(i - 1)
                i--; j--
                continue
            }
            if (i > 1 && j > 1 && same(i - 1, j - 2) && same(i - 2, j - 1) && d[i][j] == d[i - 2][j - 2] + 1) {
                wrong.add(i - 1); wrong.add(i - 2)
                i -= 2; j -= 2
                continue
            }
        }
        if (i > 0 && d[i][j] == d[i - 1][j] + 1) {
            wrong.add(i - 1)
            i--
        } else {
            gaps.add(i)
            j--
        }
    }
    return WrittenMarks(wrong, gaps)
}

object TextDistance {
    /** Edit distance where swapping two neighbour letters costs one step. */
    fun osa(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, d[i - 2][j - 2] + 1)
                }
                d[i][j] = v
            }
        }
        return d[a.length][b.length]
    }
}
