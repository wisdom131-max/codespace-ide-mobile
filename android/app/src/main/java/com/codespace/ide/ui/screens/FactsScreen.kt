package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.agent.facts.Fact
import com.codespace.ide.agent.facts.FactCategory
import com.codespace.ide.agent.facts.FactScope
import com.codespace.ide.agent.facts.FactStatus
import com.codespace.ide.agent.facts.FactDates
import com.codespace.ide.agent.facts.FactText
import com.codespace.ide.agent.facts.MemoryEvents
import com.codespace.ide.agent.facts.FactsStore
import com.codespace.ide.agent.facts.MemoryTimeline
import com.codespace.ide.agent.facts.ReviewQueue
import com.codespace.ide.agent.facts.TimelineEventType
import com.codespace.ide.agent.facts.TimeSource

/**
 * FACTS (2026-11-11) — the memory management screen, under Settings.
 *
 * Mobile first: one scrolling list, a sticky search field, and two filter rows of small chips. No
 * tabs, no side panel, no master-detail - the phone is the primary device for this IDE and every
 * pixel spent on chrome is a fact the user cannot see.
 *
 * Visual conventions are the Settings dialog's own: the same dark surfaces, the same accent, and
 * the owner's panel rule (8-12dp corners, 12dp horizontal / 10dp vertical padding).
 *
 * Nothing here writes unless the user acts: reads come from [FactsStore.load], and every mutation
 * goes through the same store functions the chat tools use, so the screen can never disagree with
 * what the model retrieves.
 */
