package com.frigate.android.core.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Downloads the APK of an update and hands it to the system installer.
 *
 * The first update asks the user to confirm it: the app becomes the installer of record only then.
 * The later updates go without the dialog ([PackageInstaller.SessionParams.setRequireUserAction]).
 * Every build is signed with the same key and shares versionCode 1, so Android takes the update
 * over the installed app and keeps its data.
 */
class UpdateInstaller(private val context: Context, private val client: OkHttpClient = OkHttpClient()) {
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system page where the user allows this app to install apps. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun download(update: AvailableUpdate, onProgress: (Int?) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(directory, "update.apk")
        client.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var reported = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val percent = total?.let { (copied * 100 / it).toInt() }
                        if (percent != reported) {
                            reported = percent ?: 0
                            onProgress(percent)
                        }
                    }
                }
            }
        }
        file
    }

    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("update.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                InstallResults.reset()
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName),
                    // The installer adds the status to the intent.
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
        } catch (error: Throwable) {
            installer.abandonSession(sessionId)
            throw error
        }
    }
}

/** The last result the system installer reported; null while it is still installing. */
object InstallResults {
    private val mutable = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = mutable

    fun reset() {
        mutable.value = null
    }

    fun fail(message: String) {
        mutable.value = message
    }
}
