package com.codespace.ide.engine

/**
 * The mode as a VALUE that carries what it may do (owner request, 2026-11-23).
 *
 * In VS Code a mode is a data object holding its own tool set and instructions, resolved fresh on
 * every request - never a stored row that a failed read can quietly turn back into Ask. This is that
 * idea reduced to the part that decides anything: which tool groups a mode may use.
 */
object ModePolicy {

    enum class Group { READ, EDIT, EXECUTE, CONNECT, MEMORY }

    /** Unknown or blank names resolve to the most restrictive mode, never to a permissive one. */
    private fun normalise(mode: String?): String =
        when (mode?.trim()?.lowercase()) {
            "agent" -> "agent"
            "edit" -> "edit"
            else -> "ask"
        }

    fun groupsFor(mode: String?): Set<Group> = when (normalise(mode)) {
        "agent" -> Group.values().toSet()
        "edit" -> setOf(Group.READ, Group.EDIT, Group.MEMORY)
        else -> setOf(Group.READ, Group.MEMORY)
    }

    fun allows(mode: String?, group: Group): Boolean = group in groupsFor(mode)

    /** The one line the model is told about its own limits, per mode. */
    fun instructionFor(mode: String?): String = when (normalise(mode)) {
        "agent" ->
            "You are in Agent mode: you may read, edit, run commands and use connectors. " +
                "Act on the request instead of describing what the user could do."
        "edit" ->
            "You are in Edit mode: you may read the project and change files, but do not run " +
                "commands or start background work. If a task needs that, say so and name it."
        else ->
            "You are in Ask mode: you may read and search the project and answer, but you may not " +
                "change files or run commands. If the user wants a change, tell them to switch to Edit " +
                "or Agent mode - do not claim you are blocked or unable."
    }
}
