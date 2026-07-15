package com.codespace.ide.terminal

import android.content.Context
import com.codespace.ide.agent.AgentApiServer
import java.io.File

/**
 * McpShellProfile — sets up the terminal environment so ANY AI launched
 * in the terminal (Claude Code, Ollama CLI, llama.cpp, etc.) has full
 * agent capabilities via the local AgentApiServer.
 *
 * What gets injected into ~/.bashrc:
 *   AGENT_API_URL  — http://localhost:8765 (local AgentApiServer)
 *   agent()        — call any of the 32 tools from the terminal
 *   agent_tools    — list all available tools
 *   agent_prompt   — get the system prompt for CLI AI tools
 *   Shorthands: agent_read, agent_write, agent_run, agent_git, agent_search, etc.
 *
 * Usage in terminal:
 *   agent_read src/main.kt
 *   agent_run "git status"
 *   agent_git commit_push "fix: update UI"
 *   agent_tools  # list all 32 tools
 *   agent_prompt  # get system prompt for CLI AI
 *
 * Also starts AgentApiServer if not already running.
 */
object McpShellProfile {

    private const val API_PORT = 8765
    private const val API_URL = "http://localhost:$API_PORT"

    fun install(context: Context, backendUrl: String? = null, authToken: String? = null) {
        // Start the local Agent API server (gives terminal AI full 32-tool access)
        AgentApiServer.start(context)

        // The ubuntu terminal's HOME is /root INSIDE proot, which maps to
        // context.filesDir/ubuntu-rootfs/root/ on the host.  The old code wrote to
        // context.filesDir/home/ — a completely different directory the shell never reads.
        val rootfsDir = ProotInstaller.rootfsDir(context)
        val home = File(rootfsDir, "root").apply { mkdirs() }

        val script = File(home, ".agent-profile.sh")
        script.writeText(buildProfile())
        script.setReadable(true, false)

        val bashrc = File(home, ".bashrc")
        val existing = if (bashrc.exists()) bashrc.readText() else ""
        if (!existing.contains(".agent-profile.sh")) {
            bashrc.appendText("\nif [ -f ~/.agent-profile.sh ]; then . ~/.agent-profile.sh; fi\n")
        }

        // Write agent.json config for MCP-compatible clients
        val agentConfig = File(home, ".agent.json")
        agentConfig.writeText(buildAgentJson())

        // Write system prompt file for CLI AI tools
        val promptFile = File(home, ".agent-system-prompt.md")
        promptFile.writeText(buildSystemPrompt())

        // Write the `agent` bin script to the correct rootfs usr/local/bin.
        // Old path was "ubuntu/usr/local/bin" but rootfs lives at "ubuntu-rootfs/".
        val binDir = File(rootfsDir, "usr/local/bin")
        if (binDir.exists()) {
            val agentScript = File(binDir, "agent")
            agentScript.writeText(buildBinScript())
            agentScript.setExecutable(true, false)
        }
    }

    fun stop() {
        AgentApiServer.stop()
    }

