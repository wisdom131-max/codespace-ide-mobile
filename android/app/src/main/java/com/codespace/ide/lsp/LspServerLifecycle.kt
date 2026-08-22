package com.codespace.ide.lsp

import org.json.JSONArray
import org.json.JSONObject

/**
 * R4-1: Extracted from LspManager — server lifecycle support functions.
 *
 * Contains pure functions for building LSP client capabilities and resolving
 * effective server configurations. These don't depend on LspManager's mutable
 * state, so they're safe to extract.
 *
 * Inspired by sora-editor's LanguageServerDefinition and ServerWrapperBaseClientContext,
 * which handle server initialization and capability negotiation separately from
 * the request routing logic.
 */
object LspServerLifecycle {

    /**
     * Build minimal client capabilities for servers that don't support full features.
     * Used for the initial connection before we know what the server supports.
     */
    fun buildMinimalClientCapabilities(): JSONObject {
        val textDocument = JSONObject().apply {
            put("synchronization", JSONObject().apply {
                put("didSave", true)
                put("dynamicRegistration", false)
            })
            put("completion", JSONObject().apply {
                put("completionItem", JSONObject().apply {
                    put("snippetSupport", false)
                    put("documentationFormat", JSONArray().apply { put("plaintext"); put("markdown") })
                })
                put("dynamicRegistration", false)
            })
            put("hover", JSONObject().apply {
                put("contentFormat", JSONArray().apply { put("plaintext"); put("markdown") })
                put("dynamicRegistration", false)
            })
            put("signatureHelp", JSONObject().apply {
                put("signatureInformation", JSONObject().apply {
                    put("documentationFormat", JSONArray().apply { put("plaintext") })
                })
                put("dynamicRegistration", false)
            })
            put("definition", JSONObject().apply { put("dynamicRegistration", false) })
            put("declaration", JSONObject().apply { put("dynamicRegistration", false); put("linkSupport", true) })
            put("typeDefinition", JSONObject().apply { put("dynamicRegistration", false); put("linkSupport", true) })
            put("implementation", JSONObject().apply { put("dynamicRegistration", false); put("linkSupport", true) })
            put("references", JSONObject().apply { put("dynamicRegistration", false) })
            put("rename", JSONObject().apply {
                put("dynamicRegistration", false)
                put("prepareSupport", true)
            })
            put("publishDiagnostics", JSONObject().apply {
                put("relatedInformation", false)
            })
            put("codeAction", JSONObject().apply {
                put("dynamicRegistration", false)
                put("codeActionLiteralSupport", JSONObject().apply {
                    put("codeActionKind", JSONObject().apply {
                        put("valueSet", JSONArray().apply {
                            put(""); put("quickfix"); put("refactor"); put("refactor.extract")
                            put("refactor.inline"); put("refactor.rewrite"); put("source")
                            put("source.organizeImports"); put("source.fixAll")
                        })
                    })
                })
            })
            put("documentSymbol", JSONObject().apply {
                put("dynamicRegistration", false)
                put("hierarchicalDocumentSymbolSupport", true)
            })
            put("foldingRange", JSONObject().apply {
                put("dynamicRegistration", false)
                put("lineFoldingOnly", true)
            })
            put("selectionRange", JSONObject().apply { put("dynamicRegistration", false) })
            put("documentHighlight", JSONObject().apply { put("dynamicRegistration", false) })
            put("formatting", JSONObject().apply { put("dynamicRegistration", false) })
            put("rangeFormatting", JSONObject().apply { put("dynamicRegistration", false) })
            put("codeLens", JSONObject().apply { put("dynamicRegistration", false) })
            put("documentLink", JSONObject().apply { put("dynamicRegistration", false) })
        }
        val workspace = JSONObject().apply {
            put("applyEdit", false)
            put("workspaceFolders", true)
            put("symbol", JSONObject().apply { put("dynamicRegistration", false) })
        }
        return JSONObject().apply {
            put("textDocument", textDocument)
            put("workspace", workspace)
        }
    }

