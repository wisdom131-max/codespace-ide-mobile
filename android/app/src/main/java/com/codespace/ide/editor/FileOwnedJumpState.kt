package com.codespace.ide.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.codespace.ide.util.CanonicalPaths
import kotlinx.coroutines.delay

/** Resource identity is captured at request creation, never inferred after a tab switch. */
data class PendingEditorJump(val path: String, val line: Int, val serial: Long)

class FileOwnedJumpState(private val currentPath: () -> String?) : MutableState<Int> {
    var pending: PendingEditorJump? by mutableStateOf(null)
        private set
    private var lastExternal: PendingEditorJump? = null
    companion object { private val serial = java.util.concurrent.atomic.AtomicLong() }
    override var value: Int
        get() = pending?.line ?: 0
        set(line) {
            if (line <= 0) { pending = null; return }
            val path = currentPath() ?: return
            request(path, line)
        }
    override fun component1(): Int = value
    override fun component2(): (Int) -> Unit = { value = it }
    fun request(path: String, line: Int) {
        if (line <= 0) { pending = null; return }
        pending = PendingEditorJump(CanonicalPaths.canonicalKey(path), line, serial.incrementAndGet())
    }
    fun acceptExternal(jump: PendingEditorJump) {
        if (lastExternal != jump) { lastExternal = jump; request(jump.path, jump.line) }
    }
    fun lineFor(path: String): Int = pending?.takeIf {
        it.path == CanonicalPaths.canonicalKey(path)
    }?.line ?: 0
    fun clearIfCurrent(request: PendingEditorJump) { if (pending == request) pending = null }
    fun clearOtherFile(path: String?) {
        if (path == null || pending?.path != CanonicalPaths.canonicalKey(path)) pending = null
    }
}

/** Each newer request cancels the prior expiry, including repeated jumps to the same line. */
@Composable
fun OwnedJumpExpiryEffect(state: FileOwnedJumpState, timeoutMillis: Long = 1000L) {
    val request = state.pending
    LaunchedEffect(request) {
        if (request != null) { delay(timeoutMillis); state.clearIfCurrent(request) }
    }
}

/** Identity-filtered render happens synchronously; this effect only handles consumption. */
@Composable
fun OwnedEditorJumpEffect(state: FileOwnedJumpState, external: PendingEditorJump?, activePath: String?,
    onConsumed: (PendingEditorJump) -> Unit = {}) {
    val consume by rememberUpdatedState(onConsumed)
    LaunchedEffect(activePath) { state.clearOtherFile(activePath) }
    LaunchedEffect(external, activePath) {
        if (external != null && activePath != null && external.path == CanonicalPaths.canonicalKey(activePath)) {
            state.acceptExternal(external)
            consume(external)
        }
    }
    OwnedJumpExpiryEffect(state)
}
