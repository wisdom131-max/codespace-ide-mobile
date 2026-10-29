package com.codespace.ide.chat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What a load attempt produced, and anything the user deserves to be told about it. */
/** PUBLIC for the same reason as [TranscriptSink]: it is part of that contract. */
data class TranscriptLoad(
    val messages: List<StoredMessage> = emptyList(),
    /** Honest note to show in the transcript when something was recovered or lost. */
    val note: String? = null,
)

/**
 * Where a conversation is persisted. An interface so the session can be driven in JVM
 * tests without a Context, a filesystem or a coroutine clock.
 */
/**
 * BATCH 4: the persistence seam. PUBLIC because ChatSession's public constructor takes one
 * (Kotlin forbids a public signature exposing an internal type, which is exactly how this
 * failed CI on the first push - the implementation stays internal, the contract does not).
 */
interface TranscriptSink {
    suspend fun load(projectId: String, conversationId: String): TranscriptLoad
    fun save(projectId: String, conversationId: String, messages: List<StoredMessage>, onError: (String) -> Unit)
}

/**
 * The file layer: one file per (project, conversation), written atomically, with the
 * previous good copy kept as .bak.
 *
 * Deliberately under the app's private files dir and NOT inside the project: a chat
 * transcript is app state, and dropping it into the user's repository would show up in
 * git status, in the Explorer, and in any archive they export. It also means persistence
 * works for a project whose root is unavailable (proot unmounted, storage ejected) —
 * the failure is then a load note, not a lost history.
 *
 * Pure java.io so it is unit-tested on the JVM against a temp directory.
 */
internal class TranscriptFiles(private val rootDir: File) {

    fun fileFor(projectId: String, conversationId: String): File =
        File(File(rootDir, safe(projectId)), safe(conversationId) + ".txt")

    private fun backupFor(target: File): File = File(target.parentFile, target.name + ".bak")

    /** The id comes from a project path, so it is sanitised into a single safe segment. */
    private fun safe(id: String): String {
        val cleaned = id.trim().map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') ch else '_'
        }.joinToString("")
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "default" else cleaned.take(80)
    }

    fun load(projectId: String, conversationId: String): TranscriptLoad {
        return try {
            val target = fileFor(projectId, conversationId)
            val bak = backupFor(target)
            val primaryRaw = if (target.exists()) target.readText() else null
            if (primaryRaw != null) {
                val primary = ChatTranscript.decode(primaryRaw)
                if (!primary.unreadable) {
                    val note = if (primary.skippedLines > 0)
                        "Restored this conversation, but " + primary.skippedLines +
                            " stored line(s) could not be read and were skipped." else null
                    return TranscriptLoad(primary.messages, note)
                }
            }
            // Either there was no file, or it is not in a format this build understands.
            if (bak.exists()) {
                val backup = ChatTranscript.decode(bak.readText())
                if (!backup.unreadable && backup.messages.isNotEmpty()) {
                    return TranscriptLoad(
                        backup.messages,
                        "The saved conversation was damaged, so the previous copy was restored instead.",
                    )
                }
            }
            if (primaryRaw == null) TranscriptLoad()
            else TranscriptLoad(
                emptyList(),
                "The saved conversation could not be read (unrecognised format). " +
                    "It was left on disk and a new conversation was started.",
            )
        } catch (t: Throwable) {
            // Storage errors are reported, never thrown into the UI.
            TranscriptLoad(
                emptyList(),
                "Could not read the saved conversation: " + (t.message ?: t.javaClass.simpleName),
            )
        }
    }

    /** Writes the transcript; returns an error message or null. Empty list deletes. */
    fun saveNow(projectId: String, conversationId: String, messages: List<StoredMessage>): String? {
        return try {
            val target = fileFor(projectId, conversationId)
            val bak = backupFor(target)
            if (messages.isEmpty()) {
                // "Clear conversation" must actually clear: a stale file would come back
                // as a ghost transcript on the next launch.
                if (target.exists()) target.delete()
                if (bak.exists()) bak.delete()
                return null
            }
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(ChatTranscript.encode(messages))
            if (target.exists()) {
                try {
                    target.copyTo(bak, overwrite = true)
                } catch (_: Throwable) {
                    // A missing backup is survivable; a half-written primary is not.
                }
            }
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
            null
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }
}

/**
 * Debounced writer on its own coroutine scope.
 *
 * Two rules that matter on a phone:
 *  - WRITES ARE GROUPED. A streaming reply produces a delta every few ms; writing each one
 *    would be dozens of file writes per second on flash storage. At most one write per
 *    [minIntervalMs], with the trailing state always written.
 *  - THE SCOPE IS NOT THE SESSION'S. Closing the chat panel must not cancel a pending
 *    save, or the last turn of every conversation would be the one that never reached
 *    disk.
 */
internal class ChatTranscriptStore(
    context: Context,
    private val minIntervalMs: Long = 1_200L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : TranscriptSink {

    private val files = TranscriptFiles(File(context.filesDir, "chat"))
    private val lock = Any()
    private val pending = HashMap<String, Job>()
    private val lastWriteMs = HashMap<String, Long>()

    override suspend fun load(projectId: String, conversationId: String): TranscriptLoad =
        withContext(Dispatchers.IO) { files.load(projectId, conversationId) }

    override fun save(
        projectId: String,
        conversationId: String,
        messages: List<StoredMessage>,
        onError: (String) -> Unit,
    ) {
        val key = projectId + "|" + conversationId
        synchronized(lock) {
            pending[key]?.cancel()
            val now = System.currentTimeMillis()
            val wait = ((lastWriteMs[key] ?: 0L) + minIntervalMs - now).coerceAtLeast(0L)
            pending[key] = scope.launch {
                if (wait > 0L) delay(wait)
                val error = files.saveNow(projectId, conversationId, messages)
                synchronized(lock) {
                    lastWriteMs[key] = System.currentTimeMillis()
                    pending.remove(key)
                }
                if (error != null) onError(error)
            }
        }
    }
}
