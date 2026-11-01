package com.codespace.ide.agent

/**
 * What each connected service can actually be asked to DO.
 *
 * The bug this closes: the agent was told a connector was "connected" and then went looking for
 * a `gitlab_create_repo` tool, found none, and told the user it could not create the repo. There
 * was never going to be one. Connectors are driven generically through `use_connector`, which
 * proxies the provider's own REST API with the stored token, so the capability was there the whole
 * time and only its discoverability was missing.
 *
 * So the recipes are printed next to a CONNECTED service in `list_connectors`, which is the exact
 * moment the model is deciding whether it can do the job. They are NOT pasted into the system
 * prompt: that would tax every request forever to answer a question that comes up occasionally.
 *
 * Paths are relative to each provider's API base (the backend prepends it), except where a full
 * URL is needed.
 */
internal object ConnectorRecipes {

    /** Two or three canonical calls per service: enough to show the shape, not a full SDK. */
    private val RECIPES: Map<String, List<String>> = mapOf(
        "gitlab" to listOf(
            "GET /projects?membership=true&per_page=20 - list the projects the user can see",
            "POST /projects {name, visibility, initialize_with_readme:true} - CREATE A REPOSITORY",
            "GET /projects/:id/repository/branches - branches of one project",
        ),
        "gmail" to listOf(
            "GET /messages?q=is:unread&maxResults=10 - search or list mail",
            "GET /messages/:id - read one message",
            "POST /messages/send {raw} - send (raw is base64url RFC822)",
        ),
        "gcalendar" to listOf(
            "GET /calendars/primary/events - upcoming events",
            "POST /calendars/primary/events {summary,start,end} - create an event",
        ),
        "gdrive" to listOf(
            "GET /files?q=name contains 'x'&pageSize=20 - search files",
            "POST /files - create a file (multipart upload)",
        ),
        "slack" to listOf(
            "POST /chat.postMessage {channel,text} - post a message",
            "GET /conversations.list - channels",
        ),
        "notion" to listOf(
            "POST /search {query} - search pages and databases",
            "POST /pages {parent,properties} - create a page",
        ),
        "jira" to listOf(
            "GET /rest/api/3/search?jql=project=X - search issues",
            "POST /rest/api/3/issue {fields} - create an issue",
        ),
        "linear" to listOf(
            "POST /graphql {query} - Linear is GraphQL-only; every call is a POST /graphql",
        ),
        "discord" to listOf(
            "GET /users/@me/guilds - servers the user is in",
            "POST /channels/:id/messages {content} - post a message",
        ),
        "sentry" to listOf(
            "GET /api/0/projects/ - projects",
            "GET /api/0/projects/:org/:project/issues/ - issues",
        ),
        "stripe" to listOf(
            "GET /v1/customers?limit=10 - list customers",
            "POST /v1/payment_links {line_items} - create a payment link",
        ),
        "vercel" to listOf(
            "GET /v2/user - the signed-in account",
            "GET /v9/projects - projects",
        ),
        "cloudflare" to listOf(
            "GET /client/v4/zones - zones",
            "GET /client/v4/accounts - accounts",
        ),
        "render" to listOf(
            "GET /v1/services - services",
            "POST /v1/services/:id/deploys - trigger a deploy",
        ),
        "figma" to listOf(
            "GET /v1/files/:key - a whole file document",
            "GET /v1/files/:key/nodes?ids=1:2 - specific nodes",
        ),
        "huggingface" to listOf(
            "GET /api/models?search=x - search models",
            "GET /api/whoami-v2 - the signed-in account",
        ),
        "railway" to listOf(
            "POST /graphql {query} - Railway is GraphQL; owner token in the header",
        ),
        "canva" to listOf(
            "GET /v1/designs - designs",
            "POST /v1/designs - create a design",
        ),
        "posthog" to listOf(
            "GET /api/projects/ - projects",
            "GET /api/projects/:id/insights/ - saved insights",
        ),
    )

    /** Nothing for this service, or no service at all: an empty list, never a guess. */
    fun forService(service: String): List<String> = RECIPES[service.trim().lowercase()].orEmpty()

    fun has(service: String): Boolean = forService(service).isNotEmpty()

    /**
     * The lines shown under a service in `list_connectors`. Indented so it reads as part of that
     * service's block, and prefixed with the tool name each time: the model must not have to infer
     * which tool takes these arguments.
     */
    fun usageLines(service: String, indent: String = "      "): String {
        val recipes = forService(service)
        if (recipes.isEmpty()) return ""
        val sb = StringBuilder()
        for (recipe in recipes) {
            sb.append(indent).append("use_connector: ").append(recipe).append('\n')
        }
        return sb.toString()
    }
}
