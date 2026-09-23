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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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

/**
 * The update prompt.
 *
 * Two states in one dialog: before the download (version comparison, notes,
 * Download) and after (integrity status, Install). Keeping it one dialog means
 * the user never loses the context of what they are installing, and the release
 * notes stay visible through the download.
 */
@Composable
fun UpdateDialog(
    releaseTag: String,
    releaseName: String,
    releaseNotes: String,
    installedVersion: String,
    apkSizeBytes: Long?,
    isDownloading: Boolean,
    downloadFraction: Float?,
    downloadedBytes: Long,
    totalBytes: Long?,
    readyToInstall: Boolean,
    readyVerified: Boolean,
    needsInstallPermission: Boolean,
    error: String?,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = {
            Text(
                text = stringResource(R.string.update_available_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    VersionCell(
                        label = stringResource(R.string.update_installed),
                        value = installedVersion,
                        modifier = Modifier.weight(1f),
                    )
                    VersionCell(
                        label = stringResource(R.string.update_latest),
                        value = releaseTag,
                        highlight = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (releaseName.isNotBlank() && releaseName != releaseTag) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = releaseName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (apkSizeBytes != null && apkSizeBytes > 0L) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = Formatters.bytes(apkSizeBytes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (releaseNotes.isNotBlank()) {
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
                            text = releaseNotes.trim(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .padding(12.dp)
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }

                // ---- download progress ----
                if (isDownloading) {
                    Spacer(Modifier.height(14.dp))
                    val label = downloadFraction?.let {
                        (it * 100f).toInt()
                    }
                    Text(
                        text = if (label != null) {
                            stringResource(R.string.update_downloading, label)
                        } else {
                            Formatters.bytes(downloadedBytes)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (downloadFraction != null) {
                        LinearProgressIndicator(
                            progress = { downloadFraction },
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
                    if (totalBytes != null && totalBytes > 0L) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${Formatters.bytes(downloadedBytes)} / ${Formatters.bytes(totalBytes)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ---- integrity + permission notices ----
                if (readyToInstall) {
                    Spacer(Modifier.height(12.dp))
                    NoticeRow(
                        text = if (readyVerified) {
                            stringResource(R.string.update_verified)
                        } else {
                            stringResource(R.string.update_unverified)
                        },
                        isWarning = !readyVerified,
                    )
                }

                if (needsInstallPermission) {
                    Spacer(Modifier.height(10.dp))
                    NoticeRow(
                        text = stringResource(R.string.update_need_permission),
                        isWarning = true,
                    )
                }

                error?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                if (needsInstallPermission) {
                    TextButton(onClick = onOpenPermissionSettings) {
                        Text(stringResource(R.string.update_open_settings))
                    }
                    Spacer(Modifier.width(4.dp))
                }

                when {
                    isDownloading -> TextButton(onClick = onCancelDownload) {
                        Text(stringResource(R.string.action_cancel))
                    }

                    readyToInstall -> TextButton(onClick = onInstall) {
                        Text(
                            text = stringResource(R.string.update_install_now),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    else -> TextButton(onClick = onDownload) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.update_download),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (!isDownloading && !readyToInstall) {
                Row {
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.update_skip)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
                }
            }
        },
    )
}

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
