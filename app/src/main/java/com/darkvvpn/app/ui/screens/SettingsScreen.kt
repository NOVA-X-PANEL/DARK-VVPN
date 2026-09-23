package com.darkvvpn.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darkvvpn.app.BuildConfig
import com.darkvvpn.app.R
import com.darkvvpn.app.ui.components.SectionHeader
import com.darkvvpn.app.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showRefreshIntervalDialog by remember { mutableStateOf(false) }

    // `settings` comes from a delegated property, so a null-check on one of its
    // fields cannot smart-cast inside a lambda. Capture it once instead.
    val skippedTag = settings.skippedUpdateTag

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(20.dp))

        // ---- connection ----
        SectionHeader(title = stringResource(R.string.settings_connection))
        Spacer(Modifier.height(10.dp))
        SettingsCard {
            SettingRow(
                title = stringResource(R.string.settings_auto_connect),
                subtitle = "Start the tunnel as soon as the app opens",
                checked = settings.autoConnectOnLaunch,
                onCheckedChange = viewModel::setAutoConnect,
            )
            RowDivider()
            SettingRow(
                title = stringResource(R.string.settings_kill_switch),
                subtitle = "Block traffic if the tunnel drops",
                checked = settings.killSwitch,
                onCheckedChange = viewModel::setKillSwitch,
            )
            RowDivider()
            SettingRow(
                title = "Block ads & trackers",
                subtitle = "Filter known ad hosts through the tunnel",
                checked = settings.blockAdsAndTrackers,
                onCheckedChange = viewModel::setBlockAds,
            )
            RowDivider()
            SettingRow(
                title = "Sort servers by latency",
                subtitle = "Show the fastest node first",
                checked = settings.sortServersByPing,
                onCheckedChange = viewModel::setSortByPing,
            )
        }

        Spacer(Modifier.height(20.dp))

        // ---- subscriptions ----
        SectionHeader(title = stringResource(R.string.settings_subscriptions))
        Spacer(Modifier.height(10.dp))
        SettingsCard {
            ClickableRow(
                title = stringResource(R.string.settings_sub_refresh_hours),
                value = if (settings.subscriptionRefreshHours <= 0) {
                    stringResource(R.string.settings_sub_refresh_off)
                } else {
                    stringResource(
                        R.string.settings_sub_refresh_hours_value,
                        settings.subscriptionRefreshHours,
                    )
                },
                onClick = { showRefreshIntervalDialog = true },
            )
            RowDivider()
            SettingRow(
                title = stringResource(R.string.settings_sub_wifi_only),
                subtitle = "Skip scheduled refreshes on mobile data",
                checked = settings.subscriptionRefreshOverWifiOnly,
                onCheckedChange = viewModel::setSubscriptionWifiOnly,
            )
            RowDivider()
            SettingRow(
                title = "Merge duplicate nodes",
                subtitle = "Replace a node in place instead of adding it twice",
                checked = settings.mergeDuplicateNodes,
                onCheckedChange = viewModel::setMergeDuplicateNodes,
            )
        }

        Spacer(Modifier.height(20.dp))

        // ---- updates ----
        SectionHeader(title = stringResource(R.string.settings_updates))
        Spacer(Modifier.height(10.dp))
        SettingsCard {
            SettingRow(
                title = stringResource(R.string.settings_update_on_launch),
                subtitle = "Checks quietly in the background",
                checked = settings.checkForUpdatesOnLaunch,
                onCheckedChange = viewModel::setCheckForUpdatesOnLaunch,
            )
            RowDivider()
            SettingRow(
                title = stringResource(R.string.settings_update_prerelease),
                subtitle = "Offer pre-releases as well as stable versions",
                checked = settings.allowPrereleaseUpdates,
                onCheckedChange = viewModel::setAllowPrereleaseUpdates,
            )
            if (skippedTag != null) {
                RowDivider()
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(
                            R.string.settings_update_skipped,
                            skippedTag,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = viewModel::clearSkippedUpdate) {
                        Text(stringResource(R.string.settings_update_unskip))
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- appearance ----
        SectionHeader(title = stringResource(R.string.settings_appearance))
        Spacer(Modifier.height(10.dp))
        SettingsCard {
            SettingRow(
                title = stringResource(R.string.settings_dark_theme),
                subtitle = "DARK VVPN is dark-first by design",
                checked = settings.forceDarkTheme,
                onCheckedChange = viewModel::setForceDarkTheme,
            )
            RowDivider()
            SettingRow(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = "Follow the system wallpaper (Android 12+)",
                checked = settings.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )
        }

        Spacer(Modifier.height(20.dp))

        // ---- about ----
        SectionHeader(title = stringResource(R.string.settings_about))
        Spacer(Modifier.height(10.dp))
        SettingsCard {
            InfoRow(
                label = stringResource(R.string.settings_version),
                value = BuildConfig.VERSION_NAME,
            )
            RowDivider()
            InfoRow(
                label = stringResource(R.string.settings_developer),
                value = "NOVA-X-PANEL",
            )
        }

        Spacer(Modifier.height(32.dp))
    }

    if (showRefreshIntervalDialog) {
        RefreshIntervalDialog(
            current = settings.subscriptionRefreshHours,
            onSelect = {
                viewModel.setSubscriptionRefreshHours(it)
                showRefreshIntervalDialog = false
            },
            onDismiss = { showRefreshIntervalDialog = false },
        )
    }
}

@Composable
private fun RefreshIntervalDialog(
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(0, 1, 3, 6, 12, 24, 48, 168)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_sub_refresh_hours)) },
        text = {
            Column {
                options.forEach { hours ->
                    val label = if (hours == 0) {
                        stringResource(R.string.settings_sub_refresh_off)
                    } else {
                        stringResource(R.string.settings_sub_refresh_hours_value, hours)
                    }
                    ClickableRow(
                        title = label,
                        value = if (hours == current) "✓" else "",
                        onClick = { onSelect(hours) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ClickableRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(onClick = onClick) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
