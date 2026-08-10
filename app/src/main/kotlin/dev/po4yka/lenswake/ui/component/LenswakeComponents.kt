package dev.po4yka.lenswake.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.core.SetupRemediationAction
import dev.po4yka.lenswake.ui.CapabilityStatus
import dev.po4yka.lenswake.ui.CapabilityUiState
import dev.po4yka.lenswake.ui.ReadinessUiState

internal enum class StatusVisualState {
    ERROR,
    WARNING,
    SUCCESS,
    IN_PROGRESS,
    NEUTRAL,
}

internal fun statusVisualState(statusLabel: String): StatusVisualState {
    val normalizedStatus = statusLabel.lowercase()
    return when {
        listOf("blocked", "failed", "error", "incompatible", "unavailable", "cancelled")
            .any(normalizedStatus::contains) -> StatusVisualState.ERROR
        listOf("warning", "attention", "needs", "safety", "pending", "degraded", "stale")
            .any(normalizedStatus::contains) -> StatusVisualState.WARNING
        listOf("ready", "available", "verified", "completed", "passed", "enabled", "succeeded")
            .any(normalizedStatus::contains) -> StatusVisualState.SUCCESS
        listOf("checking", "installing", "running", "working", "in progress")
            .any(normalizedStatus::contains) -> StatusVisualState.IN_PROGRESS
        else -> StatusVisualState.NEUTRAL
    }
}

private data class StatusVisuals(
    val state: StatusVisualState,
    @param:DrawableRes val iconResource: Int,
    val cardContainerColor: Color,
    val cardContentColor: Color,
    val indicatorContainerColor: Color,
    val indicatorContentColor: Color,
)

@Composable
fun ScreenHeader(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ReadinessCard(
    readiness: ReadinessUiState,
    onOpenSetup: () -> Unit,
    modifier: Modifier = Modifier,
    showSetupAction: Boolean = true,
) {
    val statusLabel = when (readiness) {
        is ReadinessUiState.Blocked -> "Blocked"
        is ReadinessUiState.Checking -> "Checking"
        is ReadinessUiState.Ready -> "Ready"
        is ReadinessUiState.ReadyWithWarnings -> "Ready with warnings"
    }
    val visuals = statusVisuals(statusLabel)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                stateDescription = statusLabel
                liveRegion = LiveRegionMode.Polite
            },
        colors = CardDefaults.cardColors(
            containerColor = visuals.cardContainerColor,
            contentColor = visuals.cardContentColor,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusIcon(statusLabel, visuals)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = readiness.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Text(
                text = readiness.summary,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (showSetupAction) {
                OutlinedButton(
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    onClick = onOpenSetup,
                ) {
                    Text("Review setup")
                }
            }
        }
    }
}

@Composable
fun CapabilityRow(
    capability: CapabilityUiState,
    onRemediate: (SetupRemediationAction) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val statusLabel = when (capability.status) {
        CapabilityStatus.UNKNOWN -> "Unknown"
        CapabilityStatus.AVAILABLE -> "Available"
        CapabilityStatus.BLOCKED -> "Blocked"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = statusLabel }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusIcon(statusLabel)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = capability.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (capability.required) "$statusLabel · Required" else "$statusLabel · Optional",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = capability.detail,
                style = MaterialTheme.typography.bodyMedium,
            )
            capability.remediation?.let { action ->
                OutlinedButton(
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    onClick = { onRemediate(action) },
                ) {
                    Text("Resolve")
                }
            }
        }
    }
}

@Composable
fun ActionSection(
    title: String,
    detail: String,
    actionLabel: String,
    actionEnabled: Boolean,
    unavailableReason: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            enabled = actionEnabled,
            onClick = onAction,
        ) {
            Text(actionLabel)
        }
        if (!actionEnabled) {
            Text(
                text = unavailableReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier.semantics { heading() },
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun SummaryCard(
    title: String,
    detail: String,
    status: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val visuals = statusVisuals(status)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = status },
        colors = CardDefaults.cardColors(
            containerColor = visuals.cardContainerColor,
            contentColor = visuals.cardContentColor,
        ),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            StatusIcon(status, visuals)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = visuals.cardContentColor,
                )
                if (actionLabel != null && onAction != null) {
                    OutlinedButton(
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        onClick = onAction,
                    ) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusRow(
    title: String,
    detail: String,
    status: String,
    modifier: Modifier = Modifier,
) {
    val visuals = statusVisuals(status)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = status }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusIcon(status, visuals)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelLarge,
                color = visuals.indicatorContentColor,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun StatusIcon(
    statusLabel: String,
    modifier: Modifier = Modifier,
) {
    StatusIcon(statusLabel, statusVisuals(statusLabel), modifier)
}

@Composable
private fun StatusIcon(
    statusLabel: String,
    visuals: StatusVisuals,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = Modifier
            .then(modifier)
            .size(32.dp)
            .clearAndSetSemantics {
                contentDescription = "$statusLabel status"
            },
        shape = CircleShape,
        color = visuals.indicatorContainerColor,
        contentColor = visuals.indicatorContentColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (visuals.state == StatusVisualState.IN_PROGRESS) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = visuals.indicatorContentColor,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    modifier = Modifier.size(20.dp),
                    painter = painterResource(visuals.iconResource),
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun statusVisuals(statusLabel: String): StatusVisuals {
    val colorScheme = MaterialTheme.colorScheme
    val state = statusVisualState(statusLabel)
    return when (state) {
        StatusVisualState.ERROR -> StatusVisuals(
            state = state,
            iconResource = R.drawable.ic_error_24,
            cardContainerColor = colorScheme.errorContainer,
            cardContentColor = colorScheme.onErrorContainer,
            indicatorContainerColor = colorScheme.error,
            indicatorContentColor = colorScheme.onError,
        )
        StatusVisualState.WARNING -> StatusVisuals(
            state = state,
            iconResource = R.drawable.ic_warning_24,
            cardContainerColor = colorScheme.tertiaryContainer,
            cardContentColor = colorScheme.onTertiaryContainer,
            indicatorContainerColor = colorScheme.tertiary,
            indicatorContentColor = colorScheme.onTertiary,
        )
        StatusVisualState.SUCCESS -> StatusVisuals(
            state = state,
            iconResource = R.drawable.ic_check_circle_24,
            cardContainerColor = colorScheme.primaryContainer,
            cardContentColor = colorScheme.onPrimaryContainer,
            indicatorContainerColor = colorScheme.primary,
            indicatorContentColor = colorScheme.onPrimary,
        )
        StatusVisualState.IN_PROGRESS -> StatusVisuals(
            state = state,
            iconResource = R.drawable.ic_info_24,
            cardContainerColor = colorScheme.secondaryContainer,
            cardContentColor = colorScheme.onSecondaryContainer,
            indicatorContainerColor = colorScheme.secondary,
            indicatorContentColor = colorScheme.onSecondary,
        )
        StatusVisualState.NEUTRAL -> StatusVisuals(
            state = state,
            iconResource = if (statusLabel.equals("Unknown", ignoreCase = true)) {
                R.drawable.ic_help_24
            } else {
                R.drawable.ic_info_24
            },
            cardContainerColor = colorScheme.surfaceContainerLow,
            cardContentColor = colorScheme.onSurface,
            indicatorContainerColor = colorScheme.surfaceContainerHighest,
            indicatorContentColor = colorScheme.onSurface,
        )
    }
}
