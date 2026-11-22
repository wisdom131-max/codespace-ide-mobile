package com.codespace.ide.engine

object TrajectoryEvaluator {
    data class Turn(val toolCalls: Int, val failedCalls: Int, val claimedDone: Boolean, val endedInError: Boolean)
    data class Score(val clean: Boolean, val reasons: List<String>)
    fun score(t: Turn): Score {
        val r = ArrayList<String>()
        if (t.endedInError) r.add("ended in an error")
        if (t.claimedDone && t.failedCalls > 0) r.add("claimed done with " + t.failedCalls + " failed calls")
        if (t.claimedDone && t.toolCalls == 0) r.add("claimed done without doing anything")
        return Score(r.isEmpty(), r)
    }
}
