package com.codespace.ide.agent

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * AgentScheduler — schedules recurring or one-time tasks for the AI agent.
 * Mirrors Superagent's automation capability.
 * Uses a lightweight ScheduledExecutorService (no WorkManager overhead on 3GB device).
 * Tasks persist as JSON and are re-scheduled on app restart.
 */
object AgentScheduler {

    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    private val scheduledFutures = mutableMapOf<String, java.util.concurrent.ScheduledFuture<*>>()

    private fun tasksFile(context: Context): File {
        val dir = File(context.filesDir, "agent_scheduler")
        dir.mkdirs()
        return File(dir, "tasks.json")
    }

    private fun readTasks(context: Context): JSONObject {
        val file = tasksFile(context)
        if (!file.exists()) return JSONObject()
        return try { JSONObject(file.readText()) } catch (_: Exception) { JSONObject() }
    }

    private fun writeTasks(tasks: JSONObject, context: Context) {
        tasksFile(context).writeText(tasks.toString(2))
    }

    /**
     * Schedule a task. Cron expression format: "minute hour day month dayOfWeek"
     * For simplicity on mobile, we support:
     *  - "*/N * * * *" — every N minutes
     *  - "0 H * * *"   — daily at H:00 (hour in UTC)
     *  - "@once"       — one-time (runs immediately, 5s delay)
     */
    fun schedule(name: String, cron: String, command: String, context: Context): String {
        val tasks = readTasks(context)
        val task = JSONObject()
            .put("name", name)
            .put("cron", cron)
            .put("command", command)
            .put("created", System.currentTimeMillis())
        tasks.put(name, task)
        writeTasks(tasks, context)

        // Schedule based on cron
        when {
            cron == "@once" -> {
                val future = executor.schedule({
                    runCommand(command)
                }, 5, TimeUnit.SECONDS)
                scheduledFutures[name] = future
            }
            cron.startsWith("*/") && cron.contains("* * * *") -> {
                val minutes = cron.substringAfter("*/").substringBefore(" ").toIntOrNull() ?: 5
                val future = executor.scheduleAtFixedRate({
                    runCommand(command)
                }, minutes.toLong(), minutes.toLong(), TimeUnit.MINUTES)
                scheduledFutures[name] = future
            }
            cron.startsWith("0 ") && cron.contains("* * *") -> {
                val hour = cron.split(" ")[1].toIntOrNull() ?: 9
                // Calculate delay to next occurrence of that hour
                val now = java.util.Calendar.getInstance()
                val target = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, hour)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    if (before(now)) add(java.util.Calendar.DAY_OF_MONTH, 1)
                }
                val delay = (target.timeInMillis - now.timeInMillis) / 1000
                val future = executor.scheduleAtFixedRate({
                    runCommand(command)
                }, delay, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS)
                scheduledFutures[name] = future
            }
            else -> {
                // Default: treat as every-N-minutes
                val future = executor.scheduleAtFixedRate({
                    runCommand(command)
                }, 60, 60, TimeUnit.SECONDS)
                scheduledFutures[name] = future
            }
        }

        return "Task '$name' scheduled (cron: $cron). Command: $command"
    }

    fun listTasks(context: Context): String {
        val tasks = readTasks(context)
        if (tasks.length() == 0) return "No scheduled tasks."
        val sb = StringBuilder("Scheduled tasks (${tasks.length()}):\n")
        for (name in tasks.keys()) {
            val task = tasks.getJSONObject(name)
            sb.append("  $name [${task.getString("cron")}]: ${task.getString("command").take(100)}\n")
        }
        return sb.toString().trim()
    }

    fun cancel(name: String, context: Context): String {
        val tasks = readTasks(context)
        if (!tasks.has(name)) return "No task found with name '$name'"
        tasks.remove(name)
        writeTasks(tasks, context)
        scheduledFutures[name]?.cancel(false)
        scheduledFutures.remove(name)
        return "Cancelled task: '$name'"
    }

    /** Re-schedule all persisted tasks on app start */
    fun restoreAll(context: Context) {
        val tasks = readTasks(context)
        for (name in tasks.keys()) {
            val task = tasks.getJSONObject(name)
            schedule(
                task.getString("name"),
                task.getString("cron"),
                task.getString("command"),
                context
            )
        }
    }

    private fun runCommand(command: String) {
        try {
            val parts = command.split("\\s+".toRegex())
            val proc = ProcessBuilder(parts).redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
        } catch (_: Exception) {}
    }
}
