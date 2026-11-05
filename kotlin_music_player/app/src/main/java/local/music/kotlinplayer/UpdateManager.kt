// SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
// SPDX-License-Identifier: GPL-3.0-or-later

package local.music.kotlinplayer

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ProgressBar
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

class UpdateManager(private val activity: Activity, savedState: Bundle? = null) {
    companion object { private var lastAutomaticCheck = -300000L }
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val releases = ReleaseUpdates()
    private var future: Future<*>? = null
    private var operation = 0
    private var busy = false
    private var closed = false
    private var visible = false
    private var pending: (() -> Unit)? = null
    private var dialog: AlertDialog? = null
    private var pendingFile: File? = null
    private var pendingCode = 0L
    private var waitingForPermission = false
    private var resumeInstall = false

    init {
        val code = savedState?.getLong("update_code", 0) ?: 0
        val filename = savedState?.getString("update_file")
        if (code > 0 && filename == "music-room-v$code.apk") {
            val file = File(File(activity.cacheDir, "updates"), filename)
            if (file.isFile) {
                pendingFile = file
                pendingCode = code
                waitingForPermission = savedState.getBoolean("update_permission", false)
                resumeInstall = !waitingForPermission
            }
        }
    }

    fun saveState(state: Bundle) {
        pendingFile?.let {
            state.putString("update_file", it.name)
            state.putLong("update_code", pendingCode)
            state.putBoolean("update_permission", waitingForPermission)
        }
    }

    fun onResume() {
        visible = true
        if (waitingForPermission) {
            waitingForPermission = false
            if (activity.packageManager.canRequestPackageInstalls()) installReady()
            else {
                pendingFile = null
                showError("Installation permission was not enabled. You can try again from Settings → Check updates.")
            }
        } else if (resumeInstall) {
            resumeInstall = false
            installReady()
        }
        pending?.also { pending = null; it() }
    }

    fun onPause() { visible = false }

    fun check(automatic: Boolean = false) {
        if (closed || busy || pendingFile != null || dialog?.isShowing == true) return
        if (automatic) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAutomaticCheck < 300000) return
            lastAutomaticCheck = now
        } else Toast.makeText(activity, "Checking GitHub for updates…", Toast.LENGTH_SHORT).show()
        busy = true
        val request = ++operation
        future = worker.submit {
            try {
                @Suppress("DEPRECATION")
                val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
                val installedCode = UpdateInstaller.code(installed)
                File(activity.cacheDir, "updates").listFiles()?.forEach { file ->
                    val code = Regex("music-room-v([0-9]+)\\.apk").matchEntire(file.name)
                        ?.groupValues?.get(1)?.toLongOrNull()
                    if ((code != null && code <= installedCode) ||
                        (file.name.endsWith(".part") && System.currentTimeMillis() - file.lastModified() > 86400000)) file.delete()
                }
                val update = releases.check(installedCode)
                post(request) {
                    busy = false
                    whenVisible {
                        if (update != null) offer(update)
                        else if (!automatic) showError("Music Room is up to date.", "Updates")
                    }
                }
            } catch (error: Exception) {
                post(request) {
                    busy = false
                    if (!automatic) whenVisible { showError(error.message ?: "Could not check for updates.") }
                }
            }
        }
    }

    private fun offer(update: AppUpdate) {
        dialog = AlertDialog.Builder(activity).setTitle("Update available")
            .setMessage("Music Room ${update.name} is available. Download and install it?\n\n" +
                "Your settings will stay. Android will ask you to confirm installation.")
            .setNegativeButton("Later", null).setPositiveButton("Yes, update") { _, _ -> download(update) }.show()
    }

    private fun download(update: AppUpdate) {
        busy = true
        val request = ++operation
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val inset = (24 * activity.resources.displayMetrics.density).toInt()
        val holder = android.widget.FrameLayout(activity).apply {
            setPadding(inset, inset, inset, inset)
            addView(progress)
        }
        dialog = AlertDialog.Builder(activity).setTitle("Downloading Music Room")
            .setView(holder).setNegativeButton("Cancel") { _, _ -> cancelDownload() }
            .setOnCancelListener { cancelDownload() }.show()
        future = worker.submit {
            try {
                val file = releases.download(update, File(activity.cacheDir, "updates")) { percent ->
                    post(request) { progress.progress = percent }
                }
                try { UpdateInstaller.verify(activity, file, update.code) }
                catch (error: Exception) { file.delete(); throw error }
                post(request) {
                    busy = false
                    dialog?.dismiss()
                    pendingFile = file
                    pendingCode = update.code
                    whenVisible { installReady() }
                }
            } catch (error: Exception) {
                post(request) {
                    busy = false
                    dialog?.dismiss()
                    whenVisible { showError(error.message ?: "Update download failed.") }
                }
            }
        }
    }

    private fun installReady() {
        val file = pendingFile ?: return
        try {
            UpdateInstaller.verify(activity, file, pendingCode)
            if (!activity.packageManager.canRequestPackageInstalls()) {
                dialog = AlertDialog.Builder(activity).setTitle("Allow this update")
                    .setMessage("Enable “Allow from this source” for Music Room, then return here to install the update.")
                    .setNegativeButton("Cancel") { _, _ -> pendingFile = null }
                    .setPositiveButton("Open settings") { _, _ ->
                        try {
                            waitingForPermission = true
                            activity.startActivity(UpdateInstaller.permissionIntent(activity))
                        } catch (_: Exception) {
                            waitingForPermission = false
                            showError("Could not open install permissions. Enable them in Android settings.")
                        }
                    }.show()
                return
            }
            activity.startActivity(UpdateInstaller.installIntent(activity, file))
            pendingFile = null
        } catch (error: Exception) {
            showError(error.message ?: "Android could not open the update installer.")
        }
    }

    private fun cancelDownload() {
        operation++
        releases.cancel()
        future?.cancel(true)
        busy = false
    }

    private fun post(request: Int, action: () -> Unit) {
        ui.post { if (!closed && request == operation) action() }
    }
    private fun whenVisible(action: () -> Unit) {
        if (visible && !activity.isFinishing) action() else pending = action
    }
    private fun showError(text: String, title: String = "Music Room update") {
        if (closed || activity.isFinishing) return
        dialog = AlertDialog.Builder(activity).setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
    }

    fun close() {
        closed = true
        operation++
        releases.cancel()
        future?.cancel(true)
        worker.shutdownNow()
        ui.removeCallbacksAndMessages(null)
        pending = null
        dialog?.dismiss()
    }
}
