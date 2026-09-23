package com.darkvvpn.app.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darkvvpn.app.R
import com.darkvvpn.app.data.model.VpnState
import com.darkvvpn.app.ui.components.ConnectionOrb
import com.darkvvpn.app.ui.components.CountryAvatar
import com.darkvvpn.app.ui.components.SectionHeader
import com.darkvvpn.app.ui.components.StatTile
import com.darkvvpn.app.util.Formatters
import com.darkvvpn.app.viewmodel.VpnViewModel

@Composable
fun HomeScreen(
    onNavigateToServers: () -> Unit,
    viewModel: VpnViewModel = viewModel(factory = VpnViewModel.Factory),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()
    val pendingIntent by viewModel.pendingPermissionIntent.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // VpnService.prepare() hands back an Intent that only an Activity can launch.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onPermissionGranted(context)
        } else {
            viewModel.onPermissionDenied()
        }
    }

    LaunchedEffect(pendingIntent) {
        pendingIntent?.let { permissionLauncher.launch(it) }
    }

    // Surface failures once, then clear them so a rotation doesn't re-show the bar.
    val errorMessage = when (val s = state) {
        is VpnState.Error -> s.message
        is VpnState.Disconnected -> s.lastError
        else -> null
    }
    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(errorMessage)
            viewModel.consumeError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // The hosting Scaffold already reserves space for the system bars and the
        // navigation bar, so this inner one must not reserve them a second time.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = selectedServer?.displayLocation ?: stringResource(R.string.home_no_server),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(28.dp))

            ConnectionOrb(
                state = state,
                statusLabel = statusLabel(state),
                enabled = !state.isBusy,
                onClick = {
                    if (state.isConnected) viewModel.disconnect(context) else viewModel.connect(context)
                },
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = if (state.isConnected) stringResource(R.string.home_disconnect) else stringResource(R.string.home_connect),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(28.dp))

            SectionHeader(title = stringResource(R.string.home_selected_server))

            Spacer(Modifier.height(10.dp))

            SelectedServerCard(
                name = selectedServer?.name ?: stringResource(R.string.home_no_server),
                location = selectedServer?.displayLocation ?: stringResource(R.string.home_change_server),
                countryCode = selectedServer?.countryCode ?: "??",
                ping = selectedServer?.pingMs,
                onClick = onNavigateToServers,
            )

            Spacer(Modifier.height(20.dp))

            SectionHeader(title = "Session")

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatTile(
                    icon = Icons.Filled.Download,
                    label = stringResource(R.string.home_download),
                    value = Formatters.speed(stats.downloadBytesPerSec),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    icon = Icons.Filled.Upload,
                    label = stringResource(R.string.home_upload),
                    value = Formatters.speed(stats.uploadBytesPerSec),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(10.dp))

            StatTile(
                icon = Icons.Filled.Timer,
                label = stringResource(R.string.home_duration),
                value = Formatters.duration(stats.sessionSeconds),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SelectedServerCard(
    name: String,
    location: String,
    countryCode: String,
    ping: Int?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CountryAvatar(countryCode = countryCode)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = Formatters.ping(ping),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun statusLabel(state: VpnState): String = stringResource(
    when (state) {
        is VpnState.Connected -> R.string.home_status_connected
        is VpnState.Connecting, is VpnState.AwaitingPermission -> R.string.home_status_connecting
        is VpnState.Disconnecting -> R.string.home_status_disconnecting
        else -> R.string.home_status_disconnected
    },
)
