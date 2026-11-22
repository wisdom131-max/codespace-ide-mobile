package com.codespace.ide.engine

/**
 * PROJECT NINE 9.29 (catalog 87) - let the router change effort only where that is safe.
 *
 * ComplexityRouter could suggest a tier, and it was deliberately left unwired: sending a reasoning
 * parameter to a model that does not accept one turns a good turn into a 400.
 *
 * This is the gate that makes wiring it safe. It changes nothing unless the feature is switched on
 * AND the model is known to accept the parameter, so the default behaviour is exactly today's.
 */
object RouterGate {

    fun apply(
        current: String,
        suggested: String,
        modelSupportsReasoning: Boolean,
        enabled: Boolean,
    ): String {
        if (!enabled || !modelSupportsReasoning) return current
        if (current.isBlank() || suggested.isBlank()) return current
        return suggested
    }
}
