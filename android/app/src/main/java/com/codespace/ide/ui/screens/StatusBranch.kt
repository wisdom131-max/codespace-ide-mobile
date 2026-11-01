package com.codespace.ide.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.scm.ScmBranch
import com.codespace.ide.scm.ScmState
import com.codespace.ide.terminal.ProotInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The status bar's branch item: what it reads, and the sheet it opens.
 *
 * It used to be a label and nothing else. Three things were wrong with it:
 *
 *  1. It read `.git/HEAD` as a bare host path. A project that lives inside the proot rootfs is
 *     addressed by a guest path, so the read missed and the item fell back to a hardcoded
 *     "main" whatever the real branch was - a label that is wrong in the one place a user looks
 *     to find out where they are.
 *  2. It was `remember`ed on the project path alone, so `git checkout` from the terminal or the
 *     Source Control pane left the old name there until the whole shell recomposed for another
 *     reason.
 *  3. It did nothing when tapped. In VS Code that item opens the checkout picker; here the branch
 *     name was decoration.
 *
 * All three are gone: the head file is resolved host-first then guest-mapped, the label polls (a
 * 50-byte read per tick), and tapping opens [BranchPickerSheet], which lists real branches and
 * checks one out through ScmState - the same path the Source Control pane uses.
 */
internal object BranchStatus {

    const val FALLBACK: String = "main"

    /** Accepts the raw contents of `.git/HEAD` and returns what the status bar should show. */
    fun labelFromHead(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return FALLBACK
        if (text.startsWith("ref:")) {
            // Strip the two ref prefixes rather than taking the text after the last slash: that
            // shortcut renamed "refs/heads/feature/x" to "x" and turned "ref:" into the literal
            // label "ref:", which is precisely the class of wrong label this item was fixed for.
            val name = text.removePrefix("ref:")
                .trim()
                .removePrefix("refs/heads/")
                .removePrefix("refs/")
                .trim()
            return if (name.isEmpty()) FALLBACK else name
        }
        // Detached HEAD: the file holds a SHA, and a short one is what git itself shows.
        return if (text.length > 7) text.substring(0, 7) else text
    }

    /**
     * Host path first because ProjectPathResolver hands out paths that exist on the Android side;
     * the guest mapping is the proot case, where the project lives inside the rootfs.
     */
    fun headFile(context: Context, projectRootPath: String): File? {
        if (projectRootPath.isBlank()) return null
        val direct = File(projectRootPath, ".git/HEAD")
        if (direct.exists()) return direct
        val mapped = try {
            ProotInstaller.guestToHostPath(context, projectRootPath.trimEnd('/') + "/.git/HEAD")
        } catch (_: Throwable) {
            null
        }
        return if (mapped != null && mapped.exists()) mapped else null
    }

    fun read(context: Context, projectRootPath: String): String {
        val head = headFile(context, projectRootPath) ?: return FALLBACK
        return try {
            labelFromHead(head.readText())
        } catch (_: Throwable) {
            FALLBACK
        }
    }
}

/**
 * The branch name, refreshed while composed. Polling rather than a change bus on purpose: a
 * checkout can come from the ticket sheet, the Source Control pane, an agent `git` call or a
 * typed terminal command, and a 50-byte read every two seconds costs less than wiring four
 * sources into one event.
 */
@Composable
internal fun rememberGitBranchLabel(projectRootPath: String): String {
    val context = LocalContext.current
    val label by produceState(
        initialValue = BranchStatus.FALLBACK,
        key1 = projectRootPath,
    ) {
        while (true) {
            val next = withContext(Dispatchers.IO) { BranchStatus.read(context, projectRootPath) }
            if (next != value) value = next
            delay(2000)
        }
    }
    return label
}

/** The tappable chip itself: tree glyph, branch name, and a real tap target. */
@Composable
internal fun BranchStatusChip(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.AccountTree,
            contentDescription = "Switch branch",
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f))
    }
}

/**
 * Branch picker, opened from the status bar. Local branches only: a remote-only branch is not
 * something you can check out without first creating the local one, and listing it here would
 * offer a tap that fails.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BranchPickerSheet(
    projectRootPath: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scm = remember(context) { ScmState(context) }
    var branches by remember { mutableStateOf<List<ScmBranch>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    suspend fun refresh() {
        loading = true
        val list = withContext(Dispatchers.IO) { scm.branches(projectRootPath) }
        branches = list
        loading = false
    }

    LaunchedEffect(projectRootPath) { refresh() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("Switch branch", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            message?.let { text ->
                Text(text, fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
            }
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                val locals = branches.filter { !it.isRemote }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(locals, key = { it.name }) { branch ->
                        BranchRow(
                            name = branch.name,
                            isCurrent = branch.isCurrent,
                            enabled = !working,
                            onClick = {
                                if (!branch.isCurrent && !working) {
                                    working = true
                                    scope.launch {
                                        val (ok, result) = withContext(Dispatchers.IO) {
                                            scm.checkout(projectRootPath, branch.name)
                                        }
                                        working = false
                                        message = if (ok) "Switched to " + branch.name else result
                                        if (ok) refresh()
                                    }
                                }
                            },
                        )
                    }
                }
                if (locals.isEmpty()) {
                    Text(
                        "No local branches. Not a git repository, or nothing committed yet.",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("New branch", fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = newName.isNotBlank() && !working,
                    onClick = {
                        if (!working) {
                            working = true
                            scope.launch {
                                val name = newName.trim()
                                val (ok, result) = withContext(Dispatchers.IO) {
                                    scm.createBranch(projectRootPath, name)
                                }
                                if (ok) {
                                    val (created, detail) = withContext(Dispatchers.IO) {
                                        scm.checkout(projectRootPath, name)
                                    }
                                    message = if (created) "Created and switched to " + name else detail
                                    newName = ""
                                } else {
                                    message = result
                                }
                                working = false
                                refresh()
                            }
                        }
                    },
                ) { Text("Create", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun BranchRow(
    name: String,
    isCurrent: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (isCurrent) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Current branch",
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
