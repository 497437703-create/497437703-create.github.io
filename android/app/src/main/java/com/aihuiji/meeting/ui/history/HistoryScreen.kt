package com.aihuiji.meeting.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Settings
import com.aihuiji.meeting.core.model.MeetingMode
import com.aihuiji.meeting.ui.components.ToolAction
import com.aihuiji.meeting.ui.home.RecoveryBanner
import com.aihuiji.meeting.ui.home.SelfCheckOverlay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aihuiji.meeting.R
import com.aihuiji.meeting.container
import com.aihuiji.meeting.ui.components.IconCircleButton
import com.aihuiji.meeting.ui.home.MeetingCard
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type

/** S11 history grid (cross-meeting search arrives in M2). */
@Composable
fun HistoryScreen(onOpen: (String) -> Unit, onSettings: () -> Unit, onBack: () -> Unit) {
    val container = LocalContext.current.container
    val meetings by container.repository.meetings.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var selfCheck by remember { mutableStateOf(false) }
    var dismissedRecovery by remember { mutableStateOf(false) }
    val unfinished = remember(meetings) { container.repository.unfinished(container.meetingController.live.value?.record?.id) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Palette.Bg0).padding(48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconCircleButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back), onBack, size = 80.dp)
            Spacer(Modifier.width(24.dp))
            Text(stringResource(R.string.history_title), style = Type.TitleL)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.history_retention, settings.retentionDays, settings.localCacheLimit), style = Type.Caption)
            Spacer(Modifier.width(24.dp))
            ToolAction(Icons.Outlined.FactCheck, stringResource(R.string.action_self_check), { selfCheck = true })
            Spacer(Modifier.width(16.dp))
            ToolAction(Icons.Outlined.Settings, stringResource(R.string.action_settings), onSettings)
        }
        if (unfinished.isNotEmpty() && !dismissedRecovery) {
            Spacer(Modifier.height(24.dp))
            RecoveryBanner(unfinished.first(), onRecover = {
                container.meetingController.recover(it.id)
                onOpen(it.id)
            }, onDismiss = { dismissedRecovery = true })
        }
        Spacer(Modifier.height(32.dp))
        if (meetings.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.recent_empty), style = Type.TitleM, color = Palette.Text2)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(meetings, key = { it.id }) { m -> MeetingCard(m) { onOpen(m.id) } }
            }
        }
    }
    if (selfCheck) {
        SelfCheckOverlay(mode = MeetingMode.VIDEO, standalone = true, onProceed = { selfCheck = false }, onCancel = { selfCheck = false })
    }
    }
}
