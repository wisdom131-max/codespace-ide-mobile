package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.lsp.CallHierarchyItem
import com.codespace.ide.lsp.IncomingCall
import com.codespace.ide.lsp.OutgoingCall
import com.codespace.ide.lsp.TypeHierarchyItem

// LSP SymbolKind constants for icon rendering
private const val SYMBOL_FUNCTION = 12
private const val SYMBOL_METHOD = 6
private const val SYMBOL_CLASS = 5
private const val SYMBOL_INTERFACE = 11
private const val SYMBOL_CONSTRUCTOR = 9
private const val SYMBOL_PROPERTY = 7
private const val SYMBOL_VARIABLE = 13
private const val SYMBOL_FIELD = 8

private fun symbolKindIcon(kind: Int): String = when (kind) {
    SYMBOL_FUNCTION -> "ƒ"
    SYMBOL_METHOD -> "M"
    SYMBOL_CLASS -> "C"
    SYMBOL_INTERFACE -> "I"
    SYMBOL_CONSTRUCTOR -> "⬆"
    SYMBOL_PROPERTY -> "P"
    SYMBOL_VARIABLE -> "V"
    SYMBOL_FIELD -> "F"
    else -> "•"
}

private fun symbolKindColor(kind: Int): Color = when (kind) {
    SYMBOL_FUNCTION, SYMBOL_CONSTRUCTOR -> Color(0xFFDCDCAA) // yellow
    SYMBOL_METHOD -> Color(0xFFC586C0)    // purple
    SYMBOL_CLASS -> Color(0xFF4EC9B0)     // teal
    SYMBOL_INTERFACE -> Color(0xFFB5CEA8) // light green
    SYMBOL_PROPERTY, SYMBOL_FIELD -> Color(0xFF9CDCFE) // blue
    SYMBOL_VARIABLE -> Color(0xFF9CDCFE)
    else -> Color(0xFFD4D4D4)
}

/**
 * P41-M: Call Hierarchy Panel — shows incoming/outgoing calls as a tree.
 * Extracted as a separate composable to avoid CodeEditor method size limits.
 *
 * @param rootItem The function/method being analyzed
 * @param incomingCalls List of callers (who calls rootItem)
 * @param outgoingCalls List of callees (what rootItem calls)
 * @param onNavigate Called when user taps a call item to navigate to it
 */
