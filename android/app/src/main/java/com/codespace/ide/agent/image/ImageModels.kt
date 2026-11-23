package com.codespace.ide.agent.image

/**
 * OWNER REPORT (2026-11-23): the app-wide settings page showed a bare text box for the Gemini
 * image model, and "I don't think it's supposed to be in that page - it's supposed to be in the key
 * settings page". They also asked which models can generate images and which cannot.
 *
 * HOW VS CODE DOES IT, and why this file exists. VS Code never lets anyone type a model id for a
 * feature. Every model carries declared capabilities
 * (src/vs/workbench/contrib/chat/common/languageModels.ts: `capabilities: { vision, toolCalling,
 * agentMode, editTools, apiType, adaptiveThinking }`) and the picker only offers models that declare
 * the one the feature needs. There is no image-generation capability in that block at all: in VS Code
 * image generation is not a chat-model setting, it is a tool the agent host runs
 * (src/vs/platform/agentHost/node/copilot/copilotGeneratedImages.ts). So the model is chosen from
 * what can actually do the job, never typed in as free text.
 *
 * This is the app's version of that contract: one list of known image models, each saying whether it
 * can generate and, when it cannot, the concrete reason. Nothing here guesses: a model is `true` only
 * if ImageGenService's actual call path can reach it, which today is Gemini's generateContent.
 */
data class ImageModel(
    val id: String,
    val label: String,
    val provider: String,
    val canGenerate: Boolean,
    /** Empty when it can generate; the honest reason when it cannot. */
    val reason: String,
)

object ImageModels {

    /** ImageGenService.DEFAULT_IMAGE_MODEL, kept in step with it. */
    const val DEFAULT = "gemini-2.5-flash-image"

    val ALL: List<ImageModel> = listOf(
        ImageModel(
            id = "gemini-2.5-flash-image",
            label = "Gemini 2.5 Flash Image",
            provider = "Google",
            canGenerate = true,
            reason = "",
        ),
        ImageModel(
            id = "gemini-2.0-flash-preview-image-generation",
            label = "Gemini 2.0 Flash (image preview)",
            provider = "Google",
            canGenerate = true,
            reason = "",
        ),
        ImageModel(
            id = "imagen-3.0-generate-002",
            label = "Imagen 3",
            provider = "Google",
            canGenerate = false,
            reason = "Imagen answers on its own predict endpoint; the app calls generateContent.",
        ),
        ImageModel(
            id = "gpt-image-1",
            label = "GPT Image 1",
            provider = "OpenAI",
            canGenerate = false,
            reason = "Needs the OpenAI images endpoint, which this app does not call yet.",
        ),
        ImageModel(
            id = "dall-e-3",
            label = "DALL-E 3",
            provider = "OpenAI",
            canGenerate = false,
            reason = "Needs the OpenAI images endpoint, which this app does not call yet.",
        ),
        ImageModel(
            id = "grok-2-image",
            label = "Grok 2 Image",
            provider = "xAI",
            canGenerate = false,
            reason = "Needs the xAI images endpoint, which this app does not call yet.",
        ),
        ImageModel(
            id = "claude-sonnet-4-5",
            label = "Claude",
            provider = "Anthropic",
            canGenerate = false,
            reason = "Claude reads images but cannot generate them.",
        ),
    )

    /** Only what can really do the job: what the picker offers. */
    val usable: List<ImageModel> = ALL.filter { it.canGenerate }

    /** Everything that cannot, with its reason: shown, greyed, never offered. */
    val unusable: List<ImageModel> = ALL.filter { !it.canGenerate }

    fun labelFor(id: String): String {
        val clean = id.trim()
        if (clean.isEmpty()) return labelFor(DEFAULT)
        return ALL.firstOrNull { it.id == clean }?.label ?: clean
    }

    fun isUsable(id: String): Boolean {
        val clean = id.trim()
        if (clean.isEmpty()) return true
        return ALL.firstOrNull { it.id == clean }?.canGenerate ?: false
    }

    /** The model the service should actually call for a stored setting. */
    fun resolve(stored: String): String {
        val clean = stored.trim()
        return if (clean.isEmpty() || !isUsable(clean)) DEFAULT else clean
    }
}
