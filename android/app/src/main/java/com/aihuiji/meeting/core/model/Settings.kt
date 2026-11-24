package com.aihuiji.meeting.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/** Where Tencent Hunyuan ASR, translation and minutes run. Both kinds use the same Hunyuan models. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
enum class ProfileKind {
    /** On-prem Hunyuan on the enterprise Lenovo SCH-900S (Intel Arc B70). */
    ENTERPRISE,

    /** Tencent Hunyuan cloud APIs. Settings saved before this option existed called it LENOVO_CLOUD. */
    @JsonNames("LENOVO_CLOUD")
    HUNYUAN_CLOUD,
}

/** Credentials kept in [com.aihuiji.meeting.data.TokenVault] (Android Keystore), never in settings JSON. */
enum class SecretSlot(val suffix: String) {
    /** SCH-900S gateway / model server bearer token. */
    TOKEN(""),
    /** Hunyuan OpenAI-compatible API key (cloud translation + minutes). */
    API_KEY(".apiKey"),
    /** Tencent Cloud SecretId / SecretKey that sign real-time ASR requests. */
    SECRET_ID(".secretId"),
    SECRET_KEY(".secretKey"),
    ;

    fun key(profileId: String) = profileId + suffix
}

/**
 * 服务端档案: one Hunyuan deployment. Blank optional fields fall back to [HunyuanDefaults] for the
 * profile's [kind]; secrets live in the Keystore-backed vault under [SecretSlot.key].
 */
@Serializable
data class ServerProfile(
    val id: String,
    val kind: ProfileKind,
    /** On-prem: SCH-900S gateway base URL including port. Cloud: optional share-link service. */
    val baseUrl: String = "",
    /** Run the built-in offline demo backend instead (CI, demos, no keys). */
    val demoBackend: Boolean = true,
    /** On-prem: streaming ASR WebSocket path on [baseUrl]. */
    val asrPath: String = "",
    /** OpenAI-compatible chat endpoint serving the Hunyuan translation / minutes models. */
    val llmBaseUrl: String = "",
    /** Cloud: Tencent Cloud AppId for real-time ASR (not a secret). */
    val appId: String = "",
    /** Cloud: real-time ASR engine (engine_model_type) used for Chinese meetings. */
    val asrEngine: String = "",
    val translationModel: String = "",
    val summaryModel: String = "",
    /** Connect / per-request timeout; minutes generation gets [HunyuanDefaults.SUMMARY_TIMEOUT_FACTOR] times this. */
    val timeoutSec: Int = HunyuanDefaults.TIMEOUT_SEC,
) {
    val isCloud: Boolean get() = kind == ProfileKind.HUNYUAN_CLOUD
    val effectiveAsrPath: String get() = asrPath.ifBlank { HunyuanDefaults.ONPREM_ASR_PATH }
    val effectiveLlmBaseUrl: String
        get() = llmBaseUrl.trim().trimEnd('/').ifBlank {
            if (isCloud) HunyuanDefaults.CLOUD_LLM_BASE else baseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let { "$it/v1" }.orEmpty()
        }
    val effectiveAsrEngine: String get() = asrEngine.ifBlank { HunyuanDefaults.CLOUD_ASR_ENGINE_ZH }
    val effectiveTranslationModel: String
        get() = translationModel.ifBlank { if (isCloud) HunyuanDefaults.CLOUD_TRANSLATION_MODEL else HunyuanDefaults.ONPREM_TRANSLATION_MODEL }
    val effectiveSummaryModel: String
        get() = summaryModel.ifBlank { if (isCloud) HunyuanDefaults.CLOUD_SUMMARY_MODEL else HunyuanDefaults.ONPREM_SUMMARY_MODEL }
}

object HunyuanDefaults {
    const val TIMEOUT_SEC = 20
    const val SUMMARY_TIMEOUT_FACTOR = 6

    const val CLOUD_LLM_BASE = "https://api.hunyuan.cloud.tencent.com/v1"
    const val CLOUD_ASR_HOST = "asr.cloud.tencent.com"
    const val CLOUD_ASR_ENGINE_ZH = "16k_zh_large"
    const val CLOUD_TRANSLATION_MODEL = "hunyuan-translation"
    const val CLOUD_SUMMARY_MODEL = "hunyuan-turbos-latest"

    const val ONPREM_ASR_PATH = "/v1/asr/stream"
    const val ONPREM_TRANSLATION_MODEL = "Hunyuan-MT-7B"
    const val ONPREM_SUMMARY_MODEL = "Hunyuan-A13B-Instruct"
}

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
        ServerProfile(id = "hunyuan-cloud", kind = ProfileKind.HUNYUAN_CLOUD),
    ),
    val activeProfileId: String = "enterprise",
    /** Falling back from SCH-900S to Hunyuan cloud sends data off-premises: off unless an admin allows it. */
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
