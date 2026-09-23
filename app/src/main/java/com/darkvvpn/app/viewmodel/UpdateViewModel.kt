package com.darkvvpn.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.darkvvpn.app.BuildConfig
import com.darkvvpn.app.DarkVvpnApplication
import com.darkvvpn.app.data.model.AppSettings
import com.darkvvpn.app.data.repository.SettingsRepository
import com.darkvvpn.app.data.update.AppRelease
import com.darkvvpn.app.data.update.DownloadResult
import com.darkvvpn.app.data.update.DownloadState
import com.darkvvpn.app.data.update.UpdateBadgePolicy
import com.darkvvpn.app.data.update.UpdateCheckResult
import com.darkvvpn.app.data.update.UpdateChecker
import com.darkvvpn.app.data.update.UpdateDownloader
import com.darkvvpn.app.data.update.UpdateInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * The standing notice that a newer release exists.
 *
 * Separate from [UpdateUiState], which describes a dialog that is open right now.
 * The badge outlives the dialog: dismissing the sheet, or never opening it, must
 * not make the notice disappear — that is the whole point of a badge.
 */
data class UpdateBadge(
    val tag: String,
    val version: String,
    val isPrerelease: Boolean,
)

/** What the update sheet is doing right now. */
sealed interface UpdateUiState {
    data object Hidden : UpdateUiState

    data object Checking : UpdateUiState

    data class Available(
        val release: AppRelease,
        val installedVersion: String,
        val isDownloading: Boolean = false,
        val downloadFraction: Float? = null,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long? = null,
        val readyToInstall: File? = null,
        val readyVerified: Boolean = false,
        val needsInstallPermission: Boolean = false,
        val error: String? = null,
    ) : UpdateUiState

    data class UpToDate(val currentVersion: String) : UpdateUiState

    data class Failed(val reason: String) : UpdateUiState
}

/**
 * Drives the in-app update flow: badge, check, download, verify, install.
 *
 * ── The badge is the notification, the sheet is the action ───────────────────
 * An update is announced by a small amber badge on the Settings tab and a banner
 * at the top of Settings. The full sheet opens only when the user taps one of
 * them. Nothing interrupts a launch with a modal, which is what the earlier
 * design did and what made an update feel like an obstacle.
 *
 * ── Offline first ────────────────────────────────────────────────────────────
 * The newest release the app has ever seen is persisted, so the badge is drawn
 * from storage on the next launch with no network call and no delay. A VPN user
 * is frequently offline by the time they see the launcher, and an update notice
 * that needs connectivity to appear is a notice they never see.
 *
 * ── Failing quietly ──────────────────────────────────────────────────────────
 * [checkOnLaunch] resolves every failure to "no change": a rate limit, a dead
 * network, or "no releases yet" is not something the user asked about. [check]
 * from the Settings screen always reports its outcome, because there the user did
 * ask.
 */
