package dev.po4yka.lenswake.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.ui.CapabilityStatus
import dev.po4yka.lenswake.ui.CapabilityUiState
import dev.po4yka.lenswake.ui.ReadinessUiState

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
        is ReadinessUiState.Checking -> "Unknown"
        is ReadinessUiState.Ready -> "Ready"
        is ReadinessUiState.ReadyWithWarnings -> "Ready with warnings"
    }
    val containerColor = when (readiness) {
        is ReadinessUiState.Blocked -> MaterialTheme.colorScheme.errorContainer
        is ReadinessUiState.Checking -> MaterialTheme.colorScheme.surfaceVariant
        is ReadinessUiState.Ready -> MaterialTheme.colorScheme.primaryContainer
        is ReadinessUiState.ReadyWithWarnings -> MaterialTheme.colorScheme.tertiaryContainer
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                stateDescription = statusLabel
                liveRegion = LiveRegionMode.Polite
            },
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusGlyph(statusLabel)
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
        StatusGlyph(statusLabel)
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
        }
    }
}

@Composable
fun HonestEmptyState(
    title: String,
    detail: String,
    actionLabel: String,
    actionEnabled: Boolean,
    unavailableReason: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                modifier = Modifier.semantics { heading() },
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                enabled = actionEnabled,
                onClick = onAction,
            ) {
                Text(actionLabel)
            }
            if (!actionEnabled) {
                Text(
                    text = "Unavailable: $unavailableReason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = status },
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            StatusGlyph(status)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusGlyph(statusLabel: String) {
    val normalizedStatus = statusLabel.lowercase()
    val glyph = when {
        "blocked" in normalizedStatus || "failed" in normalizedStatus -> "!"
        "warning" in normalizedStatus -> "△"
        "ready" in normalizedStatus ||
            "available" in normalizedStatus ||
            "verified" in normalizedStatus ||
            "completed" in normalizedStatus -> "✓"
        normalizedStatus == "unknown" -> "?"
        else -> "•"
    }
    Text(
        modifier = Modifier
            .sizeIn(minWidth = 32.dp, minHeight = 32.dp)
            .clearAndSetSemantics {
                contentDescription = "$statusLabel status"
            },
        text = glyph,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
}
