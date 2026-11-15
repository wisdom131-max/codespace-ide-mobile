package com.codespace.ide.chat.panel

/**
 * PHASE 4c (owner instruction 2026-11-15): which setup a connector needs, decided from what the
 * backend says about the service rather than from a guess about its name.
 *
 * The connector card used to hand the user to the Connectors Hub and make them find the service
 * again. The card now does the setup itself, and this is the rule that picks the shape: a `pat`
 * service collects a token in the card, an `oauth` service sends the user to the consent page the
 * hub would have opened. Pure so the rule is tested without a network, a Context or Compose.
 */
internal object ConnectorSetupPlan {

    enum class Flow {
        /** Paste a personal access token in the card. */
        PAT,
        /** Browser consent page, opened straight from the card. */
        OAUTH,
    }

    /**
     * @param authType the backend's `authType` for the service, or null when the status could not
     *   be read. Null restores as OAUTH because that is `ConnectorStatus`'s own default: an
     *   unknown service gets the consent page, which cannot invent a credential field.
     */
    fun flowFor(authType: String?): Flow =
        if (authType?.trim()?.lowercase() == "pat") Flow.PAT else Flow.OAUTH

    /** True when the card should stop offering setup because the service is already usable. */
    fun isSettled(connected: Boolean): Boolean = connected
}
