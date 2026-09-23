package com.darkvvpn.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.darkvvpn.app.R
import com.darkvvpn.app.util.Formatters
import com.darkvvpn.app.viewmodel.UpdateUiState

/**
 * The update sheet, driven by the whole [UpdateUiState].
 *
 * ── Why it takes the state rather than one branch of it ──────────────────────
 * The previous version was handed only the "there is an update" case, so the two
 * other outcomes had nowhere to go. Both were handled by closing the sheet:
 * `UpToDate` and `Failed` each triggered a dismiss, which meant tapping the update
 * banner and being already current — or losing the network — made the dialog
 * flash and vanish with no explanation. Reported, reasonably, as "the update
 * button does nothing".
 *
 * Every outcome now has a body: checking shows a spinner, being current says so,
 * and a failure shows the reason **with the reason selectable** plus a Retry.
 * Nothing closes itself.
 */
@Composable
fun UpdateDialog(
    state: UpdateUiState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    // A download in flight must not be dismissed by a stray tap outside.
    val dismissable = when (state) {
        is UpdateUiState.Available -> !state.isDownloading
        else -> true
    }

    AlertDialog(
        onDismissRequest = { if (dismissable) onDismiss() },
        title = { Text(text = titleFor(state), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (state) {
                    is UpdateUiState.Checking -> CheckingBody()
                    is UpdateUiState.UpToDate -> UpToDateBody(state.currentVersion)
                    is UpdateUiState.Failed -> FailedBody(state.reason)
                    is UpdateUiState.Available -> AvailableBody(
                        state = state,
                        onOpenPermissionSettings = onOpenPermissionSettings,
                    )
                    is UpdateUiState.Hidden -> Unit // never rendered
                }
            }
        },
        confirmButton = {
            ConfirmAction(
                state = state,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
                onInstall = onInstall,
                onOpenPermissionSettings = onOpenPermissionSettings,
                onRetry = onRetry,
            )
        },
        dismissButton = {
            DismissAction(state = state, onSkip = onSkip, onDismiss = onDismiss)
        },
    )
}

@Composable
private fun titleFor(state: UpdateUiState): String = when (state) {
    is UpdateUiState.Checking -> stringResource(R.string.update_checking)
    is UpdateUiState.UpToDate -> stringResource(R.string.update_uptodate_title)
    is UpdateUiState.Failed -> stringResource(R.string.update_check_failed_title)
    is UpdateUiState.Available -> stringResource(R.string.update_available_title)
    is UpdateUiState.Hidden -> ""
}

// ---------------------------------------------------------------------------
// Bodies
// ---------------------------------------------------------------------------

@Composable
private fun CheckingBody() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.update_checking_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UpToDateBody(currentVersion: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = stringResource(R.string.update_uptodate, currentVersion),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The reason a check failed, selectable so it can be copied out.
 *
 * GitHub's unauthenticated API allows 60 requests an hour per IP, which a shared
 * mobile network can exhaust on its own. When that happens the app used to show
 * nothing at all; now it says so and offers a retry.
 */
@Composable
private fun FailedBody(reason: String) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            SelectionContainer {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.update_check_failed_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AvailableBody(
    state: UpdateUiState.Available,
    onOpenPermissionSettings: () -> Unit,
) {
    val release = state.release

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        VersionCell(
            label = stringResource(R.string.update_installed),
            value = state.installedVersion,
            modifier = Modifier.weight(1f),
        )
        VersionCell(
            label = stringResource(R.string.update_latest),
            value = release.tag,
            highlight = true,
            modifier = Modifier.weight(1f),
        )
    }

    if (release.name.isNotBlank() && release.name != release.tag) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = release.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (release.apkSizeBytes != null && release.apkSizeBytes > 0L) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = Formatters.bytes(release.apkSizeBytes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (release.notes.isNotBlank()) {
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.update_release_notes).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Text(
                text = release.notes.trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(12.dp)
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }

    if (state.isDownloading) {
        Spacer(Modifier.height(14.dp))
        val percent = state.downloadFraction?.let { (it * 100f).toInt() }
        Text(
            text = if (percent != null) {
                stringResource(R.string.update_downloading, percent)
            } else {
                Formatters.bytes(state.downloadedBytes)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        if (state.downloadFraction != null) {
            LinearProgressIndicator(
                progress = { state.downloadFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )
        }
        val total = state.totalBytes ?: release.apkSizeBytes
        if (total != null && total > 0L) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${Formatters.bytes(state.downloadedBytes)} / ${Formatters.bytes(total)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (state.readyToInstall != null) {
        Spacer(Modifier.height(12.dp))
        NoticeRow(
            text = if (state.readyVerified) {
                stringResource(R.string.update_verified)
            } else {
                stringResource(R.string.update_unverified)
            },
            isWarning = !state.readyVerified,
        )
    }

    if (state.needsInstallPermission) {
        Spacer(Modifier.height(10.dp))
        NoticeRow(
            text = stringResource(R.string.update_need_permission),
            isWarning = true,
        )
    }

    state.error?.let { message ->
        Spacer(Modifier.height(12.dp))
        SelectionContainer {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

@Composable
private fun ConfirmAction(
    state: UpdateUiState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    Row {
        when (state) {
            // A retry is the only useful action when the check itself failed.
            is UpdateUiState.Failed -> Button(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_retry))
            }

            // Nothing to do but close; the Close button handles that, so this is
            // empty rather than a disabled button.
            is UpdateUiState.Checking, is UpdateUiState.UpToDate -> Unit

            is UpdateUiState.Available -> when {
                state.isDownloading -> TextButton(onClick = onCancelDownload) {
                    Text(stringResource(R.string.action_cancel))
                }

                state.readyToInstall != null -> Button(onClick = {
                    // Ask for the grant first when it is missing, rather than
                    // launching an installer that the system will refuse.
                    if (state.needsInstallPermission) onOpenPermissionSettings else onInstall()
                }) {
                    Text(stringResource(R.string.update_install_now))
                }

                else -> Button(onClick = onDownload) {
                    Icon(
                        imageVector = Icons.Filled.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.update_download))
                }
            }

            is UpdateUiState.Hidden -> Unit
        }
    }
}

@Composable
private fun DismissAction(
    state: UpdateUiState,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row {
        // Skipping only makes sense when there is a release to skip.
        if (state is UpdateUiState.Available && !state.isDownloading && state.readyToInstall == null) {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.update_skip)) }
        }
        TextButton(onClick = onDismiss) {
            Text(
                stringResource(
                    if (state is UpdateUiState.Available) {
                        R.string.update_later
                    } else {
                        R.string.action_close
                    },
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Small pieces
// ---------------------------------------------------------------------------

@Composable
private fun VersionCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (highlight) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NoticeRow(text: String, isWarning: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = if (isWarning) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.secondary
            },
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isWarning) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
