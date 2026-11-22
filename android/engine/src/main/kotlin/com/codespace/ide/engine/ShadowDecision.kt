package com.codespace.ide.engine

object ShadowDecision {
    fun inCanary(key: String, percent: Int): Boolean {
        if (percent <= 0) return false
        if (percent >= 100) return true
        return Math.floorMod(key.hashCode(), 100) < percent
    }
}
