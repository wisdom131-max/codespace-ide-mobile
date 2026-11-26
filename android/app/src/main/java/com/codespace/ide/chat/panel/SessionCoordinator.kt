package com.codespace.ide.chat.panel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * STAGE 1 (Checkpoint 0.5) - ONE writer for session lifecycle.
 *
 * Cancellation used to be performed by whoever pressed Stop, on the UI thread, while a new send
 * could be starting on Dispatchers.Default. Both wrote the session's state, so a cancel that
 * arrived a moment late could clear the indicator of the attempt that had just replaced it.
 *
 * Now every lifecycle change is a value on one bounded queue, read by one consumer. The consumer is
 * the only thing that touches the attempt registry, the job and the indicator, so two of them can
 * never be in flight at once and ordering is the queue's order.
 *
 * Deliberately NOT a command: starting a send and finalising a reply stay on the caller's
 * coroutine. They must be synchronous with the send path that is building the request and the
 * handler that is writing the rows, and moving them onto a queue would only introduce a window
 * where the UI has not been told what the code already decided. Cancellation and shutdown are the
 * operations that arrive from another thread with no ordering guarantee, and those are what this
 * owns.
 *
 * Capacity is the owner's provisional 64. A cancel must never be dropped, so when the queue is full
 * the command is delivered from a non-cancellable coroutine instead of being refused: the queue is
 * a buffer, not a policy.
 */
internal sealed interface SessionCommand {
    /** Stop the attempt numbered [gen]. Ignored when a newer attempt already owns the session. */
    data class Cancel(val gen: Int, val why: String) : SessionCommand

    /** The session is going away; stop the consumer after everything already queued is applied. */
    data object Shutdown : SessionCommand
}

internal class SessionCoordinator(
    private val scope: CoroutineScope,
    capacity: Int = CAPACITY,
    private val onCancel: (gen: Int, why: String) -> Unit,
) {
    private val commands = Channel<SessionCommand>(capacity)

    /**
     * Serialises the application of commands. The consumer normally holds it; [drainNow] takes it
     * when a caller must not continue until the queue is empty (switching away from a session, where
     * the registry reads `isStreaming` on the very next line). One writer either way - this lock is
     * what makes "the consumer is the only writer" true even when a caller asks for a drain.
     */
    private val applying = Any()

    private fun apply(command: SessionCommand) = synchronized(applying) {
        when (command) {
            is SessionCommand.Cancel -> onCancel(command.gen, command.why)
            SessionCommand.Shutdown -> Unit
        }
    }

    /** The single consumer. Nothing else reads this queue. */
    private val consumer: Job = scope.launch {
        for (command in commands) {
            if (command is SessionCommand.Shutdown) break
            apply(command)
        }
    }

    /**
     * Applies everything already queued, on the caller's thread, before returning.
     *
     * Used by the few paths that must observe the result immediately - leaving a streaming session,
     * where the manager asks "is it still streaming?" on the next line. It is a drain, not a second
     * writer: [apply] is serialised, so a command is never applied twice or out of order.
     */
    fun drainNow() {
        // Taking the lock FIRST is the whole point. A queued command may already have been picked up
        // by the consumer, in which case the queue looks empty while its write is still in flight -
        // and the caller would read the stale value. Waiting on the lock waits for that write.
        synchronized(applying) {
            while (true) {
                val next = commands.tryReceive().getOrNull() ?: return
                if (next is SessionCommand.Shutdown) return
                apply(next)
            }
        }
    }

    /**
     * Enqueues without blocking. Returns false only when the queue is full, in which case the caller
     * has already been handed the non-cancellable route by [deliver].
     */
    fun offer(command: SessionCommand): Boolean = commands.trySend(command).isSuccess

    /**
     * Delivers [command] whatever the queue looks like. Used for cancellation, where losing the
     * command means a spinner that never stops.
     */
    suspend fun deliver(command: SessionCommand) {
        if (offer(command)) return
        withContext(NonCancellable) { commands.send(command) }
    }

    /**
     * Non-suspending submission that cannot lose a command.
     *
     * Three cases, and the last one matters: a session that has already been disposed has a CLOSED
     * queue, so offering fails, delivering throws, and a Stop would vanish - the indicator staying
     * on for ever. With no consumer left there is nothing to order against, so the command is
     * applied directly. That is still one writer: the consumer is gone.
     */
    fun submit(command: SessionCommand) {
        if (offer(command)) return
        if (!isActive() || commands.isClosedForSend) {
            apply(command)
            return
        }
        scope.launch { deliver(command) }
    }

    /** Closes the queue; the consumer drains what is already there and exits. */
    fun shutdown() {
        commands.close()
    }

    /** True while the consumer is alive. Used by tests and by dispose(). */
    fun isActive(): Boolean = consumer.isActive

    companion object {
        const val CAPACITY = 64
    }
}
