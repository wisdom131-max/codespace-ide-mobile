package com.codespace.ide.engine

/**
 * PROJECT EIGHT 8.17 (catalog 87) - route by how hard the request is, not by habit.
 *
 * Every request was sent to the same model at the same effort, so "rename this variable" and
 * "why does the build fail intermittently" cost the same. This classifies the REQUEST only; it
 * never picks a model or changes a setting by itself, because the choice of what to do with a tier
 * is a product decision the owner has not made yet.
 *
 * Deliberately conservative: an unknown request is STANDARD. Guessing HEAVY on everything would
 * make it useless, and guessing LIGHT on real work would be worse than not routing at all.
 */
object ComplexityRouter {

    enum class Tier { LIGHT, STANDARD, HEAVY }

    data class Route(val tier: Tier, val reasons: List<String>)

    private val HEAVY_WORDS = listOf(
        "architect", "refactor", "redesign", "why does", "intermittent", "race condition",
        "migrate", "across the codebase", "every file", "whole project", "investigate",
        "root cause", "performance", "optimise", "optimize", "deadlock", "memory leak",
    )

    private val LIGHT_WORDS = listOf(
        "rename", "typo", "what is", "what's", "explain", "show me", "where is",
        "how many", "list", "format this", "add a comment", "one line",
    )

    private val MULTI_STEP = Regex("(?i)\\b(then|after that|also|and then|finally|step \\d)\\b")

    fun route(request: String, mode: String = "AGENT"): Route {
        val text = request.lowercase().trim()
        if (text.isEmpty()) return Route(Tier.LIGHT, listOf("empty request"))
        val reasons = ArrayList<String>()
        var score = 0

        val heavy = HEAVY_WORDS.count { text.contains(it) }
        if (heavy > 0) { score += heavy * 2; reasons.add("$heavy heavy-task signal(s)") }
        val light = LIGHT_WORDS.count { text.contains(it) }
        if (light > 0) { score -= light; reasons.add("$light light-task signal(s)") }
        if (text.length > 400) { score += 2; reasons.add("long request") }
        if (text.length < 40) { score -= 1; reasons.add("short request") }
        val steps = MULTI_STEP.findAll(text).count()
        if (steps >= 2) { score += 2; reasons.add("$steps chained steps") }
        if (mode.uppercase() == "EDIT" || mode.uppercase() == "AGENT") {
            // Both can write, so the floor is STANDARD: a write is never "light".
            score += 1
            reasons.add("write-capable mode")
        }

        val scored = when {
            score >= 4 -> Tier.HEAVY
            score <= 0 -> Tier.LIGHT
            else -> Tier.STANDARD
        }
        // FLOOR: a write-capable mode is never LIGHT. Short requests in Edit/Agent still touch
        // files, and "rename it" in Agent is not a cheap question - it is a cheap EDIT, which is
        // the one place a wrong tier actually damages something. The score cannot lift itself out
        // of that, so the floor is applied after scoring rather than as another weight.
        val writeCapable = mode.uppercase() == "EDIT" || mode.uppercase() == "AGENT"
        val tier = if (writeCapable && scored == Tier.LIGHT) Tier.STANDARD else scored
        return Route(tier, reasons)
    }
}
