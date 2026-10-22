package com.tytv.app.update

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.tytv.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared update flow used by both trigger points (silent launch check
 * and the manual "Check for updates" card). The system install prompt
 * is the user's confirmation step; nothing installs without it.
 */
object UpdateFlow {

    /** Starts the download → install sequence for an available update. */
    fun start(activity: FragmentActivity, info: UpdateInfo) {
        activity.lifecycleScope.launch {
            if (!UpdateInstaller.canInstall(activity)) {
                Toast.makeText(
                    activity,
                    R.string.update_allow_install,
                    Toast.LENGTH_LONG
                ).show()
                UpdateInstaller.openUnknownSourcesSettings(activity)
                return@launch
            }
            Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_SHORT).show()
            val file = withContext(Dispatchers.IO) {
                UpdateInstaller.download(activity, info.apkUrl)
            }
            if (file == null) {
                Toast.makeText(
                    activity,
                    R.string.update_download_failed,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            if (!UpdateInstaller.install(activity, file)) {
                Toast.makeText(
                    activity,
                    R.string.update_install_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