    private fun buildProfile(): String = buildString {
        appendLine("# CodeSpace Agent Profile — auto-generated")
        appendLine("# Gives ANY terminal AI full access to 32 agent tools via local API")
        appendLine("export AGENT_API_URL='$API_URL'")
        appendLine("")
        appendLine("# ── Core tool caller ──────────────────────────────────────────────")
        appendLine("# Usage: agent <tool_name> '<json_args>'")
        appendLine("# Example: agent run_command '{\"command\":\"ls -la\"}'")
        appendLine("agent() {")
        appendLine("  local tool=\"\$1\"; shift")
        appendLine("  local args=\"\${1:-{}}\"")
        appendLine("  local resp")
        appendLine("  resp=\$(curl -s -X POST \"\$AGENT_API_URL/tool/\$tool\" \\")
        appendLine("    -H 'Content-Type: application/json' \\")
        appendLine("    -d \"\$args\" 2>/dev/null)")
        appendLine("  if [ \$? -ne 0 ]; then")
        appendLine("    echo '[agent] API server not reachable. Is the terminal session active?'")
        appendLine("    return 1")
        appendLine("  fi")
        appendLine("  echo \"\$resp\" | python3 -c \"import sys,json; d=json.load(sys.stdin); print(d.get('result','[no result]'))\" 2>/dev/null || echo \"\$resp\"")
        appendLine("}")
        appendLine("")
        appendLine("# ── List all available tools ──────────────────────────────────────")
        appendLine("agent_tools() {")
        appendLine("  curl -s \"\$AGENT_API_URL/tools\" 2>/dev/null | python3 -c \"")
        appendLine("import sys,json")
        appendLine("d=json.load(sys.stdin)")
        appendLine("for t in d.get('tools',[]):")
        appendLine("    print(f'  {t}')")
        appendLine("print('Total: '+str(d.get('count',0))+' tools available')\" 2>/dev/null || echo '[agent] API not reachable'")
        appendLine("}")
        appendLine("")
        appendLine("# ── Get system prompt for CLI AI tools ────────────────────────────")
        appendLine("agent_prompt() {")
        appendLine("  curl -s \"\$AGENT_API_URL/system-prompt\" 2>/dev/null | python3 -c \"")
        appendLine("import sys,json; print(json.load(sys.stdin).get('prompt',''))\" 2>/dev/null")
        appendLine("}")
        appendLine("")
        appendLine("# ── Save terminal AI session to Copilot chat history ─────────────")
        appendLine("# Usage: agent_session_save 'Session title' 'Session summary or content'")
        appendLine("# This pushes a record into the Copilot Chat sessions sidebar so you can")
        appendLine("# review terminal AI conversations alongside your Copilot sessions.")
        appendLine("agent_session_save() {")
        appendLine("  local _title=\"\${1:-Terminal session}\"")
        appendLine("  local _content=\"\${2:-}\"")
        appendLine("""  agent save_terminal_session "{\"title\":\"${'$'}_title\",\"content\":\"${'$'}_content\",\"mode\":\"TERMINAL\"}" """.trimEnd())
        appendLine("}")
        appendLine("")
        appendLine("# ── Shell & File shorthands ───────────────────────────────────────")
        appendLine("agent_read()   { agent read_file '{\"path\":\"'\$1'\"}'; }")
        appendLine("agent_write()  { agent write_file '{\"path\":\"'\$1'\",\"content\":\"'\$2'\"}'; }")
        appendLine("agent_ls()     { agent list_files '{\"path\":\"'\${1:-.}'\"}'; }")
        appendLine("agent_search() { agent search_files '{\"path\":\"'\${2:-.}'\",\"pattern\":\"'\$1'\"}'; }")
        appendLine("agent_run()    { agent run_command '{\"command\":\"'\$1'\"}'; }")
        appendLine("")
        appendLine("# ── Git shorthands ────────────────────────────────────────────────")
        appendLine("agent_git()    {")
        appendLine("  local action=\"\$1\"; shift")
        appendLine("  case \"\$action\" in")
        appendLine("    status)       agent git_status '{}' ;;")
        appendLine("    diff)         agent git_diff '{\"staged\":'\${2:-false}'}' ;;")
        appendLine("    push)         agent git_commit_push '{\"message\":\"'\${2:-auto-update}'\"}' ;;")
        appendLine("    pull)         agent git_pull_rebase '{}' ;;")
        appendLine("    branch)       agent git_branch '{\"action\":\"'\${2:-list}'\"}' ;;")
        appendLine("    *)            echo 'Usage: agent_git [status|diff|push|pull|branch] ...' ;;")
        appendLine("  esac")
        appendLine("}")
        appendLine("")
        appendLine("# ── Secret shorthands ─────────────────────────────────────────────")
        appendLine("agent_secret_save()   { agent save_secret '{\"key\":\"'\$1'\",\"value\":\"'\$2'\"}'; }")
        appendLine("agent_secret_get()    { agent get_secret '{\"key\":\"'\$1'\"}'; }")
        appendLine("agent_secret_scan()   { agent detect_secrets '{\"'\$([ -f \"\$1\" ] && echo path || echo text)':'\"'\$1'\"'}'; }")
        appendLine("")
        appendLine("# ── Memory shorthands ─────────────────────────────────────────────")
        appendLine("agent_mem_save()   { agent save_memory '{\"key\":\"'\$1'\",\"value\":\"'\$2'\"}'; }")
        appendLine("agent_mem_read()   { agent read_memory '{}'; }")
        appendLine("agent_mem_del()    { agent delete_memory '{\"key\":\"'\$1'\"}'; }")
        appendLine("")
        appendLine("# ── Web shorthands ────────────────────────────────────────────────")
        appendLine("agent_fetch()      { agent web_fetch '{\"url\":\"'\$1'\"}'; }")
        appendLine("agent_search_web() { agent web_search '{\"query\":\"'\$1'\"}'; }")
        appendLine("")
        appendLine("# ── Entity/Data shorthands ────────────────────────────────────────")
        appendLine("agent_data_create() { agent create_entity '{\"entity\":\"'\$1'\",\"data\":\"'\$2'\"}'; }")
        appendLine("agent_data_read()   { agent read_entities '{\"entity\":\"'\$1'\"}'; }")
        appendLine("agent_data_update() { agent update_entity '{\"entity\":\"'\$1'\",\"filter\":\"'\$2'\",\"data\":\"'\$3'\"}'; }")
        appendLine("agent_data_delete() { agent delete_entity '{\"entity\":\"'\$1'\",\"filter\":\"'\$2'\"}'; }")
        appendLine("")
        appendLine("# ── Scheduler shorthands ──────────────────────────────────────────")
        appendLine("agent_sched()      { agent schedule_task '{\"name\":\"'\$1'\",\"cron\":\"'\$2'\",\"command\":\"'\$3'\"}'; }")
        appendLine("agent_sched_list() { agent list_tasks '{}'; }")
        appendLine("agent_sched_cancel() { agent cancel_task '{\"name\":\"'\$1'\"}'; }")
        appendLine("")
        appendLine("# ── Connector shorthands ──────────────────────────────────────────")
        appendLine("agent_connectors() { agent list_connectors '{}'; }")
        appendLine("agent_connect()    { agent connect_service '{\"service\":\"'\$1'\"}'; }")
        appendLine("")
        appendLine("# ── Media shorthands ──────────────────────────────────────────────")
        appendLine("agent_image()      { agent generate_image '{\"prompt\":\"'\$1'\",\"output\":\"'\$2'\"}'; }")
        appendLine("agent_install()    { agent install_package '{\"manager\":\"'\$1'\",\"package\":\"'\$2'\"}'; }")
        appendLine("")
        appendLine("# ── Remotion ──────────────────────────────────────────────────────")
        appendLine("agent_render()     { agent render_remotion '{\"composition\":\"'\$1'\",\"output\":\"'\$2'\"}'; }")
        appendLine("")
        appendLine("# ── Health check ──────────────────────────────────────────────────")
        appendLine("agent_health() { curl -s \"\$AGENT_API_URL/health\" 2>/dev/null || echo '[agent] API not running'; }")
        appendLine("")
        appendLine("echo '[Agent] 32 tools ready. Type agent_tools to list, agent <tool> \"<json>\" to call.'")
        appendLine("[ -n \"${'\$'}{WORKSPACE_PATH}\" ] && echo \"[Agent] Project files: ${'\$'}{WORKSPACE_PATH}\" || echo \"[Agent] Tip: open a project in Explorer to set WORKSPACE_PATH\"")
        appendLine("echo '[Agent] Shorthands: agent_read, agent_write, agent_run, agent_git, agent_search, agent_mem_*, agent_fetch...'")
    }

