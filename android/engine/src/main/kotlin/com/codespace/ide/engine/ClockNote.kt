package com.codespace.ide.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * PROJECT EIGHT 8.3 (catalog B1) — the current date, time and zone, as one prompt line.
 *
 * The agent had no clock. Asked "what time is it" or "what did I do today" it answered from the
 * training cutoff, and a relative instruction ("in an hour", "tomorrow") was resolved against
 * nothing. That is not a model failure; it is a missing fact the host never supplied, and no amount
 * of prompt tuning fixes it.
 *
 * Deliberately `java.util` rather than `java.time`: this module also ships to Android builds whose
 * minSdk may sit below 26, where `java.time` is absent unless core-library desugaring is enabled.
 * Guessing wrong here would be a NoClassDefFoundError at runtime on older devices, and a clock is
 * not worth that risk.
 *
 * [now] takes its clock and zone as parameters so the output is reproducible in tests; production
 * callers use the defaults.
 */
object ClockNote {

    /** Weekday, day, month, year, 24h time - unambiguous, and no locale-dependent ordering. */
    private const val PATTERN = "EEEE, d MMMM yyyy, HH:mm"

    /**
     * One line naming the local time and the zone it is in.
     *
     * The zone and its offset are both included on purpose: "21:40" alone is meaningless to a model
     * reasoning about a user elsewhere, and a bare abbreviation ("WAT") is not resolvable to an
     * offset by every provider.
     */
    fun now(
        epochMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.US,
    ): String {
        val fmt = SimpleDateFormat(PATTERN, locale)
        fmt.timeZone = timeZone
        val stamp = fmt.format(Date(epochMillis))
        return "Current local time: $stamp (${zoneLabel(timeZone)}, ${offsetLabel(timeZone)})"
    }

    /** Short zone name, or the raw id when the platform has no friendly name. */
    private fun zoneLabel(timeZone: TimeZone): String {
        val short = timeZone.getDisplayName(false, TimeZone.SHORT, Locale.US)
        return if (short.isNullOrBlank() || short == timeZone.id) timeZone.id else short
    }

    /** "UTC+01:00" style offset, derived from the offset actually in force at that instant. */
    private fun offsetLabel(timeZone: TimeZone, epochMillis: Long = System.currentTimeMillis()): String {
        val millis = timeZone.getOffset(epochMillis)
        val totalMinutes = Math.abs(millis) / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val sign = if (millis < 0) "-" else "+"
        return String.format(Locale.US, "UTC%s%02d:%02d", sign, hours, minutes)
    }
}
