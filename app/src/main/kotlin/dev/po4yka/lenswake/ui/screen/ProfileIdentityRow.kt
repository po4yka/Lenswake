package dev.po4yka.lenswake.ui.screen

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.ui.ProfileSummaryUiState
import dev.po4yka.lenswake.ui.component.StatusRow

// Twelve hex characters of the SHA-256 definition digest stay unique across the few profiles one
// device holds and keep the identity line to one line; the copy action yields the complete digest.
private const val FINGERPRINT_PREFIX_LENGTH = 12

@Composable
internal fun ProfileIdentityRow(profile: ProfileSummaryUiState) {
    val clipboard = LocalClipboard.current
    StatusRow(
        title = profile.title,
        detail = stringResource(
            R.string.profile_identity_detail,
            profile.supportTier.label(),
            profile.environment,
            profile.definitionFingerprint.take(FINGERPRINT_PREFIX_LENGTH),
        ),
        status = profile.compatibility,
        actionLabel = stringResource(R.string.action_copy_fingerprint),
        actionContentDescription = stringResource(
            R.string.profile_fingerprint_copy_content_description,
            profile.title,
        ),
        onAction = {
            clipboard.nativeClipboard.setPrimaryClip(
                ClipData.newPlainText(profile.title, profile.definitionFingerprint),
            )
        },
    )
}