    private fun buildAgentJson(): String =
        """{
  "agentApiUrl": "$API_URL",
  "tools": [
    "run_command","read_file","write_file","list_files","search_files",
    "git_commit_push","git_pull_rebase","git_branch","git_status","git_diff",
    "render_remotion","save_secret","get_secret","detect_secrets",
    "web_fetch","web_search","save_memory","read_memory","delete_memory",
    "list_connectors","connect_service","use_connector",
    "create_entity","read_entities","update_entity","delete_entity",
    "schedule_task","list_tasks","cancel_task",
    "generate_image","upload_file","install_package"
  ],
  "usage": "POST /tool/{name} with JSON body containing tool arguments"
}"""

    private fun buildSystemPrompt(): String = """
# CodeSpace IDE — Agent System Prompt for Terminal AI

You are an AI agent running inside CodeSpace IDE's terminal. You have full
access to the user's environment through 32 agent tools.

## WHERE THE FILES ARE

The user's project files are at: ${'$'}{WORKSPACE_PATH:-/sdcard}
- ${'$'}WORKSPACE_PATH  — the currently open project folder (set automatically)
- ${'$'}PROJECT_FILES   — same as WORKSPACE_PATH (alias)
- /sdcard            — the phone's internal storage (/storage/emulated/0)
- /root              — the proot home directory

ALWAYS start by checking ${'$'}WORKSPACE_PATH:
  agent_run "ls \"${'$'}{WORKSPACE_PATH:-/sdcard}\""
  agent_ls "${'$'}{WORKSPACE_PATH:-/sdcard}"

Do NOT waste time guessing paths like /home/user, /workspace, or /project.

## How to Call Tools

Use the `agent` shell function:
  agent <tool_name> '<json_arguments>'

Or use shorthands:
  agent_read <path>
  agent_write <path> <content>
  agent_run "<command>"
  agent_git status|diff|push|pull|branch
  agent_search <pattern> [dir]
  agent_mem_save <key> <value>
  agent_fetch <url>
  agent_search_web "<query>"

## Available Tools (32)

Shell: run_command, read_file, write_file, list_files, search_files
Git: git_commit_push, git_pull_rebase, git_branch, git_status, git_diff
Video: render_remotion
Secrets: save_secret, get_secret, detect_secrets
Web: web_fetch, web_search
Memory: save_memory, read_memory, delete_memory
Connectors: list_connectors, connect_service, use_connector
Data: create_entity, read_entities, update_entity, delete_entity
Scheduler: schedule_task, list_tasks, cancel_task
Media: generate_image, upload_file
Packages: install_package

## Example Session

$ agent_run "ls -la"
$ agent_read src/main.kt
$ agent_git status
$ agent_git push "fix: update UI"
$ agent_search "TODO" src/
$ agent_mem_save "decision" "Use clip rendering for Remotion"

## API Endpoints

POST /tool/{name}  — Execute a tool
GET  /tools        — List all tools
GET  /system-prompt — Get this prompt
GET  /health       — Health check

Server: http://localhost:$API_PORT
""".trim()

    private fun buildBinScript(): String = buildString {
        appendLine("#!/bin/bash")
        appendLine("# agent - CLI wrapper for CodeSpace AgentApiServer")
        appendLine("AGENT_API_URL=\${AGENT_API_URL:-http://localhost:$API_PORT}")
        appendLine("")
        appendLine("if [ -z \"\$1\" ]; then")
        appendLine("  echo 'Usage: agent <tool_name> [json_args]'")
        appendLine("  echo 'Run: agent_tools  to list available tools'")
        appendLine("  exit 1")
        appendLine("fi")
        appendLine("")
        appendLine("tool=\$1; shift")
        appendLine("args=\${1:-{}}")
        appendLine("")
        appendLine("resp=\$(curl -s -X POST \$AGENT_API_URL/tool/\$tool -H 'Content-Type: application/json' -d \$args 2>/dev/null)")
        appendLine("")
        appendLine("if [ \$? -ne 0 ]; then")
        appendLine("  echo '[agent] API server not reachable at '\$AGENT_API_URL")
        appendLine("  echo 'Make sure the terminal session is active in CodeSpace IDE.'")
        appendLine("  exit 1")
        appendLine("fi")
        appendLine("")
        appendLine("echo \$resp | head -5")
    }

}
