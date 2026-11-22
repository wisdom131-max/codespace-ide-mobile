package com.codespace.ide.engine

object FeedbackSignal {
    enum class Signal { GOOD, BAD, NONE }
    class Tally {
        private val counts = HashMap<Signal, Int>()
        fun record(s: Signal) { if (s != Signal.NONE) counts[s] = (counts[s] ?: 0) + 1 }
        fun count(s: Signal): Int = counts[s] ?: 0
        fun score(): Double {
            val g = count(Signal.GOOD); val b = count(Signal.BAD)
            return if (g + b == 0) 0.0 else g.toDouble() / (g + b)
        }
    }
}
