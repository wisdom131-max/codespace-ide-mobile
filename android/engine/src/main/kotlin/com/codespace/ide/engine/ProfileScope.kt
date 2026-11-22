package com.codespace.ide.engine

class ProfileScope(private val current: String) {
    fun accepts(profileId: String): Boolean = profileId == current
    fun <T> filter(items: List<T>, owner: (T) -> String): List<T> = items.filter { accepts(owner(it)) }
    fun key(kind: String, id: String): String = current + "/" + kind + "/" + id
}
