package com.aihuiji.meeting.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.IntentCompat
import com.aihuiji.meeting.AiHuijiApp
import com.aihuiji.meeting.MainActivity
import com.aihuiji.meeting.R

/** Result of the system screen/audio capture consent dialog (Android 14 每次视频会议记录确认一次). */
data class ProjectionConsent(val resultCode: Int, val data: Intent)

/**
 * Keeps capture alive for long meetings (NFR: 4 h) with the Android 14 microphone and
 * mediaProjection foreground-service types. The projection must be obtained after startForeground.
 */
class MeetingForegroundService : Service() {
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val micGranted = intent?.getBooleanExtra(EXTRA_MIC, false) == true
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }

        var types = 0
        if (micGranted) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (data != null) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (types == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, types)
        else startForeground(NOTIFICATION_ID, notification)

        if (data != null && projection == null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projection = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()?.also { mp ->
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { projection = null }
                }, null)
                (application as AiHuijiApp).container.meetingController.attachProjection(mp)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "meeting"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_MIC = "mic"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        fun start(context: Context, micGranted: Boolean, consent: ProjectionConsent?) {
            if (!micGranted && consent == null) return
            val intent = Intent(context, MeetingForegroundService::class.java)
                .putExtra(EXTRA_MIC, micGranted)
            consent?.let { intent.putExtra(EXTRA_RESULT_CODE, it.resultCode).putExtra(EXTRA_DATA, it.data) }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeetingForegroundService::class.java))
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_meeting), NotificationManager.IMPORTANCE_LOW),
            )
        }

        internal fun buildNotification(context: Context, textRes: Int = R.string.notif_recording): Notification {
            ensureChannel(context)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_wave)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(textRes))
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }
}
