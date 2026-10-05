package com.codespace.ide.resolver

/**
 * V0-c (resolver architecture, 2026-10-05 owner-approved): the BINDING-REGISTRY HOOK.
 *
 * The resolver V1 phase (S3 slot) will build the Option-A workspace-binding mechanism:
 * a per-project registry mapping GUEST paths to ROOTFS-PRIVATE host paths (first
 * consumer: node_modules bind-mounted into /sdcard projects), with a centralized
 * proot -b injection consulted by every project-scoped session builder.
 *
 * V0 defines the CONSULTATION CONTRACT now, with a no-op default, so the core's
 * guest→host translation already flows through it — V1 plugs the real registry in
 * WITHOUT touching a single call site again.
 *
 * Contract (enforced by the future real implementation; the None default never fires):
 *   - [hostTargetFor] receives a TRIMMED, absolute guest path (e.g. "/project/node_modules").
 *   - It returns the bound HOST path, or null when no binding covers the path.
 *   - The registry guarantees containment internally (its entries are its own
 *     rootfs-private directories); the core does not re-check.
 */
interface BindingRegistry {

    /** The bound host path for [guestPath], or null when no binding covers it. */
    fun hostTargetFor(guestPath: String): String?

    /** V0 default: no bindings exist. V1 replaces [active] with the real registry. */
    object None : BindingRegistry {
        override fun hostTargetFor(guestPath: String): String? = null
    }

    companion object {
        @Volatile
        private var activeRegistry: BindingRegistry = None

        /** The registry the resolver core consults. Defaults to [None] until V1 installs the real one. */
        var active: BindingRegistry
            get() = activeRegistry
            set(value) {
                activeRegistry = value
            }
    }
}
