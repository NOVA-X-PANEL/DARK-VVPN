package com.darkvvpn.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darkvvpn.app.R
import com.darkvvpn.app.ui.components.ServerRow
import com.darkvvpn.app.viewmodel.ServersViewModel

@Composable
fun ServersScreen(
    onNavigateToSubscriptions: () -> Unit,
    viewModel: ServersViewModel = viewModel(factory = ServersViewModel.Factory),
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val allServers by viewModel.allServers.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val measuring by viewModel.measuring.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedServerId.collectAsStateWithLifecycle()

    // Probe once, the first time there is something to probe. Without this the
    // list shows "—" for every node until the user finds the refresh button.
    LaunchedEffect(allServers) {
        if (allServers.isNotEmpty()) viewModel.measureOnceIfNeeded()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.servers_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            if (allServers.isNotEmpty()) {
                IconButton(
                    onClick = viewModel::measureAll,
                    enabled = !measuring,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    if (measuring) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.servers_measure),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        if (allServers.isNotEmpty()) {
            Text(
                text = stringResource(R.string.servers_count, allServers.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.servers_search_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_clear),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )

            Spacer(Modifier.height(14.dp))
        }

        when {
            // Nothing has been imported yet. Saying so, and offering the way out,
            // is the difference between an empty app and a broken one.
            allServers.isEmpty() -> EmptyState(
                icon = Icons.Filled.CloudOff,
                title = stringResource(R.string.servers_empty_title),
                body = stringResource(R.string.servers_empty_body),
                actionLabel = stringResource(R.string.servers_empty_action),
                onAction = onNavigateToSubscriptions,
            )

            // There are nodes, but the search matched none of them.
            servers.isEmpty() -> EmptyState(
                icon = Icons.Filled.Search,
                title = stringResource(R.string.servers_no_match_title),
                body = stringResource(R.string.servers_no_match_body),
                actionLabel = null,
                onAction = {},
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(items = servers, key = { it.id }) { server ->
                    ServerRow(
                        server = server,
                        selected = server.id == selectedId,
                        onClick = { viewModel.select(server) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = onAction, shape = RoundedCornerShape(14.dp)) {
                    Icon(
                        imageVector = Icons.Filled.NetworkCheck,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(actionLabel)
                }
            }
        }
    }
}
