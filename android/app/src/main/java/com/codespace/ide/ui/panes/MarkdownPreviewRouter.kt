package com.codespace.ide.ui.panes

import com.codespace.ide.editor.FeatureToggleStore
import java.io.File

/**
 * MarkdownPreviewRouter — VS Code-parity routing for Markdown files.
 *
 * DESIGN CONTRACT (confirmed with Wisdom 2026-09-07):
 * A .md file ALWAYS opens as a normal, fully editable editor tab — identical to
 * every other file type (typing, undo, save all work; the editor chain is
 * type-agnostic). The auto-open behavior is strictly ADDITIVE: after the
 * editable tab exists, it flips the bottom panel to the PREVIEW tab so the
 * rendered view shows alongside. It never replaces or locks the editor.
 *
 * VS Code reference (verified from microsoft/vscode main, extensions/
 * markdown-language-features/src/preview/preview.ts): the preview is a separate
 * webview panel rendering the SAME document; the editor stays untouched.
 *
 * openReadme() algorithm (VS Code workbench startupPage.ts, verified earlier):
 * root children sorted; prefer exact "readme.md" (case-insensitive), else the
 * first file whose name starts with "readme". Markdown readmes open the preview
 * (markdown.showPreview parity); non-.md readmes open the plain editor.
 */
object MarkdownPreviewRouter {

    fun isMarkdown(path: String): Boolean =
        path.endsWith(".md") || path.endsWith(".markdown")

    /** Setting: auto-open the Preview tab when a .md file is opened. Default ON. */
    fun autoPreviewEnabled(): Boolean = FeatureToggleStore.get("md_preview_auto")

    /**
     * True if the caller should flip the bottom panel to PREVIEW after opening
     * [path] in its editor tab. Call AFTER the tab exists — never instead of it.
     */
    fun shouldShowPreviewTab(path: String): Boolean =
        autoPreviewEnabled() && isMarkdown(path)

    /**
     * VS Code openReadme(): pick the readme file among [rootDir]'s direct children.
     * Exact "readme.md"/"readme.markdown" (case-insensitive) wins; otherwise the
     * first file (alphabetical) whose name starts with "readme".
     * Returns the absolute path, or null if the project root has no readme.
     */
    fun findReadmeAtRoot(rootDir: String): String? {
        val root = File(rootDir)
        val children = root.listFiles()?.filter { it.isFile } ?: return null
        if (children.isEmpty()) return null
        val sorted = children.sortedBy { it.name.lowercase() }
        // Exact match first (VS Code: pick exact readme.md, case-insensitive)
        sorted.firstOrNull { it.name.lowercase() == "readme.md" }?.let { return it.absolutePath }
        sorted.firstOrNull { it.name.lowercase() == "readme.markdown" }?.let { return it.absolutePath }
        // Then first file starting with "readme" (e.g. README.txt, Readme.markdown)
        sorted.firstOrNull { it.name.lowercase().startsWith("readme") }?.let { return it.absolutePath }
        return null
    }
}
