package com.frigate.android.blackbox

import android.os.SystemClock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Выбор адреса по Wi-Fi. Основной адрес — BB_MOCK_HOST, домашний — другое loopback-имя (127.0.0.1 или localhost; тот же mock через
 * `adb reverse`); какой из них использовало приложение, видно по заголовку Host в `/__control/hosts`.
 * Устройство должно быть подключено к Wi-Fi: SSID берётся у самой сети, а не из настроек теста.
 */
class HomeNetworkTest : BlackboxTest() {
    // Домашний адрес — другое loopback-имя того же mock, чтобы заголовок Host различал адреса.
    private val homeHost get() =
        (if (requireArg("bbMockHost") == "localhost") "127.0.0.1" else "localhost") + ":${requireArg("bbMockPort")}"
    private val primaryHost get() = "${requireArg("bbMockHost")}:${requireArg("bbMockPort")}"

    private fun grantLocation() {
        device.executeShellCommand("pm grant $appId android.permission.ACCESS_FINE_LOCATION")
    }

    private fun hosts(): List<String> =
        Regex("\"([^\"]+)\"").findAll(control("/__control/hosts")).map { it.groupValues[1] }.toList()

    private fun awaitHost(host: String, timeoutMs: Long = 20_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (host in hosts()) return true
            SystemClock.sleep(500)
        }
        return false
    }

    /** Новая сессия, вход по основному адресу, вкладка настроек в режиме просмотра. */
    private fun openSettings(withPermission: Boolean) {
        freshLaunch()
        if (withPermission) {
            grantLocation()
            launch()
        }
        signIn()
        tap(text("Settings"))
        waitFor(text("Edit"))
    }

    /** Режим правки: поля основного адреса (0), домашнего адреса (1) и SSID (2). */
    private fun openEdit() {
        tap(text("Edit"))
        waitFor(text("Edit server"))
    }

    private fun saveHome(url: String, addCurrentNetwork: Boolean) {
        openEdit()
        setField(1, url)
        if (addCurrentNetwork) {
            scrollTo(textStartsWith("Add current network ("))
            tap(textStartsWith("Add current network ("))
        }
        tap(text("Save"))
        waitFor(text("Edit"))
        // Вкладка Live сама ходит на активный адрес: без этого при неизменном адресе новых запросов могло не быть.
        tap(text("Live"))
    }

    private fun textStartsWith(prefix: String) =
        androidx.test.uiautomator.By.pkg(appId).textStartsWith(prefix)

    @Test
    fun knownNetworkSwitchesToTheHomeAddress() {
        openSettings(withPermission = true)
        control("/__control/reset-hosts")
        saveHome("http://$homeHost", addCurrentNetwork = true)
        assertTrue("приложение должно обратиться к домашнему адресу $homeHost; были: ${hosts()}", awaitHost(homeHost))
        assertVisible(text("Live"))
    }

    @Test
    fun unreachableHomeAddressFallsBackToThePrimaryOne() {
        openSettings(withPermission = true)
        control("/__control/reset-hosts")
        saveHome("http://127.0.0.1:1", addCurrentNetwork = true)
        assertTrue("после отката приложение должно вернуться на $primaryHost; были: ${hosts()}", awaitHost(primaryHost, 30_000))
        assertVisible(text("Live"))
    }

    @Test
    fun networkMissingFromTheListKeepsThePrimaryAddress() {
        openSettings(withPermission = true)
        control("/__control/reset-hosts")
        saveHome("http://$homeHost", addCurrentNetwork = false)
        assertTrue(awaitHost(primaryHost))
        SystemClock.sleep(3_000)
        assertFalse("домашний адрес не должен использоваться: ${hosts()}", homeHost in hosts())
    }

    @Test
    fun withoutLocationPermissionTheCurrentNetworkCannotBeAddedAndPrimaryStays() {
        openSettings(withPermission = false)
        control("/__control/reset-hosts")
        openEdit()
        scrollTo(text("Allow location"))
        assertGone(textStartsWith("Add current network ("))
    }

    @Test
    fun viewModeShowsTheServerAndEditingStartsOnlyOnRequest() {
        openSettings(withPermission = false)
        assertVisible(text("Main address"))
        assertVisible(text(mockUrl))
        assertVisible(text("Using now"))
        assertGone(text("Save"))
        openEdit()
        assertVisible(text("Save"))
        assertVisible(text("Cancel"))
    }

    @Test
    fun cancelDiscardsTheChanges() {
        openSettings(withPermission = false)
        openEdit()
        setField(1, "http://$homeHost")
        tap(text("Cancel"))
        waitFor(text("Edit"))
        assertVisible(text("Not set"))
    }

    @Test
    fun changingTheMainAddressAsksToSignInAgainAtTheNewAddress() {
        openSettings(withPermission = false)
        control("/__control/reset-hosts")
        openEdit()
        setField(0, "http://$homeHost")
        tap(text("Save"))
        waitFor(text("Sign in"), 20_000)
        // Пароль ещё никто не вводил: ошибки «Invalid username or password.» быть не должно.
        assertGone(text("Invalid username or password."))
        val (user, password) = credentials()
        setField(0, user)
        setField(1, password)
        tap(text("Sign in"))
        waitFor(text("Live"), 20_000)
        assertTrue("после смены адреса запросы идут на $homeHost; были: ${hosts()}", awaitHost(homeHost))
    }

    @Test
    fun invalidMainAddressIsRejectedAtItsField() {
        openSettings(withPermission = false)
        openEdit()
        setField(0, "http://example.com")
        tap(text("Save"))
        assertVisible(text("Use an https:// URL for the main address."))
        assertVisible(text("Save"))
    }
}
