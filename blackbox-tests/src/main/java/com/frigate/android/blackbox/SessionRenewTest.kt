package com.frigate.android.blackbox

import android.os.SystemClock
import androidx.test.uiautomator.By
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.regex.Pattern

/**
 * Сессия переживает и истечение на сервере, и недоступный сервер. Пароль спрашивается, только когда сервер
 * сам отказал во входе; недоступный сервер (заблокированная сеть, упавший туннель) — не отказ.
 */
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

    /**
     * Пока экран погашен, сессия на сервере истекает, а первые попытки войти снова не доходят до сервера.
     * После включения экрана приложение входит само, как только сервер отвечает.
     */
    @Test
    fun anUnreachableServerDuringRenewalDoesNotAskForThePassword() {
        freshLaunch()
        signIn()
        device.sleep()
        control("/__control/expire")
        control("/__control/fail/login/0/3")
        SystemClock.sleep(3_000)
        device.wakeUp()
        // Вход при signIn, три оборванных попытки и успешная.
        awaitSignedInWithoutThePassword("POST /api/login", atLeast = 5)
    }

    /** Сохранённая сессия действительна, но сервер не отвечает при запуске: приложение ждёт его, а не просит пароль. */
    @Test
    fun anUnreachableServerAtStartDoesNotAskForThePassword() {
        freshLaunch()
        signIn()
        control("/__control/fail/profile/0/3")
        device.executeShellCommand("am force-stop $appId")
        launch()
        // Профиль при signIn, три оборванных запроса и успешный.
        awaitSignedInWithoutThePassword("GET /api/profile", atLeast = 5)
    }

    /** Сессия истекла, пока экран был погашен на архиве: приложение входит заново, и архив играет дальше. */
    @Test
    fun theArchiveKeepsPlayingAfterTheSessionIsRenewed() {
        requireMedia()
        freshLaunch()
        control("/__control/review/${System.currentTimeMillis() / 1000 - 300}")
        signIn()
        assertVisible(text("Unreviewed"), 20_000)
        tap(By.pkg(appId).text(Pattern.compile(".* ago")))
        awaitSquare()

        screenOff()
        control("/__control/expire")
        val logins = requestCount("POST /api/login")
        screenOn()
        awaitRequests("POST /api/login", logins + 1, timeoutMs = 20_000)
        awaitSquare(30_000)
        assertPlaying(samples = 4, intervalMs = 1_000)
    }

    /** Экран входа на любом шаге — провал; успех — mock получил [atLeast] запросов [request] и видна вкладка Live. */
    private fun awaitSignedInWithoutThePassword(request: String, atLeast: Int, timeoutMs: Long = 45_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            assertNull(
                "приложение просит пароль, хотя сервер был только недоступен; запросы: ${requestLog().takeLast(12)}",
                device.findObject(text("Sign in")),
            )
            if (requestCount(request) >= atLeast && device.hasObject(text("Live"))) return
            keepScreenOn()
            SystemClock.sleep(300)
        }
        error("за $timeoutMs мс приложение не вошло снова: ${requestCount(request)} запросов «$request»; ${requestLog().takeLast(12)}")
    }
}
