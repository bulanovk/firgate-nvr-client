package com.frigate.android.blackbox

import android.os.SystemClock
import androidx.test.uiautomator.By
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.regex.Pattern

/**
 * Приёмка воспроизведения снаружи приложения. Mock отдаёт реальное H.264 (пурпурный квадрат движется
 * по зелёному фону), поэтому «кадр декодирован» и «воспроизведение идёт» проверяются по скриншотам.
 */
class VideoPlaybackTest : BlackboxTest() {
    @Before
    fun needMedia() = requireMedia()

    /** audio: none, sound или silent — режим звука потока в mock; ставится после сброса, который делает freshLaunch. */
    private fun openCamera(audio: String = "none") {
        freshLaunch()
        control("/__control/audio/$audio")
        signIn()
        tapCameraTile()
        assertVisible(desc("Back to cameras"))
    }

    @Test
    fun liveShowsADecodedFrameAndKeepsPlaying() {
        openCamera()
        awaitSquare()
        assertPlaying(samples = 40, intervalMs = 1_000)
    }

    @Test
    fun livePlaybackSurvivesTheRotation() {
        openCamera()
        awaitSquare()
        device.setOrientationLeft()
        device.waitForIdle()
        awaitSquare(20_000)
        // В альбомной компоновке окно показывает не всю картинку, квадрат может быть вне его: смотрим на фон.
        assertPlayingByBackground(samples = 6, intervalMs = 1_000)
        device.setOrientationNatural()
        device.waitForIdle()
        awaitSquare(20_000)
    }

    @Test
    fun aPinchEnlargesTheDecodedPicture() {
        openCamera()
        val before = awaitSquare()
        waitFor(By.pkg(appId).descStartsWith("Zoom 1")).pinchOpen(0.6f)
        device.waitForIdle()
        SystemClock.sleep(1_000)
        val shot = screenshot()
        val after = findSquare(shot)
        shot.recycle()
        assertTrue("после щипка квадрат должен стать крупнее: было ${before.width}, стало ${after?.width}",
            after != null && after.width > before.width * 1.3)
    }

    @Test
    fun theHistoryPlaysTheArchiveAndKeepsPlaying() {
        freshLaunch()
        signIn()
        assertVisible(text("Unreviewed"), 20_000)
        tap(By.pkg(appId).text(Pattern.compile(".* ago")))
        assertVisible(desc("Back 10 seconds"), 15_000)
        awaitSquare()
        assertPlaying(samples = 8, intervalMs = 1_000)
    }

    @Test
    fun theHistoryPictureZoomsWithAPinch() {
        freshLaunch()
        signIn()
        assertVisible(text("Unreviewed"), 20_000)
        tap(By.pkg(appId).text(Pattern.compile(".* ago")))
        assertVisible(desc("Back 10 seconds"), 15_000)
        val before = awaitSquare()
        waitFor(By.pkg(appId).descStartsWith("Zoom 1")).pinchOpen(0.6f)
        device.waitForIdle()
        SystemClock.sleep(1_000)
        val shot = screenshot()
        val after = findSquare(shot)
        shot.recycle()
        assertTrue("зум архива: было ${before.width}, стало ${after?.width}", after != null && after.width > before.width * 1.3)
    }

    @Test
    fun pictureInPictureKeepsPlayingAndReturnsToThePanel() {
        openCamera()
        awaitSquare()
        tap(desc("Picture in picture"))
        // Окно закреплено системой: это видно в состоянии activity, а не по внутреннему флагу приложения.
        val deadline = SystemClock.uptimeMillis() + 15_000
        var pinned = false
        while (!pinned && SystemClock.uptimeMillis() < deadline) {
            pinned = device.executeShellCommand("dumpsys activity activities").lines()
                .any { it.contains(appId) && it.contains("pinned", ignoreCase = true) }
            if (!pinned) SystemClock.sleep(300)
        }
        assertTrue("activity не перешла в картинку в картинке", pinned)
        awaitSquare(20_000)
        assertPlaying(samples = 6, intervalMs = 1_000)
        // Возврат: приложение выходит на передний план, панель камеры на месте.
        bringToFront()
        assertVisible(desc("Back to cameras"), 15_000)
        awaitSquare(20_000)
    }

    /** Звук приложения снаружи: плеер процесса приложения (`u/pid` в AudioPlaybackConfiguration) в состоянии started. */
    private fun appPlayers(): List<String> {
        val pid = device.executeShellCommand("pidof $appId").trim()
        if (pid.isEmpty()) return emptyList()
        return device.executeShellCommand("dumpsys audio").lineSequence()
            .filter { it.contains("AudioPlaybackConfiguration") && it.contains("/$pid ") }.toList()
    }

    private fun appPlaysSound() = appPlayers().any { it.contains("state:started") }

    @Test
    fun aCameraWithoutAudioOffersNoSoundButton() {
        openCamera()
        awaitSquare()
        assertGone(desc("Unmute audio"))
        assertGone(desc("Mute audio"))
    }

    @Test
    fun theSoundIsOfferedMutedAndPlaysWhenAskedFor() {
        openCamera("sound")
        awaitSquare()
        assertVisible(desc("Unmute audio"))
        assertTrue("до нажатия звук не должен играть", !appPlaysSound())
        tap(desc("Unmute audio"))
        assertVisible(desc("Mute audio"))
        // Больше 5 с: так долго приложение ждёт звук, прежде чем объявить камеру немой.
        SystemClock.sleep(8_000)
        assertVisible(desc("Mute audio"))
        assertGone(text("This camera has no audio"), 1_000)
        assertTrue("звук должен играть: ${appPlayers()}", appPlaysSound())
        assertPlaying(samples = 6, intervalMs = 1_000)
    }

    @Test
    fun aCameraThatAnnouncesAudioButSendsNoneSaysSoAndKeepsPlaying() {
        openCamera("silent")
        awaitSquare()
        tap(desc("Unmute audio"))
        // Snackbar живёт около 4 с: ждать его по событиям нельзя, опрашиваем часто и ищем без ожидания.
        val deadline = SystemClock.uptimeMillis() + 20_000
        var told = false
        while (!told && SystemClock.uptimeMillis() < deadline) {
            keepScreenOn()
            told = device.findObject(text("This camera has no audio")) != null
            if (!told) SystemClock.sleep(150)
        }
        assertTrue("камера без звука должна сказать об этом", told)
        assertGone(desc("Mute audio"))
        assertGone(desc("Unmute audio"))
        assertTrue("немая камера не должна давать звук", !appPlaysSound())
        assertPlaying(samples = 6, intervalMs = 1_000)
    }
}
