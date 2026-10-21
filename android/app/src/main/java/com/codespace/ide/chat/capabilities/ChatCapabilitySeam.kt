package com.codespace.ide.chat.capabilities

/**
 * G-E-b (advisor GO 2026-10-21, flag OFF by default): THE ONE SEAM between
 * the existing supportsImages call sites and the G-E-a capability layer.
 *
 * Every call site swaps its direct `provider.supportsImages` read for
 * [supportsImagesFor]. With the switch OFF that returns the provider
 * constant EXACTLY — byte-identical send path by construction (the flag-off
 * equivalence tests pin this). With the switch ON the resolution chain runs:
 * user override > live G-C refetch > bundled curated table, UNKNOWN falls
 * back to the provider constant (never a claim).
 *
 * A null providerSupportsImages means the selection's prefix is NOT a
 * registered provider (or selection has no provider prefix) — today's rule
 * for that case is "allowed" (true), preserved unchanged either way.
 */
object ChatCapabilitySeam {

    /** "openai:gpt-4o" -> "openai"; "" when no prefix. */
    fun providerIdOf(selection: String): String? =
        selection.substringBefore(':', "").ifBlank { null }

    /**
     * "openai:gpt-4o" -> "gpt-4o". NOTE (G-D-a will own parsing): the FIRST
     * colon separates, so a model id containing ":" would be split wrong —
     * the same ad-hoc parse hazard the G-D plan documents. Selections are
     * always written by the app as "provider:model", so the first-colon rule
     * matches every real stored string today.
     */
    fun modelIdOf(selection: String): String? =
        selection.substringAfter(':', "").ifBlank { null }

    /**
     * THE gate call. providerSupportsImages = TODAY's ChatProvider.supportsImages
     * value (null = unregistered prefix -> today's allowed).
     */
    fun supportsImagesFor(selection: String, providerSupportsImages: Boolean?): Boolean {
        if (providerSupportsImages == null) return true
        return ChatCapabilityGate.acceptsImages(providerIdOf(selection), modelIdOf(selection), providerSupportsImages)
    }

    /**
     * G-E-c attach-sheet offer condition: TRUE only when the switch is ON
     * AND the resolution chain claims NOTHING for this pair (UNKNOWN) — the
     * "this model accepts images" offer appears exactly then, never for a
     * known model, never while the switch is off.
     */
    fun imagesUnknownFor(selection: String, providerSupportsImages: Boolean?): Boolean {
        if (providerSupportsImages == null) return false
        if (!ChatCapabilityFlags.capabilityTableEnabled) return false
        return ChatCapabilityGate.resolve(providerIdOf(selection), modelIdOf(selection)).acceptsImages == null
    }

    /** G-E-c: write (or clear, accepts == null) the images override for the pair. */
    fun setImagesOverride(selection: String, accepts: Boolean?) {
        val pid = providerIdOf(selection) ?: return
        val mid = modelIdOf(selection) ?: return
        if (accepts == null) {
            ModelCapabilityOverrides.clear(pid, mid)
        } else {
            ModelCapabilityOverrides.set(pid, mid, ModelCapabilityOverrides.Override(acceptsImages = accepts))
        }
    }

    /**
     * G-E-c tri-state cycle (E17-c precedent: pure function, unit-tested):
     * follow-table (null) -> yes (true) -> no (false) -> follow-table (null).
     */
    fun nextImagesOverride(current: Boolean?): Boolean? = when (current) {
        null -> true
        true -> false
        else -> null
    }

    /**
     * G-E-b read-only picker line. Empty string when the switch is OFF (the
     * line is only rendered when non-empty — no dead UI while off) or when
     * the selection has no registered provider. With the switch ON:
     * "Images yes/no · ctx 128k · table 2026-10-20" — only fields the chain
     * actually resolved; UNKNOWN falls back to the provider constant for
     * images (the gate's own rule) and is simply omitted for ctx.
     */
    fun pickerLineFor(selection: String, providerSupportsImages: Boolean?): String {
        if (providerSupportsImages == null) return ""
        if (!ChatCapabilityFlags.capabilityTableEnabled) return ""
        val f = ChatCapabilityGate.resolve(providerIdOf(selection), modelIdOf(selection))
        val sb = StringBuilder()
        sb.append(if (f.acceptsImages ?: providerSupportsImages) "Images yes" else "Images no")
        f.maxContextTokens?.let { ctx ->
            sb.append(" \u00B7 ctx ").append(if (ctx >= 1000) "${ctx / 1000}k" else "$ctx")
        }
        ModelCapabilityTable.tableDate()?.let { sb.append(" \u00B7 table ").append(it) }
        return sb.toString()
    }
}
