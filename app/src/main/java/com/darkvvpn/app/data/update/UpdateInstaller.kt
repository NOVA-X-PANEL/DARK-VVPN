package com.darkvvpn.app.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded APK to the system installer.
 *
 * ── Why this is a separate class ──────────────────────────────────────────────
 * Installing an APK needs three things that a ViewModel must not own: a
 * `Context`, a `FileProvider` authority, and a jump into Settings. Keeping them
 * here means the ViewModel only ever asks a question ("can we install?") and
 * issues a command ("install this"), both of which are testable in principle and
 * trivial in practice.
 *
 * ── The two permissions ───────────────────────────────────────────────────────
 *  - `REQUEST_INSTALL_PACKAGES` is a *manifest* permission: without it the
 *    installer activity refuses the intent outright on API 26+.
 *  - "Install unknown apps" is a *user* grant per source app, opened via
 *    [Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES]. [canInstallPackages] reports
 *    it so the UI can offer to open that screen instead of failing silently.
 *
 * The file is shared as a `content://` URI through [FileProvider]; a `file://`
 * URI would throw `FileUriExposedException` on API 24+ and is precisely the
 * hole this indirection exists to close.
 */
class UpdateInstaller(private val context: Context) {

    /** True when the user has allowed this app to install packages. */
    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            // Before API 26 the permission is granted at install time.
            true
        }

    /**
     * Opens the system screen where the user grants install permission.
     * Silently no-ops when the settings activity is unavailable.
     */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Install-permission settings screen is unavailable") }
    }

    /**
     * Launches the package installer for [apk].
     *
     * @return `true` when the installer was launched, `false` when permission is
     *   missing or no installer is present — the caller should then call
     *   [openInstallPermissionSettings].
     */
    fun install(apk: File): Boolean {
        if (!apk.exists() || apk.length() == 0L) {
            Log.w(TAG, "install() called with a missing or empty file")
            return false
        }
        if (!canInstallPackages()) {
            Log.i(TAG, "install() blocked: the user has not granted install permission")
            return false
        }

        val uri = try {
            FileProvider.getUriForFile(context, authority(), apk)
        } catch (e: IllegalArgumentException) {
            // Thrown when the path is outside the configured FileProvider roots.
            Log.e(TAG, "APK is outside the shared FileProvider paths", e)
            return false
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "No activity could handle the install intent", e)
            false
        }
    }

    /** Version name currently installed, for the "you have / download" comparison. */
    fun installedVersionName(): String? = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** `1.0.0 (42)` — shown when `versionCode` adds information the name cannot. */
    fun installedVersionSummary(): String? = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun authority(): String = "${context.packageName}.fileprovider"

    private companion object {
        const val TAG = "UpdateInstaller"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
