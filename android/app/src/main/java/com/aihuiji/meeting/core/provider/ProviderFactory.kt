package com.aihuiji.meeting.core.provider

import com.aihuiji.meeting.core.model.ProfileKind
import com.aihuiji.meeting.core.model.SecretSlot
import com.aihuiji.meeting.core.model.ServerProfile
import com.aihuiji.meeting.core.provider.demo.DemoBackend
import com.aihuiji.meeting.core.provider.hunyuan.HunyuanBackend
import com.aihuiji.meeting.core.provider.hunyuan.TencentAsrCredentials
import java.io.File

/**
 * Single place that turns the user's choice (设置 › AI 服务) into providers. Everything above this
 * (live captions, translation, minutes, takeaway) only sees [ProviderBundle].
 */
class ProviderFactory(
    private val spoolDir: File,
    private val secret: (profileId: String, slot: SecretSlot) -> String?,
) {
    fun create(profile: ServerProfile): ProviderBundle {
        if (profile.demoBackend) return DemoBackend.bundle(shareHost = demoShareHost(profile.kind))
        val get = { slot: SecretSlot -> { secret(profile.id, slot) } }
        return when (profile.kind) {
            ProfileKind.ENTERPRISE -> HunyuanBackend.onPrem(profile, get(SecretSlot.TOKEN), spoolDir)
            ProfileKind.HUNYUAN_CLOUD -> HunyuanBackend.cloud(
                profile = profile,
                apiKey = get(SecretSlot.API_KEY),
                asrCreds = TencentAsrCredentials(profile.appId.trim(), get(SecretSlot.SECRET_ID), get(SecretSlot.SECRET_KEY)),
                shareToken = get(SecretSlot.TOKEN),
                spoolDir = spoolDir,
            )
        }
    }

    private fun demoShareHost(kind: ProfileKind) = when (kind) {
        ProfileKind.ENTERPRISE -> "aihuiji.corp.local"
        ProfileKind.HUNYUAN_CLOUD -> "aihuiji-cloud.local"
    }
}
