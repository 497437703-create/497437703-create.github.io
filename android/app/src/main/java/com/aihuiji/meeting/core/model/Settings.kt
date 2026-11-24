package com.aihuiji.meeting.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProfileKind { ENTERPRISE, LENOVO_CLOUD }

/**
 * 服务端档案: one deployment (enterprise SCH-900S or Lenovo public cloud) behind a single client
 * contract. The auth token is not stored here; it lives in [com.aihuiji.meeting.data.TokenVault].
 */
@Serializable
data class ServerProfile(
    val id: String,
    val kind: ProfileKind,
    val baseUrl: String = "",
    /** Use the in-app demo backend until the real API contract is available. */
    val demoBackend: Boolean = true,
)

@Serializable
enum class ScreenSizeClass(val inches: Int, val inRoomCaptionSp: Int, val panelCaptionSp: Int) {
    AUTO(0, 48, 30),
    IN65(65, 52, 34),
    IN75(75, 48, 32),
    IN86(86, 46, 30),
    IN98(98, 44, 30),
}

/** Window rectangle in physical pixels; [w] == 0 means "not placed yet, use the default". */
@Serializable
data class BarBounds(val x: Int = 0, val y: Int = 0, val w: Int = 0, val h: Int = 0) {
    val isSet: Boolean get() = w > 0 && h > 0
}

/** Top-left of the minimised floating icon in physical pixels; negative means "not placed yet". */
@Serializable
data class IconPos(val x: Int = -1, val y: Int = -1) {
    val isSet: Boolean get() = x >= 0 && y >= 0
}

@Serializable
data class AppSettings(
    val onboardingDone: Boolean = false,
    val profiles: List<ServerProfile> = listOf(
        ServerProfile(id = "enterprise", kind = ProfileKind.ENTERPRISE),
        ServerProfile(id = "lenovo-cloud", kind = ProfileKind.LENOVO_CLOUD),
    ),
    val activeProfileId: String = "enterprise",
    /** Falling back from enterprise to Lenovo cloud sends data off-premises: off unless an admin allows it. */
    val allowCloudFallback: Boolean = false,
    val primaryLang: Lang = Lang.ZH,
    val autoDetect: Boolean = false,
    val translationEnabled: Boolean = false,
    val targetLangs: List<Lang> = listOf(Lang.EN),
    val translationDisplay: TranslationDisplay = TranslationDisplay.INTERLEAVED,
    val captionSize: CaptionSize = CaptionSize.M,
    val screenSize: ScreenSizeClass = ScreenSizeClass.AUTO,
    val templateId: String = Templates.GENERAL.id,
    val showAvatars: Boolean = true,
    val showTimestamps: Boolean = true,
    val captionBackdrop: Float = 0f,
    val reduceMotion: Boolean = false,
    val retentionDays: Int = 180,
    val localCacheLimit: Int = 20,
    val adminPinHash: String? = null,
    val vcAppPackage: String = "com.tencent.wemeet.app",
    /** Last choice of 视频会议 (capture the far end through playback capture) in the start sheet. */
    val remoteAudio: Boolean = false,
    val captionBar: BarBounds = BarBounds(),
    val captionIcon: IconPos = IconPos(),
) {
    val activeProfile: ServerProfile get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()
    fun profile(kind: ProfileKind): ServerProfile? = profiles.firstOrNull { it.kind == kind }
}

/** Per-meeting options chosen in the pre-meeting sheet (S03). */
@Serializable
data class MeetingConfig(
    val mode: MeetingMode,
    val primaryLang: Lang,
    val autoDetect: Boolean,
    val translationEnabled: Boolean,
    val targetLangs: List<Lang>,
    val templateId: String,
    val profileId: String,
    val recordOnly: Boolean = false,
)
