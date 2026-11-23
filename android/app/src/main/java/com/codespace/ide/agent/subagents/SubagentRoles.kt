package com.codespace.ide.agent.subagents

import com.codespace.ide.chat.panel.ToolCategory
import com.codespace.ide.chat.panel.ToolMeta

/**
 * PROJECT 3.3 (2026-11-15): what a subagent is allowed to be.
 *
 * The audit found one genuinely strong idea in Brahma's planner that ours lacked: a *role* on each
 * step. A role is what turns a flat plan into a delegation, because "the researcher reads" and "the
 * coder writes" are different jobs with different tools. Brahma carries
 * `PlanStep.role` over `SubagentRole { GENERAL, RESEARCHER, BUILDER, TESTER }`; VN Code carried no
 * role at all, so every step was "the agent does this".
 *
 * The design rule here is the one that keeps roles from becoming a second permission system: a role
 * can only ever NARROW the tool set the caller already has. [scope] intersects the role's categories
 * with the tools the session is permitted to expose, and a read-only role additionally drops every
 * destructive tool no matter what the role's categories contain. There is no path by which a
 * subagent obtains a tool the user's own session could not use.
 *
 * Pure Kotlin: the caller supplies the permitted names and the metadata lookup, so this is asserted
 * on the JVM without an Android runtime.
 */
internal object SubagentRoles {

    /**
     * Nine roles, chosen for work that actually differs. Brahma's four are a subset of these in
     * spirit; the additions are the ones that pay for themselves in a real IDE: `DEBUGGER` needs the
     * error and test surface, `SECURITY` must be read-only by construction, and `PLANNER` exists so a
     * delegation can produce a plan without gaining the ability to execute it.
     */
    enum class Role(
        val id: String,
        val label: String,
        /** Read-only roles can never receive a destructive tool, whatever their categories say. */
        val mayWrite: Boolean,
        val categories: Set<ToolCategory>,
        val explicit: Set<String> = emptySet(),
    ) {
        PLANNER(
            id = "planner",
            label = "Planning coordinator",
            mayWrite = false,
            // A planner reads what exists and records intent; it does not edit or run anything.
            categories = setOf(ToolCategory.FILES, ToolCategory.SEARCH, ToolCategory.AGENT, ToolCategory.TASKS),
        ),
        RESEARCHER(
            id = "researcher",
            label = "Research agent",
            mayWrite = false,
            categories = setOf(ToolCategory.WEB, ToolCategory.SEARCH, ToolCategory.FILES, ToolCategory.DATA),
        ),
        CODER(
            id = "coder",
            label = "Coding agent",
            mayWrite = true,
            categories = setOf(
                ToolCategory.FILES, ToolCategory.SEARCH, ToolCategory.EDIT, ToolCategory.DATA,
                ToolCategory.NOTEBOOKS,
            ),
        ),
        DEBUGGER(
            id = "debugger",
            label = "Debugging agent",
            mayWrite = true,
            categories = setOf(
                ToolCategory.FILES, ToolCategory.SEARCH, ToolCategory.EDIT, ToolCategory.TERMINAL,
                ToolCategory.TESTING, ToolCategory.GIT,
            ),
        ),
        TESTER(
            id = "tester",
            label = "Testing agent",
            mayWrite = true,
            categories = setOf(
                ToolCategory.FILES, ToolCategory.SEARCH, ToolCategory.TERMINAL, ToolCategory.TESTING,
            ),
            explicit = setOf("write_file"),
        ),
        DOCS(
            id = "docs",
            label = "Documentation agent",
            mayWrite = true,
            categories = setOf(ToolCategory.FILES, ToolCategory.SEARCH, ToolCategory.EDIT, ToolCategory.SKILLS),
        ),
        FILE_ANALYSIS(
            id = "file_analysis",
            label = "File and document analysis agent",
            mayWrite = false,
            categories = setOf(ToolCategory.FILES, ToolCategory.SEARCH, ToolCategory.DATA),
        ),
        SECURITY(
            id = "security",
            label = "Security reviewer",
            mayWrite = false,
            // Deliberately no EDIT and no TERMINAL: a reviewer that could change the thing it reviews
            // is not a reviewer. Destructive tools are dropped even inside the categories it has.
            categories = setOf(ToolCategory.FILES, ToolCategory.SEARCH, ToolCategory.GIT, ToolCategory.CONNECTORS, ToolCategory.MCP),
            explicit = setOf("detect_secrets"),
        ),
        REVIEW(
            id = "review",
            label = "Code reviewer",
            mayWrite = false,
            categories = setOf(ToolCategory.FILES, ToolCategory.SEARCH, ToolCategory.GIT, ToolCategory.TESTING),
        ),
        ;

        companion object {
            fun byId(id: String): Role? = entries.firstOrNull { it.id == id.trim().lowercase() }

            fun ids(): List<String> = entries.map { it.id }
        }
    }

