package com.codespace.ide.chat.panel

import com.codespace.ide.engine.EngineConfig
import com.codespace.ide.engine.OpenAIAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** U4 model picker: structure from the reference, plus the two honesty rules. */
class ModelPickerSpecTest {

    private fun m(
        id: String,
        provider: String = "gemini",
        reasoning: Boolean = false,
        price: String? = null,
        other: Boolean = false,
        name: String = id,
    ) = PickerModel(provider, id, name, reasoning, 1_000_000, price, other)

    private val gemini = m("gemini-3.8-flash", reasoning = true)
    private val llama = m("llama-3.3-70b", provider = "ollama", reasoning = false)
    private val other = m("grok-4.7", provider = "xai", reasoning = true, other = true)

    private fun build(
        models: List<PickerModel> = listOf(gemini, llama, other),
        selected: Pair<String, String>? = "gemini" to "gemini-3.8-flash",
        effort: Effort = Effort.MEDIUM,
        query: String = "",
        otherExpanded: Boolean = false,
        autoNote: String? = null,
    ) = ModelPickerSpec.build(models, selected, effort, query, otherExpanded, autoNote)

    // ---- structure from the reference ----

    @Test
    fun theSelectedModelIsTheOnlyCheckedRow() {
        val rows = build().rows.filterIsInstance<PickerRow.Model>()
        assertEquals(1, rows.count { it.selected })
        assertEquals("gemini-3.8-flash", rows.first { it.selected }.model.modelId)
    }

    @Test
    fun theGearIsAlwaysOfferedAndRoutesToSettings() {
        assertTrue(build().showsManageGear)
        assertTrue(build(query = "zzz").showsManageGear)
    }

    @Test
    fun otherModelsIsCollapsedByDefaultAndHidesItsRows() {
        val rows = build().rows
        val header = rows.filterIsInstance<PickerRow.OtherHeader>().single()
        assertFalse(header.expanded)
        assertEquals(1, header.count)
        assertFalse(rows.filterIsInstance<PickerRow.Model>().any { it.model.isOther })
    }

    @Test
    fun expandingOtherModelsShowsThemAfterTheHeader() {
        val rows = build(otherExpanded = true).rows
        val hi = rows.indexOfFirst { it is PickerRow.OtherHeader }
        val grok = rows.indexOfFirst { it is PickerRow.Model && (it as PickerRow.Model).model.isOther }
        assertTrue(grok > hi)
    }

    @Test
    fun aSearchOpensTheOtherGroupSoAHitIsNeverHidden() {
        val state = build(query = "grok")
        val models = state.rows.filterIsInstance<PickerRow.Model>()
        assertEquals(listOf("grok-4.7"), models.map { it.model.modelId })
        assertTrue(state.rows.filterIsInstance<PickerRow.OtherHeader>().single().expanded)
    }

    @Test
    fun searchMatchesNameIdAndProviderCaseInsensitively() {
        assertEquals(1, build(query = "GEMINI").rows.filterIsInstance<PickerRow.Model>().size)
        assertEquals(1, build(query = "ollama").rows.filterIsInstance<PickerRow.Model>().size)
        assertEquals(1, build(query = "70B").rows.filterIsInstance<PickerRow.Model>().size)
    }

    @Test
    fun anEmptySearchResultSaysSoInsteadOfShowingNothing() {
        val s = build(query = "nonexistent")
        assertTrue(s.rows.isEmpty())
        assertEquals("No models match \"nonexistent\"", s.emptyMessage)
        assertNull(build().emptyMessage)
    }

    // ---- honesty rule 1: no invented cost ----

    @Test
    fun noPriceMeansNoCostTextAnywhereEver() {
        val s = build(otherExpanded = true)
        for (row in s.rows.filterIsInstance<PickerRow.Model>()) {
            assertFalse("no multiplier invented: '${row.summary}'", row.summary.contains("x"))
            assertFalse(row.summary.contains("%"))
            assertFalse(row.summary.contains("$"))
        }
    }

    @Test
    fun aSuppliedPriceIsShownVerbatimAndOnlyThen() {
        val priced = m("gemini-3.8-flash", reasoning = true, price = "\$0.30 / M in")
        val row = build(models = listOf(priced)).rows.filterIsInstance<PickerRow.Model>().single()
        assertEquals("Medium \u00B7 \$0.30 / M in", row.summary)
        val noPrice = build(models = listOf(m("x", price = "  "))).rows.filterIsInstance<PickerRow.Model>().single()
        assertEquals("", noPrice.summary)
    }

