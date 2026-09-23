package com.darkvvpn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.darkvvpn.app.R
import com.darkvvpn.app.ui.theme.BrandAmber
import com.darkvvpn.app.ui.theme.BrandAmberDeep
import com.darkvvpn.app.ui.theme.InkOnAmber

/**
 * The standing "an update is ready" notice.
 *
 * Deliberately amber and deliberately not a dialog. Violet is this app's colour
 * for everything, so an update announced in violet would read as decoration;
 * amber is used for nothing else, which is what makes the banner scan at a
 * glance. And a banner can be ignored, which a modal cannot — an update is
 * information, not an obstacle.
 */
@Composable
fun UpdateBanner(
    version: String,
    isPrerelease: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BrandAmber.copy(alpha = 0.14f))
            .border(1.dp, BrandAmberDeep.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(50))
                .background(BrandAmber),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.SystemUpdateAlt,
                contentDescription = null,
                tint = InkOnAmber,
                modifier = Modifier.size(19.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.update_banner_title, version),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = BrandAmber,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (isPrerelease) {
                    stringResource(R.string.update_banner_body_prerelease)
                } else {
                    stringResource(R.string.update_banner_body)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.width(12.dp))

        // A tinted chip, not a Material3 Button.
        //
        // The whole row is already clickable, so a nested Button would be a second
        // tap target inside the first — an accessibility smell, and the reason the
        // chip's own onClick is deliberately absent. It is drawn as a pill so it
        // still reads as the thing to press, which is what the earlier plain amber
        // `Text` failed to do.
        //
        // Material3's Button machinery is also what made the render tests hang:
        // composing it here left Compose "not idle after 9,490,639 attempts",
        // i.e. it never settled. Its ripple and interaction plumbing is more than
        // a static label inside a row needs.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(BrandAmber)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.update_banner_action),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = InkOnAmber,
            )
        }
    }
}

/**
 * A small amber dot for the tab icon.
 *
 * No number: the badge means "there is something newer", and a count would imply
 * there are several. Drawn rather than using `Badge` so it can carry the same
 * amber as the banner and stay legible at 8dp.
 */
@Composable
fun UpdateDot(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 9.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(BrandAmber),
    )
}

/** A row that reports the outcome of a manual check, in amber or grey. */
@Composable
fun UpdateCheckReport(
    message: String,
    isNotice: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isNotice) BrandAmber else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
