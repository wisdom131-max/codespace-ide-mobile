package com.codespace.ide.terminal

import android.content.Context
import java.io.File

/**
 * Writes MCP (Model Context Protocol) environment variables and helper scripts
 * into the Ubuntu/offline shell profile so ANY AI running in the terminal
 * (Ollama, llama.cpp, Claude via curl, etc.) can call the backend MCP tools.
 *
 * What gets injected into ~/.bashrc:
 *   MCP_SERVER_URL  — points to the NestJS backend /ai/mcp/execute endpoint
 *   MCP_AUTH_TOKEN  — JWT passed as Bearer token
 *   mcp()           — shell function to call any MCP tool from the terminal
 *   mcp_read        — shorthand: mcp read_file <path>
 *   mcp_write       — shorthand: mcp write_file <path> <content>
 *   mcp_run         — shorthand: mcp run_command <cmd>
 *   mcp_ls          — shorthand: mcp list_dir <path>
 *   mcp_grep        — shorthand: mcp search_files <query> <dir>
 *
 * Usage in terminal:
 *   mcp_ls /workspaces/my-project
 *   mcp_read src/main.kt
 *   mcp_run "gradle assembleDebug"
 *   mcp_write config.json '{"debug": true}'
 */
object McpShellProfile {

    fun install(context: Context, backendUrl: String, authToken: String) {
        val home = File(context.filesDir, "home").apply { mkdirs() }
        val script = File(home, ".mcp-profile.sh")
        script.writeText(buildProfile(backendUrl, authToken))
        script.setReadable(true, false)

        val bashrc = File(home, ".bashrc")
        val existing = if (bashrc.exists()) bashrc.readText() else ""
        if (!existing.contains(".mcp-profile.sh")) {
            bashrc.appendText("\nif [ -f ~/.mcp-profile.sh ]; then . ~/.mcp-profile.sh; fi\n")
        }

        // Also write an mcp.json config that tools like continue.dev or any MCP client can pick up
        val mcpConfig = File(home, ".mcp.json")
        mcpConfig.writeText(buildMcpJson(backendUrl, authToken))
    }

    private fun buildProfile(backendUrl: String, authToken: String): String = buildString {
        appendLine("# VN Code MCP profile — auto-generated")
        appendLine("export MCP_SERVER_URL='${backendUrl.trimEnd('/')}'")
        appendLine("export MCP_AUTH_TOKEN='$authToken'")
        appendLine("")
        appendLine("# Generic MCP tool caller")
        appendLine("mcp() {")
        appendLine("  local tool=\$1; shift")
        appendLine("  local params='{}'")
        appendLine("  case \"\$tool\" in")
        appendLine("    read_file)   params=\"{\\\"path\\\":\\\"\$1\\\"}\" ;;")
        appendLine("    write_file)  params=\"{\\\"path\\\":\\\"\$1\\\",\\\"content\\\":\\\"\$2\\\"}\" ;;")
        appendLine("    list_dir)    params=\"{\\\"path\\\":\\\"\${1:-.}\\\"}\" ;;")
        appendLine("    search_files)params=\"{\\\"query\\\":\\\"\$1\\\",\\\"dir\\\":\\\"\${2:-.}\\\"}\" ;;")
        appendLine("    run_command) params=\"{\\\"command\\\":\\\"\$1\\\"}\" ;;")
        appendLine("  esac")
        appendLine("  curl -s -X POST \"\$MCP_SERVER_URL/ai/mcp/execute\" \\")
        appendLine("    -H \"Authorization: Bearer \$MCP_AUTH_TOKEN\" \\")
        appendLine("    -H \"Content-Type: application/json\" \\")
        appendLine("    -d \"{\\\"tool\\\":\\\"\$tool\\\",\\\"params\\\":\$params}\" | python3 -c \"import sys,json; print(json.load(sys.stdin).get('result',''))\" 2>/dev/null || echo '[mcp] backend not reachable — using offline mode'")
        appendLine("}")
        appendLine("")
        appendLine("# Shorthands")
        appendLine("mcp_read()  { mcp read_file \"\$@\"; }")
        appendLine("mcp_write() { mcp write_file \"\$@\"; }")
        appendLine("mcp_run()   { mcp run_command \"\$@\"; }")
        appendLine("mcp_ls()    { mcp list_dir \"\$@\"; }")
        appendLine("mcp_grep()  { mcp search_files \"\$@\"; }")
        appendLine("")
        appendLine("# List available MCP tools")
        appendLine("mcp_tools() {")
        appendLine("  curl -s -X POST \"\$MCP_SERVER_URL/ai/mcp/tools\" \\")
        appendLine("    -H \"Authorization: Bearer \$MCP_AUTH_TOKEN\" | python3 -c \"import sys,json; [print(t['name']+':', t['description']) for t in json.load(sys.stdin).get('tools',[])]\" 2>/dev/null")
        appendLine("}")
        appendLine("")
        appendLine("# Ollama + MCP combo: run Nemotron with project context")
        appendLine("nemotron() {")
        appendLine("  local ctx=''")
        appendLine("  if [ -n \"\$1\" ] && [ -f \"\$1\" ]; then")
        appendLine("    ctx=\$(mcp_read \"\$1\")")
        appendLine("    shift")
        appendLine("  fi")
        appendLine("  if [ -n \"\$ctx\" ]; then")
        appendLine("    echo \"\$ctx\" | ollama run nemotron \"\$@\"")
        appendLine("  else")
        appendLine("    ollama run nemotron \"\$@\"")
        appendLine("  fi")
        appendLine("}")
        appendLine("")
        appendLine("echo '[MCP] Tools available: mcp_read mcp_write mcp_run mcp_ls mcp_grep mcp_tools nemotron'")
    }

    private fun buildMcpJson(backendUrl: String, authToken: String): String =
        """{
  "mcpServers": {
    "vncode": {
      "url": "$backendUrl/ai/mcp",
      "transport": "http",
      "headers": { "Authorization": "Bearer $authToken" },
      "tools": ["read_file","write_file","list_dir","search_files","run_command"]
    }
  }
}"""
}
