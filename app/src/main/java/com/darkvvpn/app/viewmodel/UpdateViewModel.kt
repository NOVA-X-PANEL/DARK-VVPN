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
 * Drives the in-app update flow: check, download, verify, install.
 *
 * ── The launch-time check must be invisible ───────────────────────────────────
 * [checkOnLaunch] only opens the sheet when there is something to offer. A
 * network failure, a rate limit, or "no releases yet" all resolve to
 * [UpdateUiState.Hidden], because a failing update check is not something the
 * user asked about. A manual [check] always reports its outcome.
 */
class UpdateViewModel(
    private val settingsRepository: SettingsRepository,
    private val checker: UpdateChecker,
    private val downloader: UpdateDownloader,
    private val installer: UpdateInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Hidden)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    val downloadState: StateFlow<DownloadState> = downloader.state

    /** Set when the user presses "Later", so the sheet does not reopen this session. */
    private var dismissedThisSession = false

    // ------------------------------------------------------------------
    // Check
    // ------------------------------------------------------------------

    /** Silent check for the app start. Never surfaces a failure. */
    fun checkOnLaunch() {
        if (dismissedThisSession) return
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
            _state.value = UpdateUiState.Checking
            runCheck(settingsRepository.settings.first(), silent = false)
        }
    }

    private suspend fun runCheck(settings: AppSettings, silent: Boolean) {
        val result = withContextIo {
            checker.check(
                allowPrerelease = settings.allowPrereleaseUpdates,
                skippedTag = settings.skippedUpdateTag,
            )
        }

        settingsRepository.setLastUpdateCheck(System.currentTimeMillis())

        _state.value = when (result) {
            is UpdateCheckResult.UpdateAvailable -> {
                val installed = installer.installedVersionName() ?: BuildConfig.VERSION_NAME
                UpdateUiState.Available(
                    release = result.release,
                    installedVersion = installed,
                    needsInstallPermission = !installer.canInstallPackages(),
                )
            }

            is UpdateCheckResult.UpToDate -> {
                if (silent) UpdateUiState.Hidden
                else UpdateUiState.UpToDate(BuildConfig.VERSION_NAME)
            }

            is UpdateCheckResult.Failed -> {
                if (silent) UpdateUiState.Hidden else UpdateUiState.Failed(result.reason)
            }
        }
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
    // Dismiss
    // ------------------------------------------------------------------

    fun dismiss() {
        dismissedThisSession = true
        _state.value = UpdateUiState.Hidden
    }

    /** Hides this specific release until a newer one appears. */
    fun skipThisVersion() {
        val available = _state.value as? UpdateUiState.Available ?: return
        viewModelScope.launch { settingsRepository.setSkippedUpdateTag(available.release.tag) }
        dismissedThisSession = true
        _state.value = UpdateUiState.Hidden
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

    fun consumeError() {
        val available = _state.value as? UpdateUiState.Available ?: return
        _state.value = available.copy(error = null)
    }

    // ------------------------------------------------------------------

    private suspend fun <T> withContextIo(block: () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

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
