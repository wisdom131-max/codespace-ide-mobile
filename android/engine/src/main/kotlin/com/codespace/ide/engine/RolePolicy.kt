package com.codespace.ide.engine

/**
 * PROJECT NINE 9.23 (catalog 84) - a role decides which tools exist for a session.
 *
 * Permissions were per-tool and global, so there was no way to say "in this session, only reading".
 * A role is a named allow-list, and allowAll exists for the owner's own default so nothing changes
 * for them.
 *
 * Filtering preserves the caller's order, because tool order is part of the prompt and shuffling
 * it would change the request for no reason.
 */
object RolePolicy {

    data class Role(val name: String, val allowed: Set<String>, val allowAll: Boolean = false)

    fun permits(role: Role, tool: String): Boolean = role.allowAll || role.allowed.contains(tool)

    fun filter(role: Role, tools: List<String>): List<String> = tools.filter { permits(role, it) }
}