    @Test
    fun theAutoRowDoesNotExistUntilAutoRoutingDoes() {
        assertTrue(build().rows.none { it is PickerRow.Auto })
        val withAuto = build(autoNote = "Picks a model for you")
        assertTrue(withAuto.rows.first() is PickerRow.Auto)
        // ...and it is hidden while searching so it never pollutes results.
        assertTrue(build(query = "gem", autoNote = "x").rows.none { it is PickerRow.Auto })
    }

    // ---- honesty rule 2: effort only where reasoning is real ----

    @Test
    fun effortMenuExistsOnlyForReasoningModels() {
        assertTrue(ModelPickerSpec.effortMenu(llama, Effort.MEDIUM).isEmpty())
        val menu = ModelPickerSpec.effortMenu(gemini, Effort.HIGH)
        assertEquals(listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH), menu.map { it.first })
        assertEquals(listOf(false, false, true), menu.map { it.second })
        val rows = build().rows.filterIsInstance<PickerRow.Model>()
        assertTrue(rows.first { it.model.modelId == gemini.modelId }.hasEffortMenu)
        assertFalse(rows.first { it.model.modelId == llama.modelId }.hasEffortMenu)
    }

    @Test
    fun effortLabelsAndDescriptionsMatchTheReferenceWording() {
        assertEquals("Low", Effort.LOW.label)
        assertEquals("Medium (default)", Effort.MEDIUM.label)
        assertEquals("High", Effort.HIGH.label)
        assertEquals("Faster responses with less reasoning", Effort.LOW.description)
        assertEquals("Balanced reasoning and speed", Effort.MEDIUM.description)
        assertEquals("Maximum reasoning depth", Effort.HIGH.description)
        assertEquals(Effort.MEDIUM, Effort.DEFAULT)
    }

    @Test
    fun onlyTheSelectedModelShowsTheChosenEffort() {
        val rows = build(effort = Effort.HIGH, otherExpanded = true).rows.filterIsInstance<PickerRow.Model>()
        assertEquals("High", rows.first { it.selected }.summary)
        assertEquals("Medium", rows.first { it.model.modelId == "grok-4.7" }.summary)
    }

    // ---- the effort values are the REAL wire values, not decoration ----

    @Test
    fun everyEffortBudgetProducesTheMatchingReasoningEffortInTheRealRequest() {
        // reasoningEffort() is internal to the engine module, so go through the PUBLIC
        // path instead: build the actual serialized request and read the field off it.
        // That also proves the value really reaches the wire, not just a helper.
        val adapter = OpenAIAdapter()
        val expectedWire = mapOf(Effort.LOW to "low", Effort.MEDIUM to "medium", Effort.HIGH to "high")
        for (e in Effort.menuOrder) {
            assertEquals("budget->effort inverse for $e", e, Effort.fromBudget(e.budgetTokens))
            val cfg = EngineConfig(
                modelId = "m", providerId = "openai", baseUrl = "http://x",
                apiKey = "k", reasoningBudget = e.budgetTokens,
            )
            val body = adapter.payload(emptyList(), "hi", cfg)
            assertTrue(
                "$e must put reasoning_effort=${expectedWire[e]} on the wire, got: $body",
                body.contains("\"reasoning_effort\":\"" + expectedWire[e] + "\""),
            )
        }
    }

    @Test
    fun aNonEffortProviderNeverGetsReasoningEffortEvenAtHighEffort() {
        // deepseek/groq/mistral/ollama are kind none: sending the field is a 400 risk.
        val cfg = EngineConfig(
            modelId = "m", providerId = "ollama", baseUrl = "http://x",
            apiKey = "", reasoningBudget = Effort.HIGH.budgetTokens,
        )
        assertFalse(OpenAIAdapter().payload(emptyList(), "hi", cfg).contains("reasoning_effort"))
    }

    @Test
    fun anthropicBudgetFloorIsRespectedByEveryEffort() {
        // Anthropic needs budget_tokens >= 1024; LOW is exactly the floor.
        assertTrue(Effort.menuOrder.all { it.budgetTokens >= 1024 })
        assertNotNull(Effort.LOW.budgetTokens)
    }

    // ---- toolbar chip text ----

    @Test
    fun chipShowsEffortOnlyWhenThereIsRoomAndTheModelReasons() {
        assertEquals("gemini-3.8-flash \u00B7 Medium", ModelPickerSpec.chipText(gemini, Effort.MEDIUM, true))
        assertEquals("gemini-3.8-flash", ModelPickerSpec.chipText(gemini, Effort.MEDIUM, false))
        assertEquals("llama-3.3-70b", ModelPickerSpec.chipText(llama, Effort.MEDIUM, true))
        assertEquals("Pick model", ModelPickerSpec.chipText(null, Effort.MEDIUM, true))
    }
}
