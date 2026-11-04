package com.frigate.android.core.network

/** Reports the Wi-Fi the phone is on; the only seam between the app and the Android network APIs. */
interface NetworkMonitor {
    /** Calls [onChange] with the current snapshot and again after each change; close to stop. */
    fun start(onChange: (NetworkSnapshot) -> Unit): AutoCloseable

    /** Re-reads what depends on the user, such as the location permission, after it changed. */
    fun refresh()
}
