package com.codespace.ide.terminal

import android.content.Context
import java.io.File

class OllamaSetup(private val context: Context) {
    fun installProfile(): String {
        val home = File(context.filesDir, "home").apply { mkdirs() }
        val script = File(home, ".ollama-nemotron.sh")
        script.writeText(buildProfile())
        script.setReadable(true, false)

        val bashrc = File(home, ".bashrc")
        val existing = if (bashrc.exists()) bashrc.readText() else ""
        if (!existing.contains(".ollama-nemotron.sh")) {
            bashrc.writeText(existing + "\nif [ -f ~/.ollama-nemotron.sh ]; then . ~/.ollama-nemotron.sh; fi\n")
        }
        return script.absolutePath
    }

    fun buildProfile(): String = buildString {
        appendLine("# VN Code Ollama + Nemotron profile")
        appendLine("export OLLAMA_HOST=0.0.0.0:11434")
        appendLine("export OLLAMA_MODELS=\$HOME/.ollama/models")
        appendLine("export OLLAMA_KEEP_ALIVE=30m")
        appendLine("export OLLAMA_NUM_PARALLEL=1")
        appendLine("alias ollama-serve='ollama serve'")
        appendLine("alias ollama-list='ollama list'")
        appendLine("alias ollama-pull='ollama pull'")
        appendLine("alias ollama-nemotron='ollama run nemotron'")
        appendLine("alias ollama-nemotron-chat='ollama run nemotron'")
        appendLine("alias ollama-status='ollama ps && ollama list'")
        appendLine("alias ollama-cloud='echo \"Use this profile for cloud-backed Nemotron workflows\"'")
        appendLine("ollama-nemotron-help() {")
        appendLine("  echo 'Useful commands:'")
        appendLine("  echo '  ollama serve'")
        appendLine("  echo '  ollama pull nemotron'")
        appendLine("  echo '  ollama run nemotron'")
        appendLine("  echo '  ollama ps'")
        appendLine("}")
    }
}
