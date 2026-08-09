package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.ui.LenswakeUiState
import dev.po4yka.lenswake.ui.component.HonestEmptyState
import dev.po4yka.lenswake.ui.component.ReadinessCard
import dev.po4yka.lenswake.ui.component.ScreenHeader
import dev.po4yka.lenswake.ui.component.SummaryCard

@Composable
fun SchedulesScreen(
    state: LenswakeUiState,
    contentPadding: PaddingValues,
    onOpenSetup: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = contentPadding.calculateTopPadding() + 24.dp,
            end = 20.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            ScreenHeader(
                title = "Schedules",
                summary = "Plan unattended Time Lapse sessions in the native Pixel Camera.",
            )
        }
        item {
            ReadinessCard(
                readiness = state.readiness,
                onOpenSetup = onOpenSetup,
            )
        }
        if (state.schedules.isEmpty()) {
            item {
                HonestEmptyState(
                    title = "No schedules",
                    detail = "Nothing is scheduled. Lenswake will not launch Pixel Camera until a persisted schedule is created.",
                    actionLabel = "Create schedule",
                    actionEnabled = state.actions.canCreateSchedule,
                    unavailableReason = state.actions.createScheduleUnavailableReason,
                    onAction = {},
                )
            }
        } else {
            items(state.schedules.size, key = { state.schedules[it].id }) { index ->
                val schedule = state.schedules[index]
                SummaryCard(
                    title = schedule.title,
                    detail = schedule.timing,
                    status = schedule.status,
                )
            }
        }
    }
}
