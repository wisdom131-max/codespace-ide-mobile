package com.codespace.ide.engine

class SubagentRuntime(private val maxDepth: Int = 1, private val maxParallel: Int = 3) {
    data class Task(val id: String, val prompt: String, val depth: Int = 0)
    data class Result(val id: String, val output: String, val ok: Boolean, val error: String = "")
    fun canSpawn(depth: Int): Boolean = depth < maxDepth
    fun run(tasks: List<Task>, depth: Int, delegate: (Task) -> Result): List<Result> {
        if (tasks.isEmpty()) return emptyList()
        if (!canSpawn(depth)) return tasks.map { Result(it.id, "", false, "depth limit reached") }
        return tasks.take(maxParallel).map { t ->
            runCatching { delegate(t) }.getOrElse { e -> Result(t.id, "", false, e.message ?: "delegate failed") }
        }
    }
}
