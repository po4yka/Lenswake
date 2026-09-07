package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.ui.ProfileSummaryUiState
import dev.po4yka.lenswake.ui.ScheduleActionUiState
import dev.po4yka.lenswake.ui.ScheduleEditorUiState
import dev.po4yka.lenswake.ui.ScheduleFormUiState
import dev.po4yka.lenswake.ui.component.ScreenHeader
import dev.po4yka.lenswake.ui.scaffoldContentViewport
import dev.po4yka.lenswake.ui.screenContentPadding

/**
 * Full-screen host of [ScheduleEditor]. The form used to be one item of the schedules list, where
 * the IME resized the list around it, the form could sit half off-screen, and the cards around it
 * stayed interactive. Its own destination makes the form the only reachable surface, and the route
 * title lives in the top app bar like every other screen.
 *
 * [action] is rendered here and not only on the schedules list because a rejected or failed save
 * keeps the editor open: its message has to reach the user on the surface that is on screen.
 */
@Composable
internal fun ScheduleEditorScreen(
    editor: ScheduleEditorUiState.Open,
    profiles: List<ProfileSummaryUiState>,
    contentPadding: PaddingValues,
    action: ScheduleActionUiState,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onClearOutcome: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .scaffoldContentViewport(contentPadding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(screenContentPadding(topMargin = 24.dp, bottomMargin = 24.dp)),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader(summary = stringResource(R.string.schedule_editor_summary))
        if (action !is ScheduleActionUiState.Idle) {
            ScheduleOutcome(action = action, onDismiss = onClearOutcome)
        }
        ScheduleEditor(
            editor = editor,
            profiles = profiles,
            busyMessage = (action as? ScheduleActionUiState.Working)?.message,
            onUpdateForm = onUpdateForm,
            onSubmit = onSubmit,
            onCancel = onCancel,
        )
    }
}
