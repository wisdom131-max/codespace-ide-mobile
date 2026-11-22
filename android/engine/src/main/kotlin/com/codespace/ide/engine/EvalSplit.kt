package com.codespace.ide.engine

/**
 * PROJECT NINE 9.26 (catalog 94) - keep some cases out of sight so the score means something.
 *
 * EvalDataset could render and parse cases. Scoring against every case you have tells you how well
 * the agent does on questions you have already tuned for, which is not the question.
 *
 * Every Nth case is held out, deterministically: the same dataset always splits the same way, so a
 * score can be compared across runs without the split itself moving.
 */
object EvalSplit {

    data class Split(val train: List<EvalDataset.EvalCase>, val holdout: List<EvalDataset.EvalCase>)

    fun split(cases: List<EvalDataset.EvalCase>, holdoutEvery: Int = 5): Split {
        if (holdoutEvery <= 1) return Split(emptyList(), cases)
        val train = ArrayList<EvalDataset.EvalCase>()
        val holdout = ArrayList<EvalDataset.EvalCase>()
        cases.forEachIndexed { i, c ->
            if ((i + 1) % holdoutEvery == 0) holdout.add(c) else train.add(c)
        }
        return Split(train, holdout)
    }
}