    /** What a role may actually do in one session, after narrowing. */
    data class Scope(
        val role: Role,
        val tools: Set<String>,
        /** True only when a destructive tool survived the narrowing. */
        val canWrite: Boolean,
        /** Tools the role is entitled to that this session does not permit. Reported, never granted. */
        val withheld: Set<String> = emptySet(),
    )

    sealed class Result {
        data class Ok(val scope: Scope) : Result()
        data class Refused(val reason: String) : Result()
    }

    /**
     * Narrow [permitted] down to what [roleId] may use.
     *
     * [metaOf] returns null for a name with no metadata; such a tool is treated as unavailable to
     * roles, because an unclassified tool cannot be shown to be safe for a read-only role.
     */
    fun scope(
        roleId: String,
        permitted: Set<String>,
        metaOf: (String) -> ToolMeta?,
    ): Result {
        val role = Role.byId(roleId)
            ?: return Result.Refused(
                "Unknown role '" + roleId + "'. Known roles: " + Role.ids().joinToString(", ") + "."
            )
        val wanted = LinkedHashSet<String>()
        val withheld = LinkedHashSet<String>()
        permitted.forEach { name ->
            val meta = metaOf(name)
            // PROJECT NINE 10.04 (catalog 84 WIRED): the engine's role policy is now the judge of a
            // role's explicit tool list, so the two systems cannot drift apart. An empty list means
            // the same thing it did before - no restriction from this route.
            val policy = com.codespace.ide.engine.RolePolicy.Role(role.id, role.explicit)
            // PROJECT EIGHT 8.8: an explicit entry used to short-circuit the read-only rule entirely,
            // so one line of catalogue editing could hand a read-only role a destructive tool. The
            // read-only check now runs first, whatever route the name arrived by.
            if (!mayInclude(role, meta)) {
                if (com.codespace.ide.engine.RolePolicy.permits(policy, name) || (meta != null && meta.category in role.categories)) {
                    withheld.add(name)
                }
                return@forEach
            }
            if (!com.codespace.ide.engine.RolePolicy.permits(policy, name)) {
                if (meta == null || meta.category !in role.categories) return@forEach
            }
            wanted.add(name)
        }
        if (wanted.isEmpty()) {
            return Result.Refused(
                role.label + " has no usable tools here: nothing the session permits falls inside " +
                    role.categories.joinToString(", ") { it.name.lowercase() } +
                    (if (!role.mayWrite) ", and the role is read-only" else "") + "."
            )
        }
        val canWrite = wanted.any { name -> metaOf(name)?.destructive == true || name in role.explicit && role.mayWrite && name.startsWith("write") }
        return Result.Ok(Scope(role = role, tools = wanted, canWrite = canWrite, withheld = withheld))
    }

    /**
     * The one rule that decides whether a role may hold a tool at all. Read-only is absolute: a role
     * that does not write can never be handed a tool the catalogue marks destructive, no matter how
     * the tool reached it (category match or an explicit list entry).
     */
    fun mayInclude(role: Role, meta: ToolMeta?): Boolean {
        // No metadata means the tool cannot be classified. A writing role keeps it (its call is
        // still gated), but a read-only role must not be handed something it cannot prove safe.
        if (meta == null) return role.mayWrite
        return role.mayWrite || !meta.destructive
    }

    /** True when a role declares itself read-only, used by the delegation policy before scheduling. */
    fun isReadOnly(roleId: String): Boolean = Role.byId(roleId)?.mayWrite == false
}
