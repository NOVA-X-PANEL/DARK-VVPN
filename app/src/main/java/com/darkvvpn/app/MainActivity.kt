package com.darkvvpn.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.darkvvpn.app.navigation.DarkVvpnNavHost
import com.darkvvpn.app.navigation.Routes
import com.darkvvpn.app.navigation.TopLevelDestination
import com.darkvvpn.app.ui.components.UpdateDialog
import com.darkvvpn.app.ui.theme.DarkVvpnTheme
import com.darkvvpn.app.util.NetworkState
import com.darkvvpn.app.viewmodel.SettingsViewModel
import com.darkvvpn.app.viewmodel.SubscriptionsViewModel
import com.darkvvpn.app.viewmodel.UpdateUiState
import com.darkvvpn.app.viewmodel.UpdateViewModel
import com.darkvvpn.app.viewmodel.VpnViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

            DarkVvpnTheme(
                darkTheme = settings.forceDarkTheme,
                dynamicColor = settings.dynamicColor,
            ) {
                DarkVvpnApp(initialPayload = sharePayloadFrom(intent))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A share link arriving while the app is already open: handled on the
        // next composition by reading the new intent, which `setIntent` makes
        // visible to `sharePayloadFrom`.
        setIntent(intent)
    }

    /**
     * Extracts a share link or subscription URL from a `VIEW` intent.
     *
     * The scheme is checked against the allow-list in the manifest rather than
     * trusted, so a link with an unexpected scheme (including `file:`) can never
     * reach the import sheet.
     */
    private fun sharePayloadFrom(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        if (data.scheme?.lowercase() !in ALLOWED_IMPORT_SCHEMES) return null
        return data.toString().take(MAX_PAYLOAD_CHARS)
    }

    private companion object {
        val ALLOWED_IMPORT_SCHEMES = setOf(
            "darkvvpn", "vless", "vmess", "trojan", "ss", "hysteria2", "http", "https",
        )

        /** A share link is a few hundred bytes; this only bounds a hostile input. */
        const val MAX_PAYLOAD_CHARS = 8_192
    }
}

@Composable
private fun DarkVvpnApp(initialPayload: String?) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val updateViewModel: UpdateViewModel = viewModel(factory = UpdateViewModel.Factory)
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()

    // The VPN ViewModel is read here only to run its launch-time auto-connect.
    val vpnViewModel: VpnViewModel = viewModel(factory = VpnViewModel.Factory)

    // Instantiated at launch, not when the Subscriptions tab is first opened, so
    // the cached node list is restored into the catalogue before the user can
    // reach the Servers screen. Skipping this is what left an imported
    // subscription showing an empty server list.
    viewModel(factory = SubscriptionsViewModel.Factory)

    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    // Android 13+ needs runtime consent before the VPN notification can be shown.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* The service degrades gracefully when denied. */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Launch-time work, each gated on its own setting.
    LaunchedEffect(settings.autoConnectOnLaunch, settings.checkForUpdatesOnLaunch) {
        if (settings.checkForUpdatesOnLaunch) updateViewModel.checkOnLaunch()
        if (settings.autoConnectOnLaunch) vpnViewModel.connect(context)
    }

    // Connectivity can change while the app is in the background; re-read it so
    // the "Wi-Fi only" subscription preference is evaluated on fresh data.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                NetworkState.refresh(context)
                // The user may have granted install permission in Settings.
                updateViewModel.refreshInstallPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val showBottomBar = currentRoute != null && currentRoute != Routes.SPLASH

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    TopLevelDestination.entries.forEach { destination ->
                        val label = stringResource(destination.labelRes)
                        val selected = currentRoute?.substringBefore('?') == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = label,
                                )
                            },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            DarkVvpnNavHost(
                navController = navController,
                startDestination = Routes.SPLASH,
            )
        }
    }

    // ---- update sheet ----
    when (val state = updateState) {
        is UpdateUiState.Available -> UpdateDialog(
            releaseTag = state.release.tag,
            releaseName = state.release.name,
            releaseNotes = state.release.notes,
            installedVersion = state.installedVersion,
            apkSizeBytes = state.release.apkSizeBytes,
            isDownloading = state.isDownloading,
            downloadFraction = state.downloadFraction,
            downloadedBytes = state.downloadedBytes,
            totalBytes = state.totalBytes ?: state.release.apkSizeBytes,
            readyToInstall = state.readyToInstall != null,
            readyVerified = state.readyVerified,
            needsInstallPermission = state.needsInstallPermission,
            error = state.error,
            onDownload = updateViewModel::download,
            onCancelDownload = updateViewModel::cancelDownload,
            onInstall = { updateViewModel.install() },
            onOpenPermissionSettings = {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:${context.packageName}"),
                    ),
                )
            },
            onSkip = updateViewModel::skipThisVersion,
            onDismiss = updateViewModel::dismiss,
        )

        is UpdateUiState.UpToDate -> LaunchedEffect(state) {
            // A manual check reports success through the Settings screen, which
            // shows its own confirmation; nothing to draw here.
            updateViewModel.dismissTransientState()
        }

        is UpdateUiState.Failed -> LaunchedEffect(state) {
            updateViewModel.dismissTransientState()
        }

        else -> Unit
    }

    // An import arriving via intent is handled by the Subscriptions screen; keep
    // the first payload in memory so a recomposition does not lose it.
    LaunchedEffect(initialPayload) {
        if (!initialPayload.isNullOrBlank()) {
            val encoded = java.net.URLEncoder.encode(initialPayload, "UTF-8")
            navController.navigate("${Routes.SUBSCRIPTIONS}?payload=$encoded")
        }
    }
}
