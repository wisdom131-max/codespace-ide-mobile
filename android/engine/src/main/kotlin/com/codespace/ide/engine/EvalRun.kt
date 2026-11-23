package com.codespace.ide.engine

/**
 * PROJECT NINE 9.82 (catalog 93 + 94 + 95 WIRED) - one evaluation pass, joined up.
 *
 * The holdout split, the judge aggregation and the trajectory scoring were each built, tested and
 * called by nothing, so "is this build getting better" had no answer at all. This is the caller:
 * one report from the three, with a plain verdict a person can read.
 */
object EvalRun {

    data class Report(
        val trainCases: Int,
        val holdoutCases: Int,
        val judgeMedian: Int,
        val judgeAgreed: Boolean,
        val cleanRate: Int,
        val wastedCalls: Int,
    ) {
        /** Agreed judges, a mostly clean trajectory, and no wasted tool calls. */
        val healthy: Boolean get() = judgeAgreed && cleanRate >= 80 && wastedCalls == 0

        fun render(): String = buildString {
            append("evaluation: ")
            append(if (healthy) "healthy" else "needs attention")
            append("\ncases: ").append(trainCases).append(" train, ").append(holdoutCases).append(" holdout")
            append("\njudge median ").append(judgeMedian)
            append(if (judgeAgreed) " (judges agree)" else " (judges disagree)")
            append("\ntrajectories: ").append(cleanRate).append("% clean, ")
            append(wastedCalls).append(" wasted tool calls")
        }
    }

    fun run(
        cases: List<EvalDataset.EvalCase>,
        verdicts: List<JudgeRubric.Verdict>,
        turns: List<TrajectoryEvaluator.Turn>,
        criterion: String = "overall",
    ): Report {
        val split = EvalSplit.split(cases)
        val judge = JudgeAggregate.summarise(verdicts, criterion)
        val trajectory = TrajectorySeries.summarise(turns)
        return Report(
            trainCases = split.train.size,
            holdoutCases = split.holdout.size,
            judgeMedian = judge?.median ?: 0,
            judgeAgreed = judge?.agreed ?: false,
            cleanRate = trajectory.cleanRate,
            wastedCalls = trajectory.failedCalls,
        )
    }
}