@Composable
fun FactsScreen(
    projectId: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val bg = Color(0xFF1E1E1E)
    val surface = Color(0xFF252526)
    val panel = Color(0xFF2D2D2D)
    val textPri = Color(0xFFE0E0E0)
    val textSec = Color(0xFF888888)
    val accent = Color(0xFF4FC3F7)
    val divider = Color(0xFF333333)
    val warn = Color(0xFFFFB74D)
    val danger = Color(0xFFEF5350)

    // STAGE 1: the reload signal belongs to the STORE, not to this screen. A Copilot save, a panel
    // edit and a delete all publish the same event, and only a write that actually landed publishes
    // at all - so this screen can never show a fact that is not on disk.
    val revision by MemoryEvents.revision.collectAsState()
    // STAGE 1: history is opt-in. Superseded records stay on disk and stay reachable; they are
    // simply not shown as if they were current instructions.
    var showHistory by remember { mutableStateOf(false) }
    val facts = remember(revision) { FactsStore.load(context) }
    var query by remember { mutableStateOf("") }
    var scopeFilter by remember { mutableStateOf<FactScope?>(null) }
    var categoryFilter by remember { mutableStateOf<FactCategory?>(null) }
    var editing by remember { mutableStateOf<Fact?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Fact?>(null) }
    var message by remember { mutableStateOf("") }

    // Project facts from OTHER projects are not merely hidden behind a filter: they are not loaded
    // into this screen at all, so they cannot be edited into this project by mistake.
    val visible = remember(revision, query, scopeFilter, categoryFilter, projectId, showHistory) {
        facts.filter { f ->
            (scopeFilter == null || f.scope == scopeFilter) &&
                (categoryFilter == null || f.category == categoryFilter) &&
                (showHistory || f.status != FactStatus.SUPERSEDED) &&
                (f.scope == FactScope.USER || f.projectId == projectId) &&
                (query.isBlank() || matchesQuery(f, query))
        }
    }
    val mine = facts.count { it.scope == FactScope.PROJECT && it.projectId == projectId }
    val userCount = facts.count { it.scope == FactScope.USER }

    Column(Modifier.fillMaxSize().background(bg)) {
        // ── title bar ──
        Row(
            Modifier.fillMaxWidth().background(surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Facts", color = textPri, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    userCount.toString() + " user, " + mine + " project, " +
                        facts.count { it.status == FactStatus.CONFLICT } + " conflicted",
                    color = textSec, fontSize = 11.sp,
                )
            }
            TextButton(onClick = { adding = true }) {
                Text("+ Add", color = accent, fontSize = 13.sp)
            }
            TextButton(onClick = onDismiss) { Text("Close", color = textSec, fontSize = 13.sp) }
        }

        // ── search ──
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(10.dp)).background(panel)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = textPri, fontSize = 13.sp),
                cursorBrush = SolidColor(accent),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search facts...", color = textSec, fontSize = 13.sp)
                    inner()
                },
            )
        }

        // ── filters: scope, then category ──
        FilterRow(
            labels = listOf("All scopes", "User", "Project"),
            selected = when (scopeFilter) {
                null -> 0
                FactScope.USER -> 1
                FactScope.PROJECT -> 2
            },
            accent = accent, surface = panel, textPri = textPri, textSec = textSec,
            onSelect = { idx ->
                scopeFilter = when (idx) { 1 -> FactScope.USER; 2 -> FactScope.PROJECT; else -> null }
            },
        )
        FilterRow(
            labels = listOf("All categories") + FactCategory.entries.map { it.label },
            selected = if (categoryFilter == null) 0 else categoryFilter!!.ordinal + 1,
            accent = accent, surface = panel, textPri = textPri, textSec = textSec,
            onSelect = { idx ->
                categoryFilter = if (idx == 0) null else FactCategory.entries[idx - 1]
            },
        )
        FilterRow(
            labels = listOf("Current only", "With history"),
            selected = if (showHistory) 1 else 0,
            accent = accent, surface = panel, textPri = textPri, textSec = textSec,
            onSelect = { idx -> showHistory = idx == 1 },
        )

        // STAGE 8: candidates waiting for a decision, above the saved facts so they cannot be
        // mistaken for memories the app already trusts.
        ReviewSection(
            context = context,
            revision = revision,
            accent = accent, surface = panel, textPri = textPri, textSec = textSec,
            danger = danger, warn = warn,
        )
    TimelineSection(context, revision, accent, textPri, textSec, panel)

        if (message.isNotBlank()) {
            Text(
                message, color = warn, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        if (visible.isEmpty()) {
            EmptyFacts(
                hasAny = facts.isNotEmpty(),
                hasProject = projectId != null,
                textPri = textPri, textSec = textSec, accent = accent,
                onAdd = { adding = true },
            )
        } else {
            LazyColumn(
                // OWNER REPORT (2026-11-23): the facts list would not scroll. fillMaxSize() inside
                // a Column that already has the review and timeline sections above it asked for the
                // WHOLE screen, so the list was laid out against a height it could not have and the
                // content past the fold was unreachable. weight(1f) takes exactly the space that is
                // left and scrolls inside it.
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
            ) {
                items(visible, key = { it.id }) { fact ->
                    FactCard(
                        fact = fact,
                        projectName = null,
                        accent = accent, surface = panel, textPri = textPri,
                        textSec = textSec, divider = divider, warn = warn, danger = danger,
                        onEdit = { editing = fact },
                        onDelete = { confirmDelete = fact },
                        onResolve = {
                            // Resolution is an EDIT, not a silent pick: the user decides the wording.
                            message = FactsStore.update(
                                context, fact.id,
                                content = fact.content,
                                category = fact.category,
                                scope = fact.scope,
                                projectId = fact.projectId,
                            ) ?: "Conflict cleared: this fact is now the wording you confirmed."
                        },
                    )
                }
            }
        }
    }

    // ── add ──
    if (adding) {
        FactEditor(
            title = "New fact",
            initial = null,
            projectId = projectId,
            accent = accent, textPri = textPri, textSec = textSec, surface = bg, divider = divider,
            onDismiss = { adding = false },
            onSave = { content, scope, category, citation ->
                when (val r = FactsStore.save(
                    context = context,
                    content = content,
                    scope = scope,
                    projectId = if (scope == FactScope.PROJECT) projectId else null,
                    category = category,
                    source = com.codespace.ide.agent.facts.FactSource.MANUAL,
                    citation = citation,
                )) {
                    is FactsStore.SaveResult.Saved -> {
                        message = when (r.decision) {
                            com.codespace.ide.agent.facts.FactDecision.CORRECTION ->
                                "Saved as a correction of an existing fact."
                            com.codespace.ide.agent.facts.FactDecision.DUPLICATE ->
                                "That fact already existed; nothing was duplicated."
                            com.codespace.ide.agent.facts.FactDecision.CONFLICT ->
                                "Saved and marked as a conflict with an existing fact. Resolve it below."
                            com.codespace.ide.agent.facts.FactDecision.NEW -> "Fact saved."
                        }
                        adding = false
                    }
                    is FactsStore.SaveResult.Refused -> message = r.reason
                    FactsStore.SaveResult.Full -> message = "Memory is full (500 facts)."
                    FactsStore.SaveResult.WriteFailed -> message = "The store write failed."
                }
            },
        )
    }

    // ── edit ──
    editing?.let { fact ->
        FactEditor(
            title = "Edit fact",
            initial = fact,
            projectId = projectId,
            accent = accent, textPri = textPri, textSec = textSec, surface = bg, divider = divider,
            onDismiss = { editing = null },
            onSave = { content, scope, category, citation ->
                val err = FactsStore.update(
                    context, fact.id,
                    content = content,
                    category = category,
                    scope = scope,
                    projectId = if (scope == FactScope.PROJECT) projectId else null,
                )
                if (err != null) {
                    message = err
                } else {
                    message = "Fact updated."
                    editing = null
                }
            },
        )
    }

    // ── delete: always confirmed ──
    confirmDelete?.let { fact ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this fact?", color = textPri, fontSize = 15.sp) },
            text = {
                Column {
                    Text(fact.content.take(180), color = textPri, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "It will no longer be remembered or retrieved. This cannot be undone.",
                        color = textSec, fontSize = 11.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = FactsStore.delete(context, fact.id)
                    message = if (ok) "Fact deleted." else "The delete write failed; nothing changed."
                    confirmDelete = null
                }) { Text("Delete", color = danger, fontSize = 13.sp) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text("Cancel", color = textSec, fontSize = 13.sp)
                }
            },
            containerColor = Color(0xFF252526),
        )
    }
}

