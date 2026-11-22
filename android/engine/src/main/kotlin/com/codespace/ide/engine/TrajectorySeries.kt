package com.codespace.ide.engine

/**
 * PROJECT NINE 9.28 (catalog 96) - score a run of turns, not one turn at a time.
 *
 * TrajectoryEvaluator judged a single turn. A turn can look clean while the session wasted a dozen
 * failed calls getting there, and that waste is the thing worth fixing.
 *
 * The rate is a percentage so it reads the same however many turns there are.
 */
object TrajectorySeries {

    data class Summary(val turns: Int, val clean: Int, val failedCalls: Int) {
        val cleanRate: Int get() = if (turns == 0) 0 else clean * 100 / turns
    }

    fun summarise(turns: List<TrajectoryEvaluator.Turn>): Summary {
        var clean = 0
        var failed = 0
        for (t in turns) {
            if (TrajectoryEvaluator.score(t).clean) clean += 1
            failed += t.failedCalls
        }
        return Summary(turns.size, clean, failed)
    }
}
