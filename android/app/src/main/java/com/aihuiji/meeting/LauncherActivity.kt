package com.aihuiji.meeting

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.aihuiji.meeting.core.model.MeetingConfig
import com.aihuiji.meeting.core.model.MeetingMode
import com.aihuiji.meeting.service.CaptionBarService
import com.aihuiji.meeting.service.ProjectionConsent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Entry point that never draws anything. Launching the app only raises the floating caption window
 * over whatever is on screen; the only system UI that can appear is Android's own prompt for the
 * overlay permission, the microphone, or screen-audio capture.
 */
class LauncherActivity : ComponentActivity() {
    private var video = false

    private val overlaySettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) CaptionBarService.show(this)
        finish()
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { afterMic() }
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        begin(if (result.resultCode == Activity.RESULT_OK && data != null) ProjectionConsent(result.resultCode, data) else null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == ACTION_START) {
            video = intent.getBooleanExtra(EXTRA_VIDEO, false)
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) afterMic()
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else if (Settings.canDrawOverlays(this)) {
            CaptionBarService.show(this)
            finish()
        } else {
            overlaySettings.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", packageName, null)),
            )
        }
    }

    private fun afterMic() {
        if (video) projection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        else begin(null)
    }

    private fun begin(consent: ProjectionConsent?) {
        val controller = container.meetingController
        if (controller.live.value == null) {
            val s = container.settings.value
            val mode = if (video) MeetingMode.VIDEO else MeetingMode.IN_ROOM
            val config = MeetingConfig(
                mode = mode,
                primaryLang = s.primaryLang,
                autoDetect = s.autoDetect,
                translationEnabled = s.translationEnabled,
                targetLangs = s.targetLangs.filter { it != s.primaryLang },
                templateId = s.templateId,
                profileId = s.activeProfileId,
                recordOnly = false,
            )
            val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
            val title = getString(if (video) R.string.title_video else R.string.title_inroom)
            controller.start(config, "$title · $stamp", consent)
        }
        finish()
    }

    companion object {
        private const val ACTION_START = "com.aihuiji.meeting.action.START"
        private const val EXTRA_VIDEO = "video"

        /** Starts a meeting from the floating window; hosted by this invisible activity so Android can ask for permissions. */
        fun requestStart(context: Context, video: Boolean) {
            context.startActivity(
                Intent(context, LauncherActivity::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_VIDEO, video)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }
}
