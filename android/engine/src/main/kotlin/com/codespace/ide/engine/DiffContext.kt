package com.codespace.ide.engine

/**
 * PROJECT NINE 9.6 (catalog 30) - show what changed instead of resending the file.
 *
 * After an edit the whole file was sent again, so a one-line change cost the whole file. A diff
 * of the changed region is a fraction of that and points the model at the edit.
 *
 * This is a prefix/suffix trim, not a full LCS diff. That is deliberate: it is exact for the
 * common case (one region changed), bounded in cost, and it never claims a change that is not
 * there - identical input returns empty text and changed=false.
 */
object DiffContext {

    data class Result(val text: String, val changed: Boolean)

    fun between(old: String, new: String, maxLines: Int = 40): Result {
        if (old == new) return Result("", false)
        val a = old.lines()
        val b = new.lines()
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size - 1
        var endB = b.size - 1
        while (endA >= start && endB >= start && a[endA] == b[endB]) {
            endA--
            endB--
        }
        val sb = StringBuilder()
        sb.append("@@ from line ").append(start + 1).append(" @@\n")
        var written = 0
        var truncated = false
        for (i in start..endA) {
            if (written >= maxLines) { truncated = true; break }
            sb.append("- ").append(a[i]).append('\n')
            written++
        }
        if (!truncated) {
            for (i in start..endB) {
                if (written >= maxLines) { truncated = true; break }
                sb.append("+ ").append(b[i]).append('\n')
                written++
            }
        }
        if (truncated) sb.append("... more lines changed than are shown\n")
        return Result(sb.toString(), true)
    }
}
