package com.aihuiji.meeting.core.provider

import com.aihuiji.meeting.core.model.ProfileKind
import com.aihuiji.meeting.core.model.ServerProfile
import com.aihuiji.meeting.core.provider.demo.DemoBackend
import com.aihuiji.meeting.core.provider.remote.RemoteBackend
import java.io.File

class ProviderFactory(
    private val spoolDir: File,
    private val tokenFor: (profileId: String) -> String?,
) {
    fun create(profile: ServerProfile): ProviderBundle =
        if (profile.demoBackend || profile.baseUrl.isBlank()) {
            DemoBackend.bundle(shareHost = demoShareHost(profile.kind))
        } else {
            RemoteBackend.bundle(profile.baseUrl, { tokenFor(profile.id) }, spoolDir)
        }

    private fun demoShareHost(kind: ProfileKind) = when (kind) {
        ProfileKind.ENTERPRISE -> "aihuiji.corp.local"
        ProfileKind.LENOVO_CLOUD -> "aihuiji-cloud.local"
    }
}
