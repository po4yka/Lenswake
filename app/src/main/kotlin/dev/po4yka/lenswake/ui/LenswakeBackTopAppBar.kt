package dev.po4yka.lenswake.ui

import androidx.annotation.StringRes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.po4yka.lenswake.R

/**
 * Top app bar of a nested destination. Leaving means different things per destination - Setup pops
 * the back stack, the schedule editor discards the draft - so the caller owns the back action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackTopAppBar(
    @StringRes titleResource: Int,
    testTag: String,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(titleResource),
                modifier = Modifier.semantics { heading() },
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back_24),
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
        modifier = Modifier.testTag(testTag),
    )
}

/** Title of the schedule editor destination. A closed editor is a frame of back-stack healing. */
@StringRes
internal fun scheduleEditorTitle(editor: ScheduleEditorUiState): Int =
    if ((editor as? ScheduleEditorUiState.Open)?.mode is ScheduleEditorMode.Edit) {
        R.string.schedule_editor_edit_title
    } else {
        R.string.schedule_editor_create_title
    }
