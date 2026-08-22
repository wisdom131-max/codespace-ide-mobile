package com.codespace.ide.editor.undo

/**
 * R2-1: Diff-based undo/redo stack.
 *
 * Stores Insert/Delete/Replace actions (not full text snapshots) to minimize
 * memory. Supports batch editing (group multiple edits as one undo action)
 * and merge (consecutive similar edits within [mergeTimeLimitMs] are merged).
 *
 * Inspired by sora-editor's UndoManager (749 lines), simplified for the
 * flat-string Compose text model.
 */
class UndoRedoManager(
    private val maxStackSize: Int = 200,
    private val mergeTimeLimitMs: Long = 8000,
) {
    sealed class EditAction {
        abstract val offset: Int
        data class Insert(override val offset: Int, val text: String) : EditAction()
        data class Delete(override val offset: Int, val text: String) : EditAction()
        data class Replace(override val offset: Int, val oldText: String, val newText: String) : EditAction()
    }

    private val undoStack = ArrayDeque<EditAction>()
    private val redoStack = ArrayDeque<EditAction>()
    private var batchDepth = 0
    private val batchActions = mutableListOf<EditAction>()
    private var lastEditTime = 0L
    private var lastAction: EditAction? = null

    fun recordInsert(offset: Int, text: String) {
        recordAction(EditAction.Insert(offset, text))
    }

    fun recordDelete(offset: Int, text: String) {
        recordAction(EditAction.Delete(offset, text))
    }

    fun recordReplace(offset: Int, oldText: String, newText: String) {
        recordAction(EditAction.Replace(offset, oldText, newText))
    }

    private fun recordAction(action: EditAction) {
        if (batchDepth > 0) {
            batchActions.add(action)
            return
        }
        val now = System.currentTimeMillis()
        if (lastAction != null && now - lastEditTime < mergeTimeLimitMs) {
            val merged = tryMerge(lastAction!!, action)
            if (merged != null) {
                undoStack.removeLast()
                undoStack.addLast(merged)
                lastAction = merged
                lastEditTime = now
                return
            }
        }
        pushToUndo(action)
        lastAction = action
        lastEditTime = now
    }

    private fun tryMerge(old: EditAction, new: EditAction): EditAction? {
        return when {
            old is EditAction.Insert && new is EditAction.Insert &&
                new.offset == old.offset + old.text.length ->
                EditAction.Insert(old.offset, old.text + new.text)
            old is EditAction.Delete && new is EditAction.Delete &&
                new.offset + new.text.length == old.offset ->
                EditAction.Delete(new.offset, new.text + old.text)
            else -> null
        }
    }

    private fun pushToUndo(action: EditAction) {
        if (undoStack.size >= maxStackSize) undoStack.removeFirst()
        undoStack.addLast(action)
        redoStack.clear()
    }

    fun beginBatchEdit() { batchDepth++ }

    fun endBatchEdit() {
        if (batchDepth > 0) batchDepth--
        if (batchDepth == 0 && batchActions.isNotEmpty()) {
            val minOffset = batchActions.minOf { it.offset }
            val combined = when (batchActions.first()) {
                is EditAction.Insert -> {
                    val total = batchActions.filterIsInstance<EditAction.Insert>().joinToString("") { it.text }
                    EditAction.Insert(minOffset, total)
                }
                is EditAction.Delete -> {
                    val total = batchActions.filterIsInstance<EditAction.Delete>().joinToString("") { it.text }
                    EditAction.Delete(minOffset, total)
                }
                is EditAction.Replace -> {
                    val oldTotal = batchActions.filterIsInstance<EditAction.Replace>().joinToString("") { it.oldText }
                    val newTotal = batchActions.filterIsInstance<EditAction.Replace>().joinToString("") { it.newText }
                    EditAction.Replace(minOffset, oldTotal, newTotal)
                }
            }
            pushToUndo(combined)
            lastAction = combined
            lastEditTime = System.currentTimeMillis()
            batchActions.clear()
        }
    }

    fun undo(currentText: String): Pair<String, Int>? {
        val action = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(action)
        return applyReverse(currentText, action)
    }

    fun redo(currentText: String): Pair<String, Int>? {
        val action = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(action)
        return applyForward(currentText, action)
    }

    private fun applyForward(text: String, action: EditAction): Pair<String, Int> = when (action) {
        is EditAction.Insert -> {
            val n = text.substring(0, action.offset) + action.text + text.substring(action.offset)
            n to action.offset + action.text.length
        }
        is EditAction.Delete -> {
            val n = text.substring(0, action.offset) + text.substring(action.offset + action.text.length)
            n to action.offset
        }
        is EditAction.Replace -> {
            val n = text.substring(0, action.offset) + action.newText + text.substring(action.offset + action.oldText.length)
            n to action.offset + action.newText.length
        }
    }

    private fun applyReverse(text: String, action: EditAction): Pair<String, Int> = when (action) {
        is EditAction.Insert -> {
            val n = text.substring(0, action.offset) + text.substring(action.offset + action.text.length)
            n to action.offset
        }
        is EditAction.Delete -> {
            val n = text.substring(0, action.offset) + action.text + text.substring(action.offset)
            n to action.offset + action.text.length
        }
        is EditAction.Replace -> {
            val n = text.substring(0, action.offset) + action.oldText + text.substring(action.offset + action.newText.length)
            n to action.offset + action.oldText.length
        }
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()
    fun clear() { undoStack.clear(); redoStack.clear(); batchActions.clear(); batchDepth = 0 }
}
