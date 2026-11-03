package com.frigate.android.blackbox

import org.junit.Test

class SessionRenewTest : BlackboxTest() {
    @Test
    fun expiredSessionIsRenewedWithoutTheLoginScreen() {
        freshLaunch()
        signIn()
        control("/__control/expire")
        device.executeShellCommand("am force-stop $appId")
        launch()
        assertVisible(text("Live"), 20_000)
        assertGone(text("Sign in"))
        assertGone(text("Server URL"))
    }
}
