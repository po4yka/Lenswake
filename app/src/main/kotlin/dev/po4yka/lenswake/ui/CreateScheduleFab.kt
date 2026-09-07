package dev.po4yka.lenswake.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
    val visible = navigation.currentDestination == SchedulesRoute &&
        state.scheduleEditor is ScheduleEditorUiState.Closed
    val canCreate = state.actions.canCreateSchedule
    val description = stringResource(
        if (canCreate) R.string.action_create_schedule else R.string.action_review_setup,
    )
    // NavDisplay already animates the destination content, so a button that snaps is the odd one
    // out. Scale keeps the slot size, and at an animation scale of 0 the change stays instant.
    AnimatedVisibility(visible = visible, enter = scaleIn(), exit = scaleOut()) {
        FloatingActionButton(
            onClick = {
                // The button keeps taking taps while it scales out, after its route is gone.
                if (visible) {
                    if (canCreate) onBeginCreate() else navigation.navigateToSetup()
                }
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add_24),
                contentDescription = description,
            )
        }
    }
}