@Composable
fun CallHierarchyPanel(
    rootItem: CallHierarchyItem,
    incomingCalls: List<IncomingCall>,
    outgoingCalls: List<OutgoingCall>,
    onNavigate: (uri: String, line: Int, character: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showIncoming by remember { mutableStateOf(true) }
    val calls = if (showIncoming) incomingCalls else outgoingCalls

    Column(modifier = modifier.fillMaxWidth()) {
        // Header with toggle
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Call Hierarchy",
                color = Color(0xFFD4D4D4),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Row {
                // Toggle: Incoming | Outgoing
                val incomingColor = if (showIncoming) Color(0xFF569CD6) else Color(0xFF6A6A6A)
                val outgoingColor = if (!showIncoming) Color(0xFF569CD6) else Color(0xFF6A6A6A)
                Text(
                    "← Incoming",
                    color = incomingColor,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { showIncoming = true }.padding(horizontal = 4.dp),
                )
                Text(
                    "Outgoing →",
                    color = outgoingColor,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { showIncoming = false }.padding(horizontal = 4.dp),
                )
            }
        }

        // Root item
        CallHierarchyRow(
            name = rootItem.name,
            detail = rootItem.detail,
            kind = rootItem.kind,
            isRoot = true,
            onClick = { onNavigate(rootItem.uri, rootItem.line, rootItem.character) },
        )

        // Call list
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(calls) { call ->
                when (call) {
                    is IncomingCall -> CallHierarchyRow(
                        name = call.from.name,
                        detail = call.from.detail,
                        kind = call.from.kind,
                        callCount = call.fromRanges.size,
                        onClick = { onNavigate(call.from.uri, call.from.line, call.from.character) },
                    )
                    is OutgoingCall -> CallHierarchyRow(
                        name = call.to.name,
                        detail = call.to.detail,
                        kind = call.to.kind,
                        callCount = call.fromRanges.size,
                        onClick = { onNavigate(call.to.uri, call.to.line, call.to.character) },
                    )
                }
            }
        }

        if (calls.isEmpty()) {
            Text(
                if (showIncoming) "No incoming calls found" else "No outgoing calls found",
                color = Color(0xFF6A6A6A),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun CallHierarchyRow(
    name: String,
    detail: String?,
    kind: Int,
    isRoot: Boolean = false,
    callCount: Int = 0,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Kind icon
        Text(
            symbolKindIcon(kind),
            color = symbolKindColor(kind),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(16.dp),
        )
        // Name
        Text(
            name,
            color = if (isRoot) Color(0xFFFFFFFF) else Color(0xFFD4D4D4),
            fontSize = if (isRoot) 11.sp else 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        // Detail
        if (detail != null) {
            Text(
                " $detail",
                color = Color(0xFF6A6A6A),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        // Call count badge
        if (callCount > 0) {
            Text(
                " ×$callCount",
                color = Color(0xFF6A6A6A),
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        // Root indicator
        if (isRoot) {
            Text(
                " (root)",
                color = Color(0xFFC586C0),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * P41-M: Type Hierarchy Panel — shows supertypes/subtypes as a tree.
 * Extracted as a separate composable to avoid CodeEditor method size limits.
 *
 * @param rootItem The class/interface being analyzed
 * @param supertypes List of parent types (classes/interfaces this extends)
 * @param subtypes List of child types (classes that extend this)
 * @param onNavigate Called when user taps a type to navigate to it
 */
@Composable
fun TypeHierarchyPanel(
    rootItem: TypeHierarchyItem,
    supertypes: List<TypeHierarchyItem>,
    subtypes: List<TypeHierarchyItem>,
    onNavigate: (uri: String, line: Int, character: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSupertypes by remember { mutableStateOf(true) }
    val types = if (showSupertypes) supertypes else subtypes

    Column(modifier = modifier.fillMaxWidth()) {
        // Header with toggle
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Type Hierarchy",
                color = Color(0xFFD4D4D4),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Row {
                val superColor = if (showSupertypes) Color(0xFF569CD6) else Color(0xFF6A6A6A)
                val subColor = if (!showSupertypes) Color(0xFF569CD6) else Color(0xFF6A6A6A)
                Text(
                    "↑ Supertypes",
                    color = superColor,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { showSupertypes = true }.padding(horizontal = 4.dp),
                )
                Text(
                    "↓ Subtypes",
                    color = subColor,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { showSupertypes = false }.padding(horizontal = 4.dp),
                )
            }
        }

        // Root item
        TypeHierarchyRow(
            name = rootItem.name,
            detail = rootItem.detail,
            kind = rootItem.kind,
            isRoot = true,
            onClick = { onNavigate(rootItem.uri, rootItem.line, rootItem.character) },
        )

        // Type list
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(types) { type ->
                TypeHierarchyRow(
                    name = type.name,
                    detail = type.detail,
                    kind = type.kind,
                    onClick = { onNavigate(type.uri, type.line, type.character) },
                )
            }
        }

        if (types.isEmpty()) {
            Text(
                if (showSupertypes) "No supertypes found" else "No subtypes found",
                color = Color(0xFF6A6A6A),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun TypeHierarchyRow(
    name: String,
    detail: String?,
    kind: Int,
    isRoot: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            symbolKindIcon(kind),
            color = symbolKindColor(kind),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(16.dp),
        )
        Text(
            name,
            color = if (isRoot) Color(0xFFFFFFFF) else Color(0xFFD4D4D4),
            fontSize = if (isRoot) 11.sp else 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (detail != null) {
            Text(
                " $detail",
                color = Color(0xFF6A6A6A),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (isRoot) {
            Text(
                " (root)",
                color = Color(0xFFC586C0),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