/** Search runs over the same tokens the dedupe uses, so the screen and the store agree. */
private fun matchesQuery(f: Fact, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    if (f.content.lowercase().contains(q)) return true
    if (f.legacyKey?.lowercase()?.contains(q) == true) return true
    val terms = FactText.tokens(q)
    if (terms.isEmpty()) return true
    val tokens = FactText.tokenSet(f.content)
    return terms.all { t -> tokens.any { it.contains(t) } }
}

/**
 * One horizontal scroller of chips. Deliberately not a dropdown: on a phone, tapping the value you
 * want is one gesture, and a dropdown plus a list is two.
 */
@Composable
private fun FilterRow(
    labels: List<String>,
    selected: Int,
    accent: Color,
    surface: Color,
    textPri: Color,
    textSec: Color,
    onSelect: (Int) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(labels.size) { idx ->
            val on = idx == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) accent.copy(alpha = 0.18f) else surface)
                    .clickable { onSelect(idx) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    labels[idx],
                    color = if (on) accent else textSec,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun EmptyFacts(
    hasAny: Boolean,
    hasProject: Boolean,
    textPri: Color,
    textSec: Color,
    accent: Color,
    onAdd: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (hasAny) "No facts match this filter" else "No facts yet",
            color = textPri, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasAny) {
                "Clear the search or pick another category to see the rest."
            } else if (hasProject) {
                "Copilot saves durable facts here when you ask it to remember something. " +
                    "A project fact stays in this project; a user fact follows you everywhere."
            } else {
                "Copilot saves durable facts here when you ask it to remember something. " +
                    "Open a project to add project facts."
            },
            color = textSec, fontSize = 12.sp,
        )
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.18f))
                .clickable(onClick = onAdd)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) { Text("Add a fact", color = accent, fontSize = 12.sp) }
    }
}

