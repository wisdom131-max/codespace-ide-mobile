package com.codespace.ide.engine

/**
 * PROJECT NINE 9.25 (catalog 93) - count what people actually thought of an answer.
 *
 * FeedbackSignal could form a signal; nothing counted them, so a repeated complaint left no trace
 * and the same weakness kept shipping. A tally turns individual votes into a number and keeps the
 * reasons, because the reason is the part that can be fixed.
 */
object FeedbackTally {

    data class Tally(val up: Int, val down: Int, val reasons: Map<String, Int>) {
        val net: Int get() = up - down
    }

    class Counter {

        private var up = 0
        private var down = 0
        private val reasons = LinkedHashMap<String, Int>()

        fun positive(reason: String? = null) {
            up += 1
            record(reason)
        }

        fun negative(reason: String? = null) {
            down += 1
            record(reason)
        }

        fun tally(): Tally = Tally(up, down, reasons.toMap())

        private fun record(reason: String?) {
            val key = reason?.trim()?.lowercase() ?: return
            if (key.isEmpty()) return
            reasons[key] = (reasons[key] ?: 0) + 1
        }
    }
}
