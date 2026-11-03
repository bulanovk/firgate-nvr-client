package com.frigate.android.blackbox

import androidx.test.uiautomator.By
import org.junit.Test
import java.util.regex.Pattern

class CarouselHistoryTest : BlackboxTest() {
    @Test
    fun theCarouselOpensHistoryAndBackReturnsToLive() {
        freshLaunch()
        signIn()
        assertVisible(text("Unreviewed"), 20_000)
        assertVisible(text("Last hour"))
        tap(By.pkg(appId).text(Pattern.compile(".* ago")))
        assertGone(text("Unreviewed"), 10_000)
        assertVisible(desc("Back 10 seconds"), 10_000)
        device.pressBack()
        assertGridShown()
        // Открытая карточка отмечена просмотренной и ушла из карусели.
        assertGone(text("Unreviewed"))
    }
}