/** One fact: statement, scope + category line, provenance line, and its actions. */
@Composable
private fun FactCard(
    fact: Fact,
    projectName: String?,
    accent: Color,
    surface: Color,
    textPri: Color,
    textSec: Color,
    divider: Color,
    warn: Color,
    danger: Color,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onResolve: () -> Unit,
) {
    val conflicted = fact.status == FactStatus.CONFLICT
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(surface)
            .then(
                if (conflicted) Modifier.padding(1.dp) else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (fact.scope == FactScope.PROJECT) "PROJECT" else "USER",
                color = if (fact.scope == FactScope.PROJECT) accent else textSec,
                fontSize = 10.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Text(fact.category.label, color = textSec, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            if (conflicted) Text("CONFLICT", color = warn, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            else if (fact.status == FactStatus.SUPERSEDED) Text("SUPERSEDED", color = textSec, fontSize = 10.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(fact.content, color = textPri, fontSize = 13.sp)
        val prov = fact.provenanceLine()
        if (prov.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(prov, color = textSec, fontSize = 10.sp)
        }
        // STAGE 1: every date says WHICH date it is. The conversation date is read from the
        // transcript link (stage 4); until that exists it is honestly "Not recorded" and the
        // memory's own creation time is never printed in its place.
        Spacer(Modifier.height(4.dp))
        Text(
            "Saved " + FactDates.local(fact.createdAt) +
                if (FactDates.known(fact.updatedAt) && fact.updatedAt != fact.createdAt) {
                    "  \u00b7  Updated " + FactDates.local(fact.updatedAt)
                } else "",
            color = textSec, fontSize = 10.sp,
        )
        Text(
            "Conversation date: " + FactDates.conversation(null) +
                if (FactDates.known(fact.lastUsedAt)) {
                    "  \u00b7  Last used " + FactDates.local(fact.lastUsedAt)
                } else "",
            color = textSec, fontSize = 10.sp,
        )
        if (projectName != null) {
            Text(projectName, color = textSec, fontSize = 10.sp)
        }
        if (conflicted) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Two facts disagree. Copilot is told this is unresolved, so it will not present " +
                    "either as settled. Confirm the wording to resolve it.",
                color = warn, fontSize = 10.sp,
            )
            Row {
                TextButton(onClick = onResolve) { Text("Confirm this wording", color = accent, fontSize = 11.sp) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onEdit) { Text("Edit", color = accent, fontSize = 11.sp) }
            TextButton(onClick = onDelete) { Text("Delete", color = danger, fontSize = 11.sp) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(divider))
    }
}

/**
 * Add/edit sheet. It is a dialog rather than a screen so the list stays visible behind it - the
 * user is correcting a fact they are looking at.
 */
@Composable
private fun FactEditor(
    title: String,
    initial: Fact?,
    projectId: String?,
    accent: Color,
    textPri: Color,
    textSec: Color,
    surface: Color,
    divider: Color,
    onDismiss: () -> Unit,
    onSave: (String, FactScope, FactCategory, String?) -> Unit,
) {
    var content by remember(initial?.id) { mutableStateOf(initial?.content.orEmpty()) }
    var scope by remember(initial?.id) {
        mutableStateOf(initial?.scope ?: if (projectId != null) FactScope.PROJECT else FactScope.USER)
    }
    var category by remember(initial?.id) { mutableStateOf(initial?.category ?: FactCategory.GENERAL) }
    var citation by remember(initial?.id) { mutableStateOf(initial?.citation.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = textPri, fontSize = 15.sp) },
        text = {
            Column {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 72.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xFF2D2D2D))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = content,
                        onValueChange = { content = it },
                        textStyle = TextStyle(color = textPri, fontSize = 13.sp),
                        cursorBrush = SolidColor(accent),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (content.isEmpty()) {
                                Text("What should Copilot remember?", color = textSec, fontSize = 13.sp)
                            }
                            inner()
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("Scope", color = textSec, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FactScope.entries.forEach { s ->
                        val on = s == scope
                        val enabled = s == FactScope.USER || projectId != null
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (on) accent.copy(alpha = 0.18f) else Color(0xFF2D2D2D))
                                .clickable(enabled = enabled) { scope = s }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                if (s == FactScope.USER) "User (all projects)" else "This project only",
                                color = if (!enabled) textSec.copy(alpha = 0.5f) else if (on) accent else textSec,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Category", color = textSec, fontSize = 10.sp)
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(FactCategory.entries.size) { idx ->
                        val c = FactCategory.entries[idx]
                        val on = c == category
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (on) accent.copy(alpha = 0.18f) else Color(0xFF2D2D2D))
                                .clickable { category = c }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) { Text(c.label, color = if (on) accent else textSec, fontSize = 11.sp) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xFF2D2D2D))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = citation,
                        onValueChange = { citation = it },
                        singleLine = true,
                        textStyle = TextStyle(color = textPri, fontSize = 12.sp),
                        cursorBrush = SolidColor(accent),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (citation.isEmpty()) {
                                Text("File or link this can be checked against (optional)",
                                    color = textSec, fontSize = 12.sp)
                            }
                            inner()
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(content, scope, category, citation.ifBlank { null }) },
                enabled = content.isNotBlank(),
            ) { Text("Save", color = if (content.isNotBlank()) accent else textSec, fontSize = 13.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = textSec, fontSize = 13.sp) }
        },
        containerColor = surface,
    )
}


