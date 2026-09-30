package com.darkvvpn.app.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darkvvpn.app.R
import com.darkvvpn.app.data.model.Subscription
import com.darkvvpn.app.data.model.VpnServer
import com.darkvvpn.app.data.model.VpnState
import com.darkvvpn.app.ui.components.ConnectionFailureCard
import com.darkvvpn.app.ui.components.CountryAvatar
import com.darkvvpn.app.ui.components.IosSwitch
import com.darkvvpn.app.ui.components.PingBadge
import com.darkvvpn.app.ui.components.ProtocolChip
import com.darkvvpn.app.ui.components.YellowUpdateBadge
import com.darkvvpn.app.ui.theme.BrandBlue
import com.darkvvpn.app.ui.theme.BrandGreen
import com.darkvvpn.app.util.Formatters
import com.darkvvpn.app.viewmodel.ServersViewModel
import com.darkvvpn.app.viewmodel.SubscriptionsViewModel
import com.darkvvpn.app.viewmodel.UpdateUiState
import com.darkvvpn.app.viewmodel.UpdateViewModel
import com.darkvvpn.app.viewmodel.VpnViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Streisand iOS-style Home Screen for DARK-VVPN.
 * Clean, standard, uncluttered: Master Connection Card with iOS switch,
 * 3-way routing segmented control, subscription quota tracker, and inset grouped server list.
 */