    /**
     * Build full client capabilities — advertises all features the app supports.
     */
    fun buildClientCapabilities(): JSONObject {
        val sync = JSONObject().apply {
            put("didSave", true)
            put("willSave", false)
            put("dynamicRegistration", false)
        }
        val completionItem = JSONObject().apply {
            put("snippetSupport", false)
            put("documentationFormat", JSONArray().apply { put("plaintext"); put("markdown") })
        }
        val completion = JSONObject().apply {
            put("completionItem", completionItem)
            put("dynamicRegistration", false)
        }
        val hover = JSONObject().apply {
            put("contentFormat", JSONArray().apply { put("plaintext"); put("markdown") })
            put("dynamicRegistration", false)
        }
        val signatureInformation = JSONObject().apply {
            put("documentationFormat", JSONArray().apply { put("plaintext") })
        }
        val signatureHelp = JSONObject().apply {
            put("signatureInformation", signatureInformation)
            put("dynamicRegistration", false)
        }
        val basic = JSONObject().apply { put("dynamicRegistration", false) }
        val publishDiagnostics = JSONObject().apply {
            put("relatedInformation", false)
            put("versionSupport", false)
        }
        val codeAction = JSONObject().apply {
            put("dynamicRegistration", false)
            put("codeActionLiteralSupport", JSONObject().apply {
                put("codeActionKind", JSONObject().apply {
                    put("valueSet", JSONArray().apply {
                        put(""); put("quickfix"); put("refactor"); put("refactor.extract")
                        put("refactor.inline"); put("refactor.rewrite"); put("source")
                        put("source.organizeImports"); put("source.fixAll")
                        put("source.removeUnused")
                    })
                })
            })
            put("resolveProvider", true)
        }
        val semanticTokens = JSONObject().apply {
            put("dynamicRegistration", false)
            put("requests", JSONObject().apply { put("full", true) })
            put("tokenTypes", JSONArray())
            put("tokenModifiers", JSONArray())
            put("formats", JSONArray().apply { put("relative") })
        }
        val formatting = JSONObject().apply { put("dynamicRegistration", false) }
        val documentSymbol = JSONObject().apply {
            put("dynamicRegistration", false)
            put("hierarchicalDocumentSymbolSupport", true)
            put("labelSupport", JSONObject().apply { put("labelDetailsSupport", true) })
        }
        val foldingRange = JSONObject().apply {
            put("dynamicRegistration", false)
            put("rangeLimit", 5000)
            put("lineFoldingOnly", true)
        }
        val textDocument = JSONObject().apply {
            put("synchronization", sync)
            put("completion", completion)
            put("hover", hover)
            put("signatureHelp", signatureHelp)
            put("definition", basic)
            put("declaration", JSONObject().apply { put("dynamicRegistration", false); put("linkSupport", true) })
            put("typeDefinition", JSONObject().apply { put("dynamicRegistration", false); put("linkSupport", true) })
            put("implementation", JSONObject().apply { put("dynamicRegistration", false); put("linkSupport", true) })
            put("references", basic)
            put("rename", JSONObject().apply {
                put("dynamicRegistration", false)
                put("prepareSupport", true)
            })
            put("publishDiagnostics", publishDiagnostics)
            put("codeAction", codeAction)
            put("semanticTokens", semanticTokens)
            put("documentSymbol", documentSymbol)
            put("foldingRange", foldingRange)
            put("selectionRange", JSONObject().apply { put("dynamicRegistration", false) })
            put("documentHighlight", JSONObject().apply { put("dynamicRegistration", false) })
            put("formatting", formatting)
            put("rangeFormatting", formatting)
            put("onTypeFormatting", formatting)
            put("codeLens", JSONObject().apply { put("dynamicRegistration", false) })
            put("inlayHint", JSONObject().apply { put("dynamicRegistration", false) })
            put("documentLink", JSONObject().apply { put("dynamicRegistration", false); put("tooltipSupport", true) })
            put("callHierarchy", JSONObject().apply { put("dynamicRegistration", false) })
            put("typeHierarchy", JSONObject().apply { put("dynamicRegistration", false) })
            put("linkedEditingRange", JSONObject().apply { put("dynamicRegistration", false) })
            put("moniker", JSONObject().apply { put("dynamicRegistration", false) })
            put("documentColor", JSONObject().apply { put("dynamicRegistration", false) })
        }
        val workspace = JSONObject().apply {
            put("applyEdit", false)
            put("workspaceFolders", true)
            put("symbol", JSONObject().apply { put("dynamicRegistration", false) })
            put("fileOperations", JSONObject().apply {
                put("willRename", true)
                put("didRename", true)
            })
        }
        return JSONObject().apply {
            put("textDocument", textDocument)
            put("workspace", workspace)
        }
    }

    /**
     * Build the initialization options for a TypeScript/JavaScript LSP server.
     */
    fun buildTsLspInitOptions(): JSONObject = JSONObject().apply {
        put("hostInfo", "codespace-ide-mobile")
        put("preferences", JSONObject().apply {
            put("providePrefixAndSuffixTextForRename", true)
            put("allowRenameOfImportPath", true)
            put("importModuleSpecifierPreference", "relative")
        })
        put("npmLocation", "/data/data/com.codespace.ide/files/usr/bin")
        put("typescript", JSONObject().apply {
            put("tsdk", "/data/data/com.codespace.ide/files/usr/lib/node_modules/typescript/lib")
        })
    }

    /**
     * Build position parameters for LSP textDocument requests.
     */
    fun buildPositionParams(uri: String, line: Int, character: Int): JSONObject {
        return JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("position", JSONObject().apply {
                put("line", line)
                put("character", character)
            })
        }
    }
}