/**
 * STAGE 8 — the review queue, inside the Facts screen.
 *
 * Deliberately small: at most five rows are drawn, so a phone never lays out an unbounded list.
 *
 * OWNER DECISION (2026-11-23): "I don't need to accept facts or memory before it saves." This is no
 * longer a gate. A candidate is saved the moment it is seen, and the section becomes the record of
 * what was saved automatically, with Remove as the undo. Nothing about the owner's complaint changes
 * the honesty rule below: "Not recorded" is still shown rather than a guessed date or message.
 */
@Composable
private fun ReviewSection(
    context: android.content.Context,
    revision: Int,
    accent: Color,
    surface: Color,
    textPri: Color,
    textSec: Color,
    danger: Color,
    warn: Color,
) {
    val dir = remember(context) { FactsStore.memoryDir(context) }
    val queue = remember(revision) { ReviewQueue.loadFrom(dir) }
    val pending = remember(queue) { ReviewQueue.pending(queue) }
    val rejected = remember(queue) { ReviewQueue.rejected(queue) }
    // No tap, no queue: every candidate becomes a fact as soon as this section is composed. This is
    // the whole point of the owner's request - memory that waits for permission is memory that is
    // never there when the copilot needs it.
    LaunchedEffect(queue) {
        ReviewQueue.pending(queue).forEach { ReviewQueue.accept(dir, it.id) }
    }
    var showRejected by remember { mutableStateOf(false) }
    if (pending.isEmpty() && rejected.isEmpty()) return

    // TOKEN-PANEL STYLE (owner request 2026-11-23): the same small curved-edge panel the Context
    // Window uses, so a section reads as a PANEL instead of a loose heading floating between lists.
    // This is the pattern the rest of the sections are being converted to.
    // OWNER CORRECTION (2026-11-23): "I didn't want it resized, I wanted the full size but with the
    // UI and styling of the token panel." The panel keeps the token-panel chrome but spans the FULL
    // width - no outer inset - so the section is not shrunk into a floating box. Inner padding stays
    // so the text does not touch the edge.
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)).background(surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            "Saved automatically (" + pending.size + ")",
            color = textSec,
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        for (c in pending.take(5)) {
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp)
                    .clip(RoundedCornerShape(10.dp)).background(surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(c.text, color = textPri, fontSize = 13.sp)
                Text(
                    "Suggested: " + c.rationale.lowercase().replace('_', ' ') + "  \u00b7  " +
                        c.scope.id + "  \u00b7  " + c.category.label,
                    color = textSec, fontSize = 10.sp,
                )
                // Provenance is stated, never invented: a missing message id is simply absent.
                Text(
                    "Conversation " + FactDates.local(c.sourceConversationAt) +
                        if (c.sourceMessageId != null) "  \u00b7  message " + c.sourceMessageId else "",
                    color = textSec, fontSize = 10.sp,
                )
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    // There is no Accept any more: it is already saved. Remove is the undo.
                    TextButton(onClick = { ReviewQueue.reject(dir, c.id) }) {
                        Text("Remove", color = danger, fontSize = 12.sp)
                    }
                }
            }
        }
        if (pending.size > 5) {
            Text("+" + (pending.size - 5) + " more waiting", color = textSec, fontSize = 10.sp)
        }
        if (rejected.isNotEmpty()) {
            Text(
                (if (showRejected) "Hide rejected (" else "Rejected (") + rejected.size + ")",
                color = textSec, fontSize = 10.sp,
                modifier = Modifier.padding(top = 8.dp).clickable { showRejected = !showRejected },
            )
            if (showRejected) {
                for (c in rejected.take(5)) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.text, color = textSec, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { ReviewQueue.reconsider(dir, c.id) }) {
                            Text("Reconsider", color = accent, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}


/**
 * STAGE 9 — the memory timeline, bounded to the newest few events.
 *
 * Deliberately a compact list, not a scrollable history of everything: this is a phone, and the
 * timeline document is capped at MemoryTimeline.MAX_EVENTS anyway. Each row shows the time the
 * APP acted, and separately whether the information carries a verified, user-stated or unknown
 * date. Those two are never merged into one date.
 */
@Composable
private fun TimelineSection(
    context: android.content.Context,
    revision: Int,
    accent: Color,
    textPri: Color,
    textSec: Color,
    surface: Color,
) {
    val events = remember(revision) {
        MemoryTimeline.loadFrom(FactsStore.memoryDir(context)).take(8)
    }
    if (events.isEmpty()) return
    // OWNER REPORT (2026-11-23): "the panel headers aren't well organized". The review section named
    // itself and the timeline did not, so the screen read as unrelated blocks stacked on each other.
    // Every section now names itself in the same place, at the same weight, before its rows.
    Text(
        "History",
        color = textSec, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Timeline (" + events.size + ")",
        color = textPri, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    Spacer(Modifier.height(4.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (e in events) {
            val label = when (e.type) {
                TimelineEventType.CREATED -> "saved"
                TimelineEventType.ACCEPTED -> "accepted"
                TimelineEventType.UPDATED -> "updated"
                TimelineEventType.SUPERSEDED -> "replaced"
                TimelineEventType.DELETED -> "deleted"
                TimelineEventType.REJECTED -> "rejected"
                TimelineEventType.RECONSIDERED -> "reconsidered"
            }
            Text(
                FactDates.local(e.atMs) + "  \u00b7  " + label,
                color = accent, fontSize = 10.sp,
            )
            val body = (e.newText ?: e.previousText ?: "").take(90)
            if (body.isNotBlank()) {
                Text(body, color = textPri, fontSize = 11.sp)
            }
            val source = when {
                e.hasVerifiedTime() -> "from the conversation on " + FactDates.local(e.subjectTimeMs)
                e.timeSource == TimeSource.USER_STATED && e.subjectTimeMs > 0L ->
                    "you said " + FactDates.local(e.subjectTimeMs)
                else -> "date not recorded"
            }
            Text(source, color = textSec, fontSize = 9.sp)
        }
    }
}