@Composable
fun HomeScreen(
    onNavigateToServers: () -> Unit,
    onNavigateToSubscriptions: () -> Unit = {},
    updateViewModel: UpdateViewModel? = null,
    viewModel: VpnViewModel = viewModel(factory = VpnViewModel.Factory),
    serversViewModel: ServersViewModel = viewModel(factory = ServersViewModel.Factory),
    subscriptionsViewModel: SubscriptionsViewModel = viewModel(factory = SubscriptionsViewModel.Factory),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()
    val pendingIntent by viewModel.pendingPermissionIntent.collectAsStateWithLifecycle()
    val updateBadge by (updateViewModel?.badge ?: MutableStateFlow(null)).collectAsStateWithLifecycle()
    val updateState by (updateViewModel?.state ?: MutableStateFlow(UpdateUiState.Hidden)).collectAsStateWithLifecycle()

    val servers by serversViewModel.servers.collectAsStateWithLifecycle()
    val selectedServerId by serversViewModel.selectedServerId.collectAsStateWithLifecycle()
    val subscriptions by subscriptionsViewModel.subscriptions.collectAsStateWithLifecycle()

    val failure by viewModel.failure.collectAsStateWithLifecycle()

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

    LaunchedEffect(Unit) {
        subscriptionsViewModel.refreshAll()
        updateViewModel?.checkOnLaunch()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))

            // ---- Top Navigation Bar (Streisand Style) ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.home_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )

                    if (updateBadge != null || updateState is UpdateUiState.Available) {
                        YellowUpdateBadge(
                            badge = updateBadge,
                            state = updateState,
                            onClick = { updateViewModel?.onBadgeClicked() },
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = BrandBlue.copy(alpha = 0.15f),
                            border = BorderStroke(0.5.dp, BrandBlue.copy(alpha = 0.35f)),
                            modifier = Modifier.clickable { updateViewModel?.check() },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                if (updateState is UpdateUiState.Checking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(10.dp),
                                        strokeWidth = 1.5.dp,
                                        color = BrandBlue,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    text = "v${com.darkvvpn.app.BuildConfig.VERSION_NAME}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = BrandBlue,
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { serversViewModel.measureAll() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = stringResource(R.string.home_test_ping),
                            tint = BrandBlue,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable(onClick = onNavigateToSubscriptions),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.home_add_subscription),
                            tint = BrandBlue,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            failure?.let { message ->
                Spacer(Modifier.height(10.dp))
                ConnectionFailureCard(
                    message = message,
                    onDismiss = viewModel::dismissFailure,
                )
            }

            Spacer(Modifier.height(10.dp))

            // ---- 1. Master Connection Card (Streisand Signature) ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (state.isConnected) BrandBlue.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val dotColor = when {
                                state.isConnected -> BrandGreen
                                state.isBusy -> BrandBlue
                                else -> MaterialTheme.colorScheme.outline
                            }
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                            Text(
                                text = statusLabel(state),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        val subLabel = when {
                            state.isConnected -> selectedServer?.let { "${it.name} • ${Formatters.duration(stats.sessionSeconds)}" }
                                ?: stringResource(R.string.home_session_duration, Formatters.duration(stats.sessionSeconds))
                            else -> selectedServer?.name ?: stringResource(R.string.home_no_server)
                        }
                        Text(
                            text = subLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        if (state.isConnected) {
                            Row(
                                modifier = Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "⬇ " + Formatters.speed(stats.downloadBytesPerSec),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BrandGreen,
                                )
                                Text(
                                    text = "⬆ " + Formatters.speed(stats.uploadBytesPerSec),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BrandBlue,
                                )
                            }
                        }
                    }

                    IosSwitch(
                        checked = state.isConnected,
                        enabled = !state.isBusy,
                        activeColor = BrandGreen,
                        onCheckedChange = {
                            if (state.isConnected) viewModel.disconnect(context) else viewModel.connect(context)
                        },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- 2. Routing Mode Segmented Control (Streisand Hallmark) ----
            var selectedRoutingIndex by remember { mutableIntStateOf(0) }
            val routingModes = listOf(
                stringResource(R.string.home_routing_rule),
                stringResource(R.string.home_routing_global),
                stringResource(R.string.home_routing_direct),
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_routing_mode),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        routingModes.forEachIndexed { index, modeTitle ->
                            val isSelected = index == selectedRoutingIndex
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        if (isSelected) BrandBlue.copy(alpha = 0.22f) else Color.Transparent
                                    )
                                    .clickable { selectedRoutingIndex = index }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = modeTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) BrandBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- 3. Grouped Subscriptions & Server Lists (Streisand Signature) ----
            val groupedServers: Map<Subscription?, List<VpnServer>> = remember(subscriptions, servers) {
                if (subscriptions.isEmpty()) {
                    mapOf(null to servers)
                } else if (subscriptions.size == 1) {
                    mapOf(subscriptions.first() to servers)
                } else {
                    val subMap = subscriptions.associateBy { it.id }
                    val map = linkedMapOf<Subscription?, MutableList<VpnServer>>()
                    for (sub in subscriptions) {
                        map[sub] = mutableListOf()
                    }
                    for (server in servers) {
                        val sub = server.subscriptionId?.let { subMap[it] }
                        map.getOrPut(sub) { mutableListOf() }.add(server)
                    }
                    map
                }
            }

            if (subscriptions.isEmpty() && servers.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToSubscriptions)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.servers_empty_action),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = BrandBlue,
                        )
                    }
                }
            } else {
                groupedServers.forEach { (sub, subServers) ->
                    if (sub != null) {
                        // Section Header for this Subscription
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val subTitle = sub.name.ifBlank {
                                try {
                                    android.net.Uri.parse(sub.url).host.orEmpty()
                                } catch (_: Exception) { "" }
                            }.ifBlank { stringResource(R.string.nav_subs) }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = subTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = BrandBlue.copy(alpha = 0.12f),
                                ) {
                                    Text(
                                        text = stringResource(R.string.home_nodes_count, subServers.size),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = BrandBlue,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.home_test_ping),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = BrandBlue,
                                    modifier = Modifier.clickable { serversViewModel.measureAll() },
                                )
                                Text(
                                    text = stringResource(R.string.subs_refresh),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = BrandBlue,
                                    modifier = Modifier.clickable { subscriptionsViewModel.refresh(sub.id) },
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Subscription Traffic Tracker Card
                        SubscriptionTrafficCard(
                            sub = sub,
                            nodeCount = subServers.size,
                            onClick = {
                                subscriptionsViewModel.refresh(sub.id)
                                onNavigateToSubscriptions()
                            },
                        )

                        Spacer(Modifier.height(10.dp))

                        // Inset Grouped Server List Card for this subscription
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                if (subServers.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.servers_empty),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    subServers.forEachIndexed { index, server ->
                                        val isSelected = server.id == (selectedServer?.id ?: selectedServerId)
                                        StreisandServerRow(
                                            server = server,
                                            selected = isSelected,
                                            onClick = { serversViewModel.select(server) },
                                        )
                                        if (index < subServers.lastIndex) {
                                            HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outlineVariant,
                                                thickness = 0.5.dp,
                                                modifier = Modifier.padding(start = 48.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(18.dp))
                    } else if (subServers.isNotEmpty()) {
                        // Orphan / manual servers
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.servers_title) + " (" + stringResource(R.string.home_nodes_count, subServers.size) + ")",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.home_test_ping),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = BrandBlue,
                                modifier = Modifier.clickable { serversViewModel.measureAll() },
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                subServers.forEachIndexed { index, server ->
                                    val isSelected = server.id == (selectedServer?.id ?: selectedServerId)
                                    StreisandServerRow(
                                        server = server,
                                        selected = isSelected,
                                        onClick = { serversViewModel.select(server) },
                                    )
                                    if (index < subServers.lastIndex) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                            thickness = 0.5.dp,
                                            modifier = Modifier.padding(start = 48.dp),
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StreisandServerRow(
    server: VpnServer,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) BrandBlue.copy(alpha = 0.08f) else Color.Transparent
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // iOS radio selection indicator
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(
                    width = 2.dp,
                    color = if (selected) BrandBlue else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                )
                .background(if (selected) BrandBlue else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        CountryAvatar(countryCode = server.countryCode, size = 32.dp)

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.5.sp,
                    lineHeight = 18.sp,
                    textDirection = TextDirection.Content,
                ),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProtocolChip(protocol = server.protocol)
                Text(
                    text = server.displayLocation,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.5.sp,
                        textDirection = TextDirection.Content,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        PingBadge(pingMs = server.pingMs)
    }
}

@Composable
private fun SubscriptionTrafficCard(
    sub: Subscription,
    nodeCount: Int,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val hostName = try {
                    android.net.Uri.parse(sub.url).host.orEmpty()
                } catch (_: Exception) { "" }.ifBlank { sub.name }

                Text(
                    text = hostName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = BrandGreen.copy(alpha = 0.15f),
                ) {
                    val isUnlimitedTime = sub.expiresAtEpochMillis == null ||
                        sub.expiresAtEpochMillis <= 0L ||
                        sub.expiresAtEpochMillis > (System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 5)

                    val daysText = when {
                        isUnlimitedTime -> stringResource(R.string.home_time_unlimited)
                        else -> {
                            val days = ((sub.expiresAtEpochMillis - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)).coerceAtLeast(0)
                            stringResource(R.string.home_days_remaining, days)
                        }
                    }
                    Text(
                        text = daysText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = BrandGreen,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            val hasQuota = sub.totalBytes != null && sub.totalBytes > 0L
            if (hasQuota) {
                val progress = sub.quotaUsedFraction ?: 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = BrandBlue,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val usedStr = sub.usedBytes?.let { Formatters.bytes(it) }
                val totalStr = sub.totalBytes?.let { Formatters.bytes(it) }

                val quotaText = when {
                    !hasQuota -> {
                        if (usedStr != null) {
                            stringResource(R.string.home_quota_used_unlimited, usedStr)
                        } else {
                            stringResource(R.string.home_quota_unlimited)
                        }
                    }
                    else -> stringResource(R.string.subs_quota, usedStr ?: "0 B", totalStr ?: "—")
                }
                Text(
                    text = quotaText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.home_nodes_count, nodeCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
