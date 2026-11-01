package com.codespace.ide.editor

/**
 * TeX to MathML for the Markdown preview, offline and without bundled assets.
 *
 * Why MathML rather than KaTeX: KaTeX means shipping ~300 KB of JS plus a font pack in the APK,
 * and loading it inside a served document means either an extra origin or inlining the whole
 * library into every render. Chrome's own MathML Core (WebView 109+) renders `math`, `mfrac`,
 * `msqrt`, `msup`, `msub`, `munderover`, `mi`, `mn`, `mo`, `mtext` natively, so the common
 * subset of TeX people actually type in a README costs nothing and works with no network.
 *
 * Honest limit: this is a subset, not TeX. Macros, alignment environments, matrices, colour
 * commands and anything relying on KaTeX's font metrics are not here. Unknown commands render
 * as their own name in upright text so a document degrades visibly instead of silently.
 */
object MarkdownMath {

    private val greek = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ",
        "sigma" to "σ", "tau" to "τ", "phi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
        "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    )

    private val symbols = mapOf(
        "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "cdot" to "⋅",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
        "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "forall" to "∀", "exists" to "∃",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "supset" to "⊃", "cup" to "∪", "cap" to "∩",
        "rightarrow" to "→", "to" to "→", "leftarrow" to "←", "leftrightarrow" to "↔",
        "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔",
        "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮", "sqr" to "√",
        "ldots" to "…", "dots" to "…", "cdots" to "⋯", "degree" to "°",
        "quad" to "\u2003", "qquad" to "\u2003\u2003",
        "{" to "{", "}" to "}", "%" to "%", "$" to "$", "&" to "&", "#" to "#", "_" to "_",
    )

    /** Commands that take one braced group and become a two-part MathML node. */
    private val binary = setOf("frac", "dfrac", "tfrac", "binom")

    fun toMathML(tex: String, display: Boolean = false): String {
        val parser = Parser(tex)
        val body = parser.parseRow(stopAt = null)
        val block = if (display) " display=\"block\"" else ""
        return "<math xmlns=\"http://www.w3.org/1998/Math/MathML\"$block><mrow>$body</mrow></math>"
    }

    private class Parser(private val src: String) {
        private var i = 0

        fun parseRow(stopAt: Char?): String {
            // Nodes are collected so a following ^ or _ can attach to the atom that was just
            // emitted. Emitting <msup><mrow></mrow>…</msup> would render a superscript on an
            // invisible empty base, which is exactly the bug this list exists to avoid.
            val nodes = mutableListOf<String>()
            while (i < src.length) {
                val c = src[i]
                if (stopAt != null && c == stopAt) { i++; return nodes.joinToString("") }
                when {
                    c == '\\' -> nodes.add(command())
                    c == '{' -> { i++; nodes.add("<mrow>${parseRow('}')}</mrow>") }
                    c == '^' || c == '_' -> {
                        i++
                        val arg = argument()
                        val base = if (nodes.isEmpty()) "<mrow></mrow>" else nodes.removeAt(nodes.size - 1)
                        nodes.add(if (c == '^') "<msup>$base$arg</msup>" else "<msub>$base$arg</msub>")
                    }
                    c == ' ' || c == '\n' || c == '\t' -> i++
                    c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> nodes.add(number())
                    c.isLetter() -> nodes.add("<mi>${atoms()}</mi>")
                    else -> { i++; nodes.add("<mo>${escape(c.toString())}</mo>") }
                }
            }
            return nodes.joinToString("")
        }

        private fun atoms(): String {
            val b = StringBuilder()
            while (i < src.length && (src[i].isLetter() || src[i].isDigit())) { b.append(src[i]); i++ }
            return escape(b.toString())
        }

        private fun number(): String {
            val b = StringBuilder()
            while (i < src.length && (src[i].isDigit() || src[i] == '.')) { b.append(src[i]); i++ }
            return "<mn>$b</mn>"
        }

        /** One command. */
        private fun command(): String {
            i++ // consume backslash
            val name = StringBuilder()
            while (i < src.length && src[i].isLetter()) { name.append(src[i]); i++ }
            val cmd = name.toString()
            if (cmd.isEmpty()) {
                // Escaped single character: \{ \} \% \$ \& \# \_ \\
                if (i >= src.length) return ""
                val ch = src[i]; i++
                return if (ch == '\\') "<mspace linebreak=\"newline\"/>" else "<mo>${escape(ch.toString())}</mo>"
            }
            greek[cmd]?.let { return "<mi>$it</mi>" }
            symbols[cmd]?.let { return "<mo>$it</mo>" }
            when (cmd) {
                "frac", "dfrac", "tfrac", "binom" -> {
                    val num = argument()
                    val den = argument()
                    return "<mfrac>$num$den</mfrac>"
                }
                "sqrt" -> {
                    val index = if (i < src.length && src[i] == '[') {
                        i++; parseRow(']')
                    } else ""
                    val radicand = argument()
                    return if (index.isEmpty()) "<msqrt>$radicand</msqrt>"
                           else "<mroot>$radicand<mrow>$index</mrow></mroot>"
                }
                "text", "mathrm", "operatorname" -> return "<mtext>${escape(rawGroup())}</mtext>"
                "mathbf", "textbf" -> return "<mtext mathvariant=\"bold\">${escape(rawGroup())}</mtext>"
                "mathit", "textit" -> return "<mtext mathvariant=\"italic\">${escape(rawGroup())}</mtext>"
                "left", "right" -> {
                    if (i < src.length) {
                        val d = src[i]
                        if (d == '.') { i++; return "" }
                        i++
                        return "<mo>${escape(d.toString())}</mo>"
                    }
                    return ""
                }
            }
            return "<mtext>${escape(cmd)}</mtext>"
        }

        /** The next argument: a braced group or a single token. */
        private fun argument(): String {
            while (i < src.length && src[i] == ' ') i++
            if (i >= src.length) return "<mrow></mrow>"
            val c = src[i]
            if (c == '{') { i++; return "<mrow>${parseRow('}')}</mrow>" }
            if (c == '\\') return command()
            if (c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit())) return number()
            if (c.isLetter()) { i++; return "<mi>${escape(c.toString())}</mi>" }
            i++
            return "<mo>${escape(c.toString())}</mo>"
        }

        private fun rawGroup(): String {
            if (i >= src.length || src[i] != '{') return ""
            i++
            val b = StringBuilder()
            while (i < src.length && src[i] != '}') { b.append(src[i]); i++ }
            if (i < src.length) i++
            return b.toString()
        }

        private fun escape(s: String): String = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
