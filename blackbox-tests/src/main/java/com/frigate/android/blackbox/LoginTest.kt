package com.frigate.android.blackbox

import org.junit.Test

class LoginTest : BlackboxTest() {
    @Test
    fun signInShowsTheTabs() {
        freshLaunch()
        signIn()
        assertVisible(text("Live"))
        assertVisible(text("Events"))
        assertVisible(text("Settings"))
        assertGone(text("Sign in"))
    }
}
