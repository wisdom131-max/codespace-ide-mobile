package com.codespace.ide.ui.panes

/**
 * TERMINAL-START-PERF (2026-10-03, DIAGNOSTICS ONLY — no behavior change).
 *
 * The owner reports a "Starting terminal..." spinner on every terminal open and
 * wants to know, with MEASURED numbers, where the time from spinner to a usable
 * shell actually goes. This probe timestamps the whole startup chain so ONE
 * device run produces a [TERM-START-PERF] trail in the Output tab (terminal
 * channel):
 *
 *   BEGIN spinner-shown → gate-pass (D13 readiness wait) → spinner-hidden →
 *   store-restore → addUbuntuTab entered → placeholder created →
 *   setup-thread done → real session created (proot forking) →
 *   first-frame (prompt visible = the usable moment).
 *
 * The gaps between consecutive marks are the real answer. begin() anchors t=0;
 * mark() lazily self-begins so manual "+ tab" paths still produce a trail.
 * Volatile: marks fire from both the main thread and UbuntuSetupThread.
 */
internal object TerminalStartupProbe {
    @kotlin.jvm.Volatile private var t0Ms: Long = 0L
    @kotlin.jvm.Volatile private var begun: Boolean = false

    /** Anchor t=0 for a fresh startup chain and log which path fired. */
    fun begin(path: String) {
        t0Ms = android.os.SystemClock.elapsedRealtime()
        begun = true
        com.codespace.ide.diagnostics.AppOutputLog.log("[TERM-START-PERF] +0ms BEGIN $path", "terminal")
    }

    /** Log a phase mark; lazily self-begins (e.g. addTab without a spinner). */
    fun mark(label: String) {
        if (!begun) begin("(lazy — no-spinner path)")
        val ms = android.os.SystemClock.elapsedRealtime() - t0Ms
        com.codespace.ide.diagnostics.AppOutputLog.log("[TERM-START-PERF] +${ms}ms $label", "terminal")
    }
}
