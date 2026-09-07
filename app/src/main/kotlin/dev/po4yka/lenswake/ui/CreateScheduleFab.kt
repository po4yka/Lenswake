package dev.po4yka.lenswake.ui

import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.po4yka.lenswake.R

/**
 * Primary action of the Schedules route. Setup can block schedule creation, so instead of a
 * permanently disabled control the button stays enabled and opens Setup, the screen that clears
 * the blocker. The content description always names the destination the tap actually reaches.
 * The button is absent on every other route and while the schedule editor is open.
 */
@Composable
internal fun CreateScheduleFab(
    state: LenswakeUiState,
    navigation: LenswakeNavigationState,
    onBeginCreate: () -> Unit,
) {
    val editorClosed = state.scheduleEditor is ScheduleEditorUiState.Closed
    if (navigation.currentDestination != SchedulesRoute || !editorClosed) {
        return
    }
    val canCreate = state.actions.canCreateSchedule
    val description = stringResource(
        if (canCreate) R.string.action_create_schedule else R.string.action_review_setup,
    )
    FloatingActionButton(
        onClick = { if (canCreate) onBeginCreate() else navigation.navigateToSetup() },
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_add_24),
            contentDescription = description,
        )
    }
}
