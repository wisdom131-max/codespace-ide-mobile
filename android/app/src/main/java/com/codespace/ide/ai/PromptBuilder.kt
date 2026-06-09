package com.codespace.ide.ai

import com.codespace.ide.domain.AiAction

/** Builds task-specific prompts, injecting project context for grounded answers. */
object PromptBuilder {

    fun forAction(action: AiAction, code: String, context: AiContext): String {
        val lang = context.language ?: "the source"
        val instruction = when (action) {
            AiAction.EXPLAIN ->
                "Explain what the following $lang code does, step by step, concisely."
            AiAction.GENERATE ->
                "Generate $lang code for the following request. Return only code."
            AiAction.REFACTOR ->
                "Refactor the following $lang code for readability and performance " +
                    "without changing behavior. Return the refactored code and a short summary."
            AiAction.FIX ->
                "Find and fix bugs in the following $lang code. Return the corrected code " +
                    "and explain each fix briefly."
            AiAction.DOCUMENT ->
                "Add clear doc comments to the following $lang code. Return the documented code."
            AiAction.TEST ->
                "Write thorough unit tests for the following $lang code using the idiomatic " +
                    "test framework. Return only the test code."
        }
        return buildString {
            appendLine(instruction)
            if (context.retrievedChunks.isNotEmpty()) {
                appendLine("\nRelevant project context:")
                context.retrievedChunks.forEach { appendLine("```\n$it\n```") }
            }
            appendLine("\nCode:\n```\n$code\n```")
        }
    }

    fun systemPrompt(context: AiContext): String = buildString {
        appendLine("You are VN Code AI, an expert pair programmer inside a mobile IDE.")
        appendLine("Be concise. Prefer code. Respect the user's language and conventions.")
        if (context.openFiles.isNotEmpty()) {
            appendLine("Open files: ${context.openFiles.joinToString()}")
        }
    }
}
