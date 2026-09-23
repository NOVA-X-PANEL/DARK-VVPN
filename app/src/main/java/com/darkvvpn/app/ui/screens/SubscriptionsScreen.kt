package com.darkvvpn.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darkvvpn.app.R
import com.darkvvpn.app.data.model.Subscription
import com.darkvvpn.app.ui.components.SectionHeader
import com.darkvvpn.app.util.Formatters
import com.darkvvpn.app.viewmodel.SubscriptionsViewModel

@Composable
fun SubscriptionsScreen(
    prefillPayload: String? = null,
    viewModel: SubscriptionsViewModel = viewModel(factory = SubscriptionsViewModel.Factory),
) {
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val refreshingIds by viewModel.refreshingIds.collectAsStateWithLifecycle()
    val importState by viewModel.import.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val nodeCount by viewModel.nodeCount.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var dialogMode by remember { mutableStateOf(ImportDialogMode.Hidden) }
    var pendingDelete by remember { mutableStateOf<Subscription?>(null) }

    // The startup refresh lives in the ViewModel's init, so it has already run by
    // the time this screen exists — doing it here as well would fetch twice.

    // A link opened from another app is offered for import straight away.
    LaunchedEffect(prefillPayload) {
        if (!prefillPayload.isNullOrBlank()) {
            viewModel.onImportUrlChange(prefillPayload)
            dialogMode = if (prefillPayload.contains("://") &&
                looksLikeShareLink(prefillPayload)
            ) {
                ImportDialogMode.Links
            } else {
                ImportDialogMode.Url
            }
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it.text)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { dialogMode = ImportDialogMode.Url },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.subs_add),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.subs_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = viewModel::refreshAll,
                    enabled = refreshingIds.isEmpty() && subscriptions.isNotEmpty(),
                ) {
                    if (refreshingIds.isNotEmpty()) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.subs_refresh_all),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Text(
                text = if (nodeCount == 0) {
                    stringResource(R.string.subs_no_nodes)
                } else {
                    stringResource(R.string.subs_node_count, nodeCount)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { dialogMode = ImportDialogMode.Links },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Link,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.subs_import_links))
            }

            Spacer(Modifier.height(18.dp))

            if (subscriptions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.subs_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items = subscriptions, key = { it.id }) { subscription ->
                        SubscriptionRow(
                            subscription = subscription,
                            isRefreshing = subscription.id in refreshingIds,
                            onRefresh = { viewModel.refresh(subscription.id) },
                            onToggleEnabled = { viewModel.toggleEnabled(subscription) },
                            onToggleAuto = { viewModel.toggleAutoUpdate(subscription) },
                            onDelete = { pendingDelete = subscription },
                        )
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }

    when (val mode = dialogMode) {
        ImportDialogMode.Hidden -> Unit

        ImportDialogMode.Links -> ImportLinksDialog(
            initialText = importState.urlInput,
            onDismiss = { dialogMode = ImportDialogMode.Hidden },
            onSubmit = { text ->
                viewModel.onImportUrlChange(text)
                viewModel.importCurrentInput()
                dialogMode = ImportDialogMode.Hidden
            },
        )

        ImportDialogMode.Url -> ImportSubscriptionDialog(
            state = importState,
            onUrlChange = viewModel::onImportUrlChange,
            onNameChange = viewModel::onImportNameChange,
            onAutoUpdateChange = viewModel::onImportAutoUpdateChange,
            onPreview = { viewModel.preview(importState.urlInput) },
            onConfirm = {
                viewModel.importCurrentInput()
                dialogMode = ImportDialogMode.Hidden
            },
            onDismiss = {
                viewModel.clearImport()
                dialogMode = ImportDialogMode.Hidden
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.subs_delete_title)) },
            text = { Text(stringResource(R.string.subs_delete_body, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(target.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private enum class ImportDialogMode { Hidden, Url, Links }

/** True when the payload is a node link rather than a subscription endpoint. */
private fun looksLikeShareLink(payload: String): Boolean =
    listOf("vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://", "socks://")
        .any { payload.trim().startsWith(it, ignoreCase = true) }

@Composable
private fun SubscriptionRow(
    subscription: Subscription,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onToggleEnabled: () -> Unit,
    onToggleAuto: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = subscription.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subscription.url,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.subs_refresh),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.action_remove),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            val status = when {
                subscription.hasError -> subscription.lastError.orEmpty()
                subscription.lastUpdatedEpochMillis != null ->
                    stringResource(
                        R.string.subs_status_ok,
                        subscription.lastNodeCount,
                        subscription.format.label,
                    )
                else -> stringResource(R.string.subs_status_never)
            }

            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = if (subscription.hasError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Quota bar, when the provider reports one.
            val fraction = subscription.quotaUsedFraction
            if (fraction != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = if (fraction > 0.9f) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.subs_quota,
                        Formatters.bytes(subscription.usedBytes ?: 0L),
                        Formatters.bytes(subscription.totalBytes ?: 0L),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.subs_enabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = subscription.enabled, onCheckedChange = { onToggleEnabled() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.subs_auto_update),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = subscription.autoUpdate, onCheckedChange = { onToggleAuto() })
            }
        }
    }
}

@Composable
private fun ImportLinksDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    // Keyed to the incoming text so a link opened from another app is prefilled.
    var text by remember(initialText) { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subs_import_links_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.subs_import_links_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    placeholder = { Text("vless://…\nvmess://…") },
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(text) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.action_import)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ImportSubscriptionDialog(
    state: com.darkvvpn.app.viewmodel.ImportState,
    onUrlChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onAutoUpdateChange: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subs_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = state.urlInput,
                    onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.subs_url_label)) },
                    placeholder = { Text("https://panel.example/sub?token=…") },
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.nameInput,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.subs_name_label)) },
                    placeholder = { Text(stringResource(R.string.subs_name_optional)) },
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.subs_auto_update),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = state.autoUpdate, onCheckedChange = onAutoUpdateChange)
                }

                state.error?.let { error ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (state.preview.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            R.string.subs_preview,
                            state.preview.size,
                            state.previewFormat ?: "",
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    state.preview.take(4).forEach { node ->
                        Text(
                            text = "• ${node.name}  (${node.protocol.label})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.preview.size > 4) {
                        Text(
                            text = stringResource(R.string.subs_preview_more, state.preview.size - 4),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (state.isWorking) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = state.canImport,
            ) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = {
            Row {
                if (state.urlInput.isNotBlank() && state.preview.isEmpty()) {
                    TextButton(onClick = onPreview, enabled = !state.isWorking) {
                        Text(stringResource(R.string.action_test))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
