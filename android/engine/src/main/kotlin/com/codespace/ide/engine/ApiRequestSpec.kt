package com.codespace.ide.engine

object ApiRequestSpec {
    enum class Method { GET, POST, PUT, PATCH, DELETE }
    data class Spec(val method: Method, val url: String, val headers: Map<String, String> = emptyMap(), val body: String? = null)
    sealed interface Check { object Ok : Check; data class Bad(val reason: String) : Check }
    fun check(s: Spec): Check {
        if (!s.url.startsWith("https://")) return Check.Bad("only https is allowed")
        if (s.method == Method.GET && s.body != null) return Check.Bad("GET cannot carry a body")
        return Check.Ok
    }
}
