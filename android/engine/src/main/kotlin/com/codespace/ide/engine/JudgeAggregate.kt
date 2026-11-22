package com.codespace.ide.engine

/**
 * PROJECT NINE 9.27 (catalog 95) - one score from several judges, and whether they agreed.
 *
 * JudgeRubric could parse a verdict and say pass or fail. With more than one judge there was no
 * way to combine them, and a single judge disagreeing with itself across runs is exactly the
 * signal that a score is noise rather than measurement.
 *
 * Median rather than mean, because one outlier judge should not drag the number, and the spread is
 * reported so a confident-looking score built on disagreement can be seen for what it is.
 */
object JudgeAggregate {

    data class Summary(val median: Int, val spread: Int, val agreed: Boolean)

    fun summarise(verdicts: List<JudgeRubric.Verdict>, criterion: String): Summary? {
        val scores = verdicts.mapNotNull { it.scores[criterion] }.sorted()
        if (scores.isEmpty()) return null
        val median = scores[scores.size / 2]
        val spread = scores.last() - scores.first()
        return Summary(median, spread, spread <= 1)
    }
}