class UpdateViewModel(
    private val settingsRepository: SettingsRepository,
    private val checker: UpdateChecker,
    private val downloader: UpdateDownloader,
    private val installer: UpdateInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Hidden)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _badge = MutableStateFlow<UpdateBadge?>(null)

    /** The standing notice: non-null while a newer release is known about. */
    val badge: StateFlow<UpdateBadge?> = _badge.asStateFlow()

    private val _checkReport = MutableStateFlow<String?>(null)

    /** One line of feedback for the manual check, shown under the Settings row. */
    val checkReport: StateFlow<String?> = _checkReport.asStateFlow()

    val downloadState: StateFlow<DownloadState> = downloader.state

    /** The release the open sheet is about, kept so a re-tap does not re-fetch. */
    private var lastKnownRelease: AppRelease? = null

    private var settingsSnapshot: AppSettings = AppSettings()

    init {
        // Draw the badge from storage before anything touches the network, so it
        // is on screen the instant the app opens.
        viewModelScope.launch {
            // A storage failure here would otherwise reach the default handler and
            // crash on launch, from a collector whose only job is to draw a badge.
            try {
                settingsRepository.settings.collect { settings ->
                    settingsSnapshot = settings
                    refreshBadgeFromSettings(settings)
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // No badge is the correct outcome when the setting cannot be read.
            }
        }

        // Mirror the downloader's progress into the sheet. The downloader owns the
        // transfer; the ViewModel only relays its numbers, so there is one source
        // of truth for how far along a download is.
        viewModelScope.launch {
            downloader.state.collect { progress ->
                val current = _state.value as? UpdateUiState.Available ?: return@collect
                _state.value = when (progress) {
                    is DownloadState.Running -> current.copy(
                        isDownloading = true,
                        downloadFraction = progress.fraction,
                        downloadedBytes = progress.bytesRead,
                        totalBytes = progress.totalBytes ?: current.totalBytes,
                    )
                    is DownloadState.Complete -> current.copy(
                        isDownloading = false,
                        downloadFraction = 1f,
                        readyToInstall = progress.file,
                        readyVerified = progress.sha256Verified,
                    )
                    is DownloadState.Idle -> current.copy(
                        isDownloading = false,
                        downloadFraction = null,
                    )
                    is DownloadState.Failed -> current  // handled by the awaiting call
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Badge
    // ------------------------------------------------------------------

    /**
     * Recomputes the badge from persisted state.
     *
     * Runs on every settings emission, which is also what makes the badge vanish
     * by itself after an update is installed: the stored version no longer
     * outranks [BuildConfig.VERSION_NAME], so the policy returns false and
     * nothing has to clear it.
     */
    private fun refreshBadgeFromSettings(settings: AppSettings) {
        val show = UpdateBadgePolicy.shouldShow(
            knownVersion = settings.knownReleaseVersion,
            installedVersion = BuildConfig.VERSION_NAME,
            skippedTag = settings.skippedUpdateTag,
        )
        val version = settings.knownReleaseVersion
        _badge.value = if (show && version != null) {
            UpdateBadge(
                tag = settings.knownReleaseTag ?: "v$version",
                version = version,
                isPrerelease = settings.knownReleaseIsPrerelease,
            )
        } else {
            null
        }
    }

    // ------------------------------------------------------------------
    // Check
    // ------------------------------------------------------------------

    /** Silent check for the app start. Never surfaces a failure. */
    fun checkOnLaunch() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            if (!settings.checkForUpdatesOnLaunch) return@launch
            // Do not re-hit the API on every cold start within the same day.
            val last = settings.lastUpdateCheckEpochMillis
            if (last != null && System.currentTimeMillis() - last < CHECK_COOLDOWN_MS) return@launch

            runCheck(settings, silent = true)
        }
    }

    /** Manual check from Settings. Always reports its outcome. */
    fun check() {
        viewModelScope.launch {
            _checkReport.value = null
            runCheck(settingsSnapshot, silent = false)
        }
    }

    private suspend fun runCheck(settings: AppSettings, silent: Boolean) {
        if (!silent) _state.value = UpdateUiState.Checking

        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            checker.check(
                allowPrerelease = settings.allowPrereleaseUpdates,
                skippedTag = settings.skippedUpdateTag,
            )
        }

        settingsRepository.setLastUpdateCheck(System.currentTimeMillis())

        when (result) {
            is UpdateCheckResult.UpdateAvailable -> {
                lastKnownRelease = result.release
                // Persist first: the badge is derived from settings, so writing the
                // release is what makes it appear, and it stays correct offline.
                settingsRepository.setKnownRelease(
                    tag = result.release.tag,
                    version = result.release.versionName,
                    isPrerelease = result.release.isPrerelease,
                )
                val installed = installer.installedVersionName() ?: BuildConfig.VERSION_NAME

                if (silent) {
                    // Announce, do not interrupt. The badge and banner appear; the
                    // sheet waits for a tap.
                    _state.value = UpdateUiState.Hidden
                } else {
                    _state.value = UpdateUiState.Available(
                        release = result.release,
                        installedVersion = installed,
                        needsInstallPermission = !installer.canInstallPackages(),
                    )
                }
            }

            is UpdateCheckResult.UpToDate -> {
                _state.value = if (silent) UpdateUiState.Hidden else UpdateUiState.UpToDate(BuildConfig.VERSION_NAME)
                if (!silent) {
                    _checkReport.value = if (result.latestTag != null) {
                        "You are on the newest version (${BuildConfig.VERSION_NAME})."
                    } else {
                        "No published release was found."
                    }
                }
            }

            is UpdateCheckResult.Failed -> {
                _state.value = if (silent) UpdateUiState.Hidden else UpdateUiState.Failed(result.reason)
                if (!silent) _checkReport.value = result.reason
            }
        }
    }

    // ------------------------------------------------------------------
    // Sheet
    // ------------------------------------------------------------------

    /** Opens the sheet for the known release, reusing it if already fetched. */
    fun openSheet() {
        val badge = _badge.value ?: return
        val cached = lastKnownRelease
        val installed = installer.installedVersionName() ?: BuildConfig.VERSION_NAME

        if (cached != null && cached.tag == badge.tag) {
            _state.value = UpdateUiState.Available(
                release = cached,
                installedVersion = installed,
                needsInstallPermission = !installer.canInstallPackages(),
            )
            return
        }

        // The badge came from storage and this process has not seen the release
        // body yet, so fetch it before showing a sheet with empty notes.
        viewModelScope.launch {
            _state.value = UpdateUiState.Checking
            runCheck(settingsSnapshot, silent = false)
        }
    }

    fun closeSheet() {
        val current = _state.value
        // Keep an in-flight download: closing the sheet mid-transfer would leave a
        // progress bar with nothing behind it.
        if (current is UpdateUiState.Available && current.isDownloading) return
        _state.value = UpdateUiState.Hidden
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    fun download() {
        val available = _state.value as? UpdateUiState.Available ?: return
        viewModelScope.launch {
            _state.value = available.copy(isDownloading = true, downloadFraction = 0f, error = null)
            val result = downloader.download(viewModelScope, available.release)
            val current = _state.value as? UpdateUiState.Available ?: return@launch
            _state.value = when (result) {
                is DownloadResult.Success -> current.copy(
                    isDownloading = false,
                    downloadFraction = 1f,
                    readyToInstall = result.file,
                    readyVerified = result.verified,
                    needsInstallPermission = !installer.canInstallPackages(),
                )
                is DownloadResult.Failure ->
                    current.copy(isDownloading = false, error = result.reason)
            }
        }
    }

    fun cancelDownload() {
        downloader.cancel()
        val available = _state.value as? UpdateUiState.Available ?: return
        _state.value = available.copy(isDownloading = false, downloadFraction = null)
    }

    // ------------------------------------------------------------------
    // Install
    // ------------------------------------------------------------------

    /**
     * Launches the installer, asking for the "install unknown apps" grant first
     * when the user has not given it. Returns `true` when the installer opened.
     */
    fun install(): Boolean {
        val available = _state.value as? UpdateUiState.Available ?: return false
        val file = available.readyToInstall ?: return false

        if (!installer.canInstallPackages()) {
            installer.openInstallPermissionSettings()
            _state.value = available.copy(needsInstallPermission = true)
            return false
        }
        return installer.install(file)
    }

    /** Re-reads the permission state after the user returns from Settings. */
    fun refreshInstallPermission() {
        val available = _state.value as? UpdateUiState.Available ?: return
        _state.value = available.copy(needsInstallPermission = !installer.canInstallPackages())
    }

    // ------------------------------------------------------------------
    // Dismiss / skip
    // ------------------------------------------------------------------

    /** Hides this specific release, badge included, until a newer one appears. */
    fun skipThisVersion() {
        val tag = _badge.value?.tag ?: (lastKnownRelease?.tag ?: return)
        viewModelScope.launch {
            settingsRepository.setSkippedUpdateTag(tag)
            _state.value = UpdateUiState.Hidden
        }
    }

    fun clearSkip() {
        viewModelScope.launch { settingsRepository.setSkippedUpdateTag(null) }
    }

    fun dismissTransientState() {
        val current = _state.value
        if (current is UpdateUiState.UpToDate || current is UpdateUiState.Failed) {
            _state.value = UpdateUiState.Hidden
        }
    }

    fun clearCheckReport() {
        _checkReport.value = null
    }

    fun consumeError() {
        val available = _state.value as? UpdateUiState.Available ?: return
        _state.value = available.copy(error = null)
    }

    companion object {
        /** Do not re-check more often than this from the launch-time hook. */
        private const val CHECK_COOLDOWN_MS = 6L * 60L * 60L * 1000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as DarkVvpnApplication
                UpdateViewModel(
                    settingsRepository = app.container.settingsRepository,
                    checker = app.container.updateChecker,
                    downloader = app.container.updateDownloader,
                    installer = app.container.updateInstaller,
                )
            }
        }
    }
}
