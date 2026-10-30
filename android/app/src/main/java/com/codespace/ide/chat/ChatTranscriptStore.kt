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
    private fun safe(id: String): String = safeSegment(id)

    companion object {
        /**
         * M1 — the ONE project-id-to-directory mapping. The session index lives in the same
         * per-project directory as the transcripts, so both must derive that directory the
         * same way or a store would read one folder while the transcripts were written to
         * another. Exposing it (instead of a second copy in the session package) is what
         * makes "the same project = the same folder" a fact rather than a coincidence.
         */
        fun safeSegment(id: String): String {
            val cleaned = id.trim().map { ch ->
                if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') ch else '_'
            }.joinToString("")
            return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "default" else cleaned.take(80)
        }
    }

    /**
     * M1 — the conversations this project actually has on disk.
     *
     * Only `*.txt` counts: a transcript backup is `<id>.txt.bak` and there is no `.txt`
     * suffix, and the session index is `sessions.index`, so neither can be mistaken for a
     * conversation. Sorted for a stable rebuild order.
     */
    fun ids(projectId: String): List<String> {
        val dir = File(rootDir, safeSegment(projectId))
        val files = try {
            dir.listFiles()
        } catch (_: Throwable) {
            null
        } ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(".txt") && it.name.length > 4 }
            .map { it.name.dropLast(4) }
            .filter { it.isNotBlank() }
            .sorted()
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
) : TranscriptSink, com.codespace.ide.chat.session.SessionTranscripts {

    private val files = TranscriptFiles(File(context.filesDir, ROOT_DIR))
    private val lock = Any()
    private val pending = HashMap<String, Job>()
    private val lastWriteMs = HashMap<String, Long>()

    /**
     * M1 — conversations that were DELETED, so a save that was already in flight cannot
     * recreate them.
     *
     * Cancelling the pending job closes almost all of the window, but a job that has already
     * passed its delay can be about to write. Without this marker that write lands AFTER the
     * delete, and the deleted conversation comes back as an empty-but-present transcript on
     * the next launch. The marker is cleared when a live session saves again, because by then
     * the conversation legitimately exists.
     */
    private val deleted = HashSet<String>()

    companion object {
        /**
         * M1 — the chat root under `filesDir`. The session index lives here too, inside each
         * project's directory, so the two layers must agree on this name or a store would read
         * a folder the transcripts are not written to.
         */
        const val ROOT_DIR = "chat"
    }

    override suspend fun load(projectId: String, conversationId: String): TranscriptLoad =
        withContext(Dispatchers.IO) { files.load(projectId, conversationId) }

    /**
     * M1 — the session store's synchronous view of a conversation. Reading is a plain file
     * read (the store calls it once per project open and once per adopted session), so it does
     * not need to be suspend and must not be mistaken for the persist contract above.
     */
    override fun readNow(projectId: String, conversationId: String): TranscriptLoad =
        files.load(projectId, conversationId)

    override fun ids(projectId: String): List<String> = files.ids(projectId)

    /**
     * Removes a conversation and its backup. An EMPTY message list is the existing "delete"
     * signal in [TranscriptFiles.saveNow], reused so there is one deletion path rather than two
     * that can disagree about the `.bak`.
     */
    override fun deleteNow(projectId: String, conversationId: String): String? {
        val key = projectId + "|" + conversationId
        synchronized(lock) {
            // Kill the debounced writer FIRST: otherwise its trailing state would be written
            // back after the files are gone.
            pending.remove(key)?.cancel()
            lastWriteMs.remove(key)
            deleted.add(key)
        }
        val error = files.saveNow(projectId, conversationId, emptyList())
        if (error != null) {
            // The bytes are still there, so the conversation still exists: do not leave a
            // marker that would silently block its next legitimate save.
            synchronized(lock) { deleted.remove(key) }
        }
        return error
    }

    override fun save(
        projectId: String,
        conversationId: String,
        messages: List<StoredMessage>,
        onError: (String) -> Unit,
    ) {
        val key = projectId + "|" + conversationId
        synchronized(lock) {
            deleted.remove(key)
            pending[key]?.cancel()
            val now = System.currentTimeMillis()
            val wait = ((lastWriteMs[key] ?: 0L) + minIntervalMs - now).coerceAtLeast(0L)
            pending[key] = scope.launch {
                if (wait > 0L) delay(wait)
                // A delete that landed while this job was waiting wins: the conversation is
                // gone, so writing it would resurrect it.
                val gone = synchronized(lock) { key in deleted }
                if (gone) return@launch
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
