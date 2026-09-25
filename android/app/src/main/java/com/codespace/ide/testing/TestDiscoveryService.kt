package com.codespace.ide.testing

import android.content.Context
import com.codespace.ide.domain.Language
import com.codespace.ide.editor.TestLensDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * F4 (F-TRACK TG04): project-wide test discovery. The F1 TestLensDetector is
 * the shared core — the pane's tree shows EXACTLY what the editor's lenses
 * would run, from the same detection rules and the same TestId strings.
 *
 * Candidate files (VS Code-test-explorer conventions, bounded):
 * - JVM: .kt/.java under a src/test source set OR a Test/Tests-suffixed name
 * - Python: test_*.py / *_test.py (any directory, including tests/)
 * - JS/TS: *.test.* / *.spec.* / anything under __tests__
 * - Dart: *_test.dart / anything under test/
 * Skipped: build/, .gradle/, node_modules/, .git/, rootfs/, and any file over
 * 1 MB (never parse a giant file into a lens scanner on-device).
 */
object TestDiscoveryService {

    private const val MAX_SCAN_BYTES = 1_000_000
    private val EXCLUDED_DIRS = setOf(
        "build", ".gradle", "node_modules", ".git", "rootfs", ".codespace",
        ".idea",
    )

    /** Result of one project scan. */
    data class ScanResult(val filesWithTests: Int, val testCount: Int)

    /**
     * Scans the whole project and swaps each scanned file's items in
     * [TestStore]. Runs on IO; reads and detection are pure functions.
     */
    suspend fun scanProject(context: Context, projectRoot: File?): ScanResult = withContext(Dispatchers.IO) {
        if (projectRoot == null || !projectRoot.exists()) {
            TestStore.clear()
            return@withContext ScanResult(0, 0)
        }
        // A fresh scan replaces the store whole: switching projects must never
        // leave the previous project's tree visible.
        TestStore.clear()
        val candidates = mutableListOf<File>()
        // onEnter prunes excluded directories from the walk itself —
        // node_modules/ and build/ trees are never traversed at all.
        projectRoot.walkTopDown()
            .onEnter { dir -> dir == projectRoot || dir.name !in EXCLUDED_DIRS }
            .forEach { file ->
                if (file.isFile && isCandidate(file)) candidates.add(file)
            }
        var filesWithTests = 0
        var testCount = 0
        for (file in candidates) {
            // Honest read: an unreadable file contributes no tests, never
            // fabricated ones.
            val content = try {
                // Oversized files are skipped entirely (never fed to the
                // lens scanner on-device) — null means "skip to the next".
                if (file.length() > MAX_SCAN_BYTES) null else file.readText()
            } catch (_: Exception) {
                TestStore.removeFile(file.absolutePath)
                continue
            }
            if (content == null) continue
            val language = Language.fromPath(file.absolutePath)
            if (!TestRunManager.supportsRun(language)) {
                TestStore.removeFile(file.absolutePath)
                continue
            }
            val items = detectItems(content, language, file.absolutePath)
            TestStore.replaceFile(file.absolutePath, items)
            if (items.isNotEmpty()) filesWithTests += 1
            testCount += items.size
        }
        ScanResult(filesWithTests, testCount)
    }

    /** Maps one file's content to discovered tests via the F1 detector. */
    private fun detectItems(content: String, language: Language, filePath: String): List<TestStore.DiscoveredTest> {
        val lenses: JSONArray = TestLensDetector.detectTestLenses(content, language, filePath)
        val items = mutableListOf<TestStore.DiscoveredTest>()
        for (i in 0 until lenses.length()) {
            val lens = lenses.optJSONObject(i) ?: continue
            val command = lens.optJSONObject("command") ?: continue
            val args = command.optJSONArray("arguments") ?: continue
            val testId = args.optString(0, "")
            if (testId.isBlank()) continue
            val lineIndex = args.optInt(1, -1)
            val suite = command.optString("title", "").contains("Run Tests")
            items.add(
                TestStore.DiscoveredTest(
                    testId = testId,
                    filePath = filePath,
                    lineIndex = lineIndex,
                    suite = suite,
                    language = language,
                )
            )
        }
        return items
    }

    /** Candidate test file filter (by name and source-set layout). */
    private fun isCandidate(file: File): Boolean {
        val name = file.name
        val path = file.absolutePath
        return when {
            // JVM: src/test source set OR conventional Test-suffixed files.
            (name.endsWith(".kt") || name.endsWith(".java")) ->
                path.contains("/src/test/") ||
                    (name.endsWith("Test.kt") || name.endsWith("Tests.kt") ||
                        name.endsWith("Test.java") || name.endsWith("Tests.java"))
            // Python: prefix/suffix convention anywhere.
            name.endsWith(".py") -> name.startsWith("test_") || name.endsWith("_test.py")
            // JS/TS: *.test.* / *.spec.* / __tests__ directory.
            name.endsWith(".js") || name.endsWith(".jsx") ||
                name.endsWith(".ts") || name.endsWith(".tsx") ->
                (name.contains(".test.") || name.contains(".spec.")) ||
                    path.contains("/__tests__/")
            // Dart: *_test.dart or test/ directory.
            name.endsWith(".dart") -> name.endsWith("_test.dart") || path.contains("/test/")
            else -> false
        }
    }
}
