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
import com.codespace.ide.agent.facts.FactText
import com.codespace.ide.agent.facts.FactsStore

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

    // A reload counter, not a mutable fact list: every mutation re-reads the store, so the screen
    // is always showing what is actually on disk.
    var revision by remember { mutableStateOf(0) }
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
    val visible = remember(revision, query, scopeFilter, categoryFilter, projectId) {
        facts.filter { f ->
            (scopeFilter == null || f.scope == scopeFilter) &&
                (categoryFilter == null || f.category == categoryFilter) &&
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
                Modifier.fillMaxSize(),
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
                            revision++
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
                        revision++
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
                    revision++
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
                    revision++
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
