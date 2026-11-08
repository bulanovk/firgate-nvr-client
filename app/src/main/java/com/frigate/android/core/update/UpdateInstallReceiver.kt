package com.frigate.android.core.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/**
 * The system installer reports the update here. It asks to confirm the first update with its own
 * dialog; a successful update replaces the app, and the process ends with it.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                InstallResults.fail(
                    if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "The update was cancelled"
                    else "The update was not installed" + (reason?.let { ": $it" } ?: ""),
                )
            }
        }
    }
}
