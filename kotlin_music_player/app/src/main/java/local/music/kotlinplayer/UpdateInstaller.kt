// SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
// SPDX-License-Identifier: GPL-3.0-or-later

package local.music.kotlinplayer

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object UpdateInstaller {
    @Suppress("DEPRECATION")
    fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val certificates = if (Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners ?: info.signatures else info.signatures
        return certificates?.map { it.toCharsString() }?.toSet().orEmpty()
    }

    internal fun requireCompatible(installed: PackageInfo, candidate: PackageInfo, expected: Long) {
        require(candidate.packageName == installed.packageName) { "This APK belongs to a different application." }
        require(code(candidate) == expected && expected > code(installed)) { "This APK is not a newer Music Room update." }
        val keys = signatures(installed)
        require(keys.isNotEmpty() && signatures(candidate) == keys) { "This APK was signed with a different key." }
        require((candidate.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) {
            "This update needs a newer Android version."
        }
    }

    @Suppress("DEPRECATION")
    fun verify(activity: Activity, file: File, expected: Long) {
        val flags = PackageManager.GET_SIGNATURES or
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val installed = activity.packageManager.getPackageInfo(activity.packageName, flags)
        val candidate = activity.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IllegalArgumentException("The downloaded APK could not be read.")
        requireCompatible(installed, candidate, expected)
    }

    fun permissionIntent(activity: Activity) = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${activity.packageName}"))

    fun installIntent(activity: Activity, file: File): Intent {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("Music Room update", uri) }
    }
}
