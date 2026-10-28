package com.codespace.ide.chat.panel

import android.content.Context
import android.content.SharedPreferences

/**
 * WP6: rebuilt-chat settings bits that land BEFORE the full WP7 Settings
 * screens. Automatic key failover is OPT-IN (silent switching burns credits);
 * default false. Lazy init - nothing runs at Application start (owner rule).
 */
object ChatSettingsPrefs {
    private const val PREFS = "chat_settings"
    private const val KEY_FAILOVER = "failover_opt_in"

    @Volatile private var prefs: SharedPreferences? = null

    fun ensure(ctx: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                }
            }
        }
    }

    fun failoverOptIn(): Boolean = prefs?.getBoolean(KEY_FAILOVER, false) ?: false

    fun setFailoverOptIn(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_FAILOVER, enabled)?.apply()
    }
}
