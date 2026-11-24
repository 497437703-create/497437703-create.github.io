package com.aihuiji.meeting

import android.app.Application
import android.content.Context
import com.aihuiji.meeting.core.provider.ProviderFactory
import com.aihuiji.meeting.data.MeetingRepository
import com.aihuiji.meeting.data.SettingsStore
import com.aihuiji.meeting.data.TokenVault
import com.aihuiji.meeting.device.DeviceChecker
import com.aihuiji.meeting.export.Exporter
import com.aihuiji.meeting.meeting.MeetingController
import com.aihuiji.meeting.service.MeetingForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    val tokens = TokenVault(context)
    val repository = MeetingRepository(File(context.filesDir, "meetings"))
    val providers = ProviderFactory(File(context.cacheDir, "spool").apply { mkdirs() }) { id, slot -> tokens.get(slot.key(id)) }
    val deviceChecker = DeviceChecker(context)
    val meetingController = MeetingController(context, settings, repository, providers)
    val exporter = Exporter(context)
}

class AiHuijiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        MeetingForegroundService.ensureChannel(this)
        container.appScope.launch {
            container.repository.load()
            val s = container.settings.value
            container.repository.prune(s.retentionDays, s.localCacheLimit)
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as AiHuijiApp).container
