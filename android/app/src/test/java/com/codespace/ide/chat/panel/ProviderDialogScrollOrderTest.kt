package com.codespace.ide.chat.panel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * U1 (build 657 finding b) — the provider/model dialog must SCROLL, and must keep
 * scrolling after every future edit.
 *
 * Why a source contract instead of a Compose test: this project has no Compose UI
 * test harness, and the failure this guards is a MODIFIER ORDER bug, not a
 * behaviour bug — `heightIn(max)` placed AFTER `verticalScroll` caps the CONTENT,
 * so the scroll range becomes zero and the dialog silently stops scrolling while
 * still rendering perfectly. Nothing in the rendered output betrays it, which is
 * why it shipped once already. The contract is therefore asserted against the
 * source: deterministic, no device, and it fails the moment the order is changed.
 *
 * Scope note: these are static contracts over one file, not behavioural tests.
 * The behavioural half of finding (b) — "the list scrolls to the END at the
 * narrowest width" — is a device check (sheet item F) because it depends on real
 * font metrics and the panel's measured width on the phone.
 */
class ProviderDialogScrollOrderTest {

    private fun source(): String {
        val candidates = listOf(
            File("src/main/java/com/codespace/ide/chat/panel/ChatPanelScreen.kt"),
            File("app/src/main/java/com/codespace/ide/chat/panel/ChatPanelScreen.kt"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError(
                "ChatPanelScreen.kt not found from " + File(".").absolutePath +
                    " (looked in " + candidates.joinToString { it.path } + ")"
            )
        return f.readText()
    }

    @Test
    fun theHeightInCapIsImmediatelyFollowedByVerticalScroll() {
        val src = source()
        val cap = src.indexOf("heightIn(max =")
        assertTrue("the dialog lost its heightIn cap — restore it, the dialog must not grow past the screen", cap >= 0)

        // Walk the modifier chain that starts at the cap: the very next modifier
        // must be verticalScroll. Anything else means the cap now wraps the scroll
        // (correct) — unless verticalScroll is missing entirely, which is the other
        // way this dialog has broken before.
        val chain = src.substring(cap, (cap + 200).coerceAtMost(src.length))
        val scrollAt = chain.indexOf("verticalScroll(")
        assertTrue(
            "no verticalScroll( in the same modifier chain as heightIn(max = ...): the list cannot scroll. chain was:\n" +
                chain.take(160),
            scrollAt >= 0,
        )
        assertTrue(
            "verticalScroll( must be chained AFTER heightIn(max = ...) — found it " + scrollAt +
                " chars downstream, expected it immediately after the cap. chain was:\n" + chain.take(160),
            scrollAt < 120,
        )
    }

    @Test
    fun bothRowSourcesRenderInsideTheScrollContainer() {
        val src = source()
        val scroll = src.indexOf("verticalScroll(")
        assertTrue("no verticalScroll( at all — the dialog cannot scroll", scroll > 0)

        val native = src.indexOf("nativeRows.forEach")
        val presets = src.indexOf("ProviderPresets.ALL.forEach")
        assertTrue("the stored-key slot rows are gone — a saved provider would be unselectable", native > 0)
        assertTrue("the provider preset rows are gone", presets > 0)
        assertTrue("stored-key slot rows must be emitted inside the scroll container", native > scroll)
        assertTrue("provider preset rows must be emitted inside the scroll container", presets > scroll)
    }

    @Test
    fun thereIsExactlyOneScrollContainerInTheDialog() {
        // Two nested verticalScrolls make the inner one unreachable on a phone, and
        // a LazyColumn inside a verticalScroll throws at measure time (infinite
        // height). One container, plain Column children, no exceptions.
        val src = source()
        val dialogStart = src.indexOf("private fun ProviderModelDialog(")
        assertTrue("ProviderModelDialog is gone — re-point this test, do not delete it", dialogStart >= 0)
        val tail = src.substring(dialogStart)
        val scrolls = Regex("verticalScroll\\(").findAll(tail).count()
        assertTrue("expected exactly ONE scroll container in the dialog, found $scrolls", scrolls == 1)
        assertTrue(
            "LazyColumn inside the dialog's verticalScroll throws at measure time — keep plain Column children",
            !tail.contains("LazyColumn"),
        )
    }
}
