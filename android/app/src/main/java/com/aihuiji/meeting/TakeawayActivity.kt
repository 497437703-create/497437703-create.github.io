package com.aihuiji.meeting

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aihuiji.meeting.export.ExportFormat
import com.aihuiji.meeting.ui.meeting.ShareQrDialog
import com.aihuiji.meeting.ui.summary.ExportMenu
import com.aihuiji.meeting.ui.summary.PostMeetingSheet
import com.aihuiji.meeting.ui.theme.AiHuijiTheme

/**
 * Stand-alone dialog shown when the minutes are ready: save to a folder on this display or take the minutes
 * away by QR code, with the share link and PDF / Markdown / TXT export still available. The floating caption
 * window is hidden while it is open so nothing covers or clips it.
 */
class TakeawayActivity : ComponentActivity() {
    private var meetingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        meetingId = intent.getStringExtra(EXTRA_ID)
        container.meetingController.setBarSuppressed(true)
        setContent {
            AiHuijiTheme {
                val meetings by container.repository.meetings.collectAsStateWithLifecycle()
                val record = meetings.firstOrNull { it.id == meetingId }
                var overlay by remember { mutableStateOf<Overlay?>(null) }
                if (record == null) {
                    Box(androidx.compose.ui.Modifier.fillMaxSize())
                } else {
                    PostMeetingSheet(
                        record,
                        onShareLink = { overlay = Overlay.Link },
                        onExport = { overlay = Overlay.Export },
                        onClose = ::finish,
                    )
                    when (overlay) {
                        Overlay.Link -> {
                            val share = record.share
                            ShareQrDialog(
                                url = share?.url ?: "aihuiji://summary/${record.id}",
                                code = record.code,
                                expiresAt = share?.expiresAtEpochMs,
                                title = getString(R.string.share_summary_hint),
                                onClose = { overlay = null },
                            )
                        }
                        Overlay.Export -> ExportMenu(onPick = { format: ExportFormat ->
                            val file = container.exporter.export(record, format)
                            startActivity(container.exporter.shareIntent(file, format))
                            overlay = null
                        }, onClose = { overlay = null })
                        null -> Unit
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        meetingId = intent.getStringExtra(EXTRA_ID)
    }

    override fun onDestroy() {
        container.meetingController.setBarSuppressed(false)
        super.onDestroy()
    }

    private enum class Overlay { Link, Export }

    companion object {
        private const val EXTRA_ID = "id"

        fun open(context: Context, meetingId: String) {
            context.startActivity(
                Intent(context, TakeawayActivity::class.java)
                    .putExtra(EXTRA_ID, meetingId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }
}
