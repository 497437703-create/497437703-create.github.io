package com.aihuiji.meeting.ui.summary

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Close
import com.aihuiji.meeting.ui.components.IconCircleButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aihuiji.meeting.R
import com.aihuiji.meeting.container
import com.aihuiji.meeting.core.model.MeetingRecord
import com.aihuiji.meeting.export.DownloadsSink
import com.aihuiji.meeting.export.ExportFormat
import com.aihuiji.meeting.export.FileSink
import com.aihuiji.meeting.export.MinutesSaver
import com.aihuiji.meeting.export.SaveLocationStore
import com.aihuiji.meeting.export.SaveResult
import com.aihuiji.meeting.export.TreeSink
import com.aihuiji.meeting.share.QrImage
import com.aihuiji.meeting.ui.components.PrimaryButton
import com.aihuiji.meeting.ui.components.SecondaryButton
import com.aihuiji.meeting.ui.components.pressable
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shown once the minutes are ready: take them away by saving to a folder on this display, or by scanning
 * a QR code. The existing share link and PDF / Markdown / TXT export stay one tap away at the bottom.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PostMeetingSheet(record: MeetingRecord, onShareLink: () -> Unit, onExport: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val container = ctx.container
    val scope = rememberCoroutineScope()
    val store = remember { SaveLocationStore(ctx) }
    var tree by remember { mutableStateOf(store.tree) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SaveResult?>(null) }

    fun save(sink: FileSink) {
        saving = true
        scope.launch {
            result = withContext(Dispatchers.IO) {
                val files = ExportFormat.entries.map { container.exporter.export(record, it) to it }
                MinutesSaver.save(files, sink)
            }
            saving = false
        }
    }

    val destination = tree?.let { runCatching { TreeSink(ctx, it) }.getOrNull() } ?: DownloadsSink(ctx)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            store.tree = uri
            tree = uri
            save(TreeSink(ctx, uri))
        }
    }

    val share = record.share
    val shape = RoundedCornerShape(32.dp)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg0.copy(alpha = 0.72f))
            .pressable(onClick = onClose)
            .systemBarsPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        val wide = maxWidth >= 1000.dp
        val pad = if (wide) 40.dp else 24.dp
        Column(
            Modifier
                .widthIn(max = 1400.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .clip(shape)
                .background(Palette.Bg2)
                .border(1.dp, Palette.Stroke, shape)
                .pressable(onClick = {})
                .verticalScroll(rememberScrollState())
                .padding(pad),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = Palette.Success, modifier = Modifier.size(56.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.post_title), style = Type.TitleL)
                    Text(stringResource(R.string.post_hint), style = Type.Caption)
                }
                IconCircleButton(Icons.Outlined.Close, stringResource(R.string.action_close), onClose, size = 64.dp)
            }
            Spacer(Modifier.height(24.dp))
            val saveCard: @Composable (Modifier) -> Unit = { m ->
                ChoiceCard(m, Icons.Outlined.SaveAlt, stringResource(R.string.post_save_title), stringResource(R.string.post_save_hint)) {
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(20.dp)).background(Palette.Bg0.copy(alpha = 0.6f)).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FolderOpen, null, tint = Palette.Brand400, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(R.string.post_save_to, destination.label), style = Type.Label, maxLines = 1)
                    }
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton(
                        stringResource(if (saving) R.string.post_saving else R.string.post_save_action),
                        { if (!saving) save(destination) },
                        Modifier.fillMaxWidth(),
                        icon = Icons.Outlined.SaveAlt,
                        enabled = !saving,
                        height = 88.dp,
                    )
                    Spacer(Modifier.height(14.dp))
                    SecondaryButton(stringResource(R.string.post_choose_folder), { picker.launch(null) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.FolderOpen, height = 80.dp)
                    result?.let { r ->
                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (r.ok) stringResource(R.string.post_saved, r.saved.size, r.where)
                            else stringResource(R.string.post_save_failed, r.failed.joinToString()),
                            style = Type.Label,
                            color = if (r.ok) Palette.Success else Palette.Warning,
                        )
                        if (r.ok) Text(r.saved.joinToString("  ·  "), style = Type.Caption)
                    }
                }
            }
            val qrCard: @Composable (Modifier) -> Unit = { m ->
                ChoiceCard(m, Icons.Outlined.QrCode2, stringResource(R.string.post_qr_title), stringResource(R.string.post_qr_hint)) {
                    Spacer(Modifier.height(20.dp))
                    Box(Modifier.align(Alignment.CenterHorizontally).size(280.dp).clip(RoundedCornerShape(24.dp)).background(Palette.Text1).padding(14.dp)) {
                        QrImage(share?.url ?: "aihuiji://summary/${record.id}", Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.meeting_code) + "  " + record.code,
                        style = Type.Label.copy(letterSpacing = 4.sp),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    share?.expiresAtEpochMs?.let {
                        Text(
                            stringResource(R.string.share_expires, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(it))),
                            style = Type.Caption,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                }
            }
            if (wide) {
                Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    saveCard(Modifier.weight(1f).fillMaxHeight())
                    qrCard(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    saveCard(Modifier.fillMaxWidth())
                    qrCard(Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.post_more), style = Type.Caption)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.action_distribute), onShareLink, height = 72.dp)
                SecondaryButton(stringResource(R.string.action_export), onExport, height = 72.dp)
                SecondaryButton(stringResource(R.string.post_later), onClose, height = 72.dp)
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    hint: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(modifier.clip(shape).background(Palette.Bg0.copy(alpha = 0.55f)).border(1.dp, Palette.Stroke, shape).padding(32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Palette.Brand400, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = Type.TitleM)
                Text(hint, style = Type.Caption)
            }
        }
        content()
    }
}
