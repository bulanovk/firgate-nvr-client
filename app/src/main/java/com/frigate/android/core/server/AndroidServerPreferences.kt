package com.frigate.android.core.server

import android.content.Context

class AndroidServerPreferences(
    context: Context,
    name: String = "frigate_server",
) : ServerPreferences {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? = preferences.getString(key, null)

    override fun put(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }
}
