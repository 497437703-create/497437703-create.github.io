package com.aihuiji.meeting.core.provider.hunyuan

import com.aihuiji.meeting.core.model.Channel
import com.aihuiji.meeting.core.model.HunyuanDefaults
import com.aihuiji.meeting.core.model.Lang
import com.aihuiji.meeting.core.model.LinkState
import com.aihuiji.meeting.core.model.MeetingRecord
import com.aihuiji.meeting.core.model.ServerProfile
import com.aihuiji.meeting.core.model.ShareLink
import com.aihuiji.meeting.core.model.Summary
import com.aihuiji.meeting.core.model.Templates
import com.aihuiji.meeting.core.provider.AsrEvent
import com.aihuiji.meeting.core.provider.AsrProvider
import com.aihuiji.meeting.core.provider.AsrSession
import com.aihuiji.meeting.core.provider.AsrSessionConfig
import com.aihuiji.meeting.core.provider.AudioFrame
import com.aihuiji.meeting.core.provider.BackendKind
import com.aihuiji.meeting.core.provider.HealthResult
import com.aihuiji.meeting.core.provider.MinutesProvider
import com.aihuiji.meeting.core.provider.MinutesRequest
import com.aihuiji.meeting.core.provider.ProviderBundle
import com.aihuiji.meeting.core.provider.ProviderErrorKind
import com.aihuiji.meeting.core.provider.ProviderErrors
import com.aihuiji.meeting.core.provider.ProviderException
import com.aihuiji.meeting.core.provider.ProviderLocation
import com.aihuiji.meeting.core.provider.ServerCapabilities
import com.aihuiji.meeting.core.provider.ServerGateway
import com.aihuiji.meeting.core.provider.ServiceKind
import com.aihuiji.meeting.core.provider.SpeakerIdentity
import com.aihuiji.meeting.core.provider.SpeakerProvider
import com.aihuiji.meeting.core.provider.TranslationProvider
import com.aihuiji.meeting.core.provider.remote.RemoteAsrProvider
import com.aihuiji.meeting.core.provider.remote.RemoteClient
import com.aihuiji.meeting.core.provider.remote.RemoteGateway
import com.aihuiji.meeting.core.provider.remote.RemoteMinutesProvider
import com.aihuiji.meeting.core.provider.remote.RemoteSpeakerProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class HunyuanTranslationProvider(private val chat: HunyuanChatClient, private val model: String) : TranslationProvider {
    override suspend fun translate(text: String, from: Lang, to: Lang): String =
        HunyuanPrompts.cleanTranslation(chat.complete(model, HunyuanPrompts.translation(text, from, to), ServiceKind.TRANSLATION, temperature = 0.2))
}

/**
 * Minutes from a Hunyuan chat model. The model cites transcript line numbers, which become the
 * `sourceRefs` that 精准回溯 jumps to; items it cannot anchor are marked inferred (F-25).
 */
class HunyuanMinutesProvider(
    private val chat: HunyuanChatClient,
    private val model: String,
    private val share: suspend (MeetingRecord) -> ShareLink,
) : MinutesProvider {
    override suspend fun generate(request: MinutesRequest, onProgress: (Float) -> Unit): Summary {
        onProgress(0.1f)
        val reply = chat.complete(
            model,
            HunyuanPrompts.minutes(request),
            ServiceKind.SUMMARY,
            temperature = 0.2,
            callTimeoutSec = chat.timeoutSec * HunyuanDefaults.SUMMARY_TIMEOUT_FACTOR,
        )
        onProgress(0.9f)
        val summary = try {
            HunyuanPrompts.parseMinutes(reply, request)
        } catch (e: Exception) {
            throw ProviderException(ProviderErrorKind.BAD_RESPONSE, ServiceKind.SUMMARY, chat.base, e.message.orEmpty(), cause = e)
        }
        onProgress(1f)
        return summary
    }

    override suspend fun createShareLink(record: MeetingRecord): ShareLink = share(record)
}

/** Hunyuan cloud has no voiceprint service: speakers come from the recogniser's own labels per channel. */
object StreamLabelSpeakerProvider : SpeakerProvider {
    override val location = ProviderLocation.REMOTE
    override suspend fun resolve(meetingId: String, channel: Channel, diarizationLabel: String?, startMs: Long, endMs: Long) =
        SpeakerIdentity("${channel.name}:${diarizationLabel ?: "U"}", null, 0f)
}

/** Session for a backend that cannot run (missing config): no captions, the reason in [error]. */
class FailedAsrSession(e: ProviderException) : AsrSession {
    override val events: SharedFlow<AsrEvent> = MutableSharedFlow()
    override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.OFFLINE)
    override val error: StateFlow<ProviderException?> = MutableStateFlow(e)
    override fun sendAudio(frame: AudioFrame) = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override suspend fun finish() = Unit
    override fun close() = Unit
}

private fun capabilities(modelVersion: String, speakers: Boolean) = ServerCapabilities(
    asrLangs = Lang.entries.toSet(),
    translationTargets = Lang.entries.associateWith { from -> Lang.entries.toSet() - from },
    templateIds = Templates.all.map { it.id }.toSet(),
    speakerRecognition = speakers,
    modelVersion = modelVersion,
)

private fun unavailable(e: ProviderException) = HealthResult.Unavailable(e.message.orEmpty(), e)

/** Builds the provider bundle for each Hunyuan deployment from a [ServerProfile] and vault-backed secrets. */
object HunyuanBackend {

    /**
     * SCH-900S: streaming ASR and speaker resolution through the enterprise gateway contract
     * ([com.aihuiji.meeting.core.provider.remote.RemoteContract]); translation and minutes through the
     * on-prem Hunyuan models' OpenAI-compatible endpoint. One bearer token authorises both.
     */
    fun onPrem(profile: ServerProfile, token: () -> String?, spoolDir: File): ProviderBundle {
        val base = profile.baseUrl.trim()
        val chat = HunyuanChatClient(profile.effectiveLlmBaseUrl, token, profile.timeoutSec, onPrem = true)
        val client = RemoteClient(base.ifBlank { "http://unset.invalid" }, profile.timeoutSec, token)
        val notConfigured = if (base.isBlank()) ProviderException(ProviderErrorKind.NOT_CONFIGURED, ServiceKind.GATEWAY, detail = "baseUrl") else null
        val remoteAsr = RemoteAsrProvider(client, spoolDir, profile.effectiveAsrPath)
        val remoteMinutes = RemoteMinutesProvider(client)
        return ProviderBundle(
            location = ProviderLocation.REMOTE,
            backend = BackendKind.SCH900S_ON_PREM,
            gateway = object : ServerGateway {
                override suspend fun health(): HealthResult {
                    notConfigured?.let { return unavailable(it) }
                    val gw = RemoteGateway(client).health()
                    if (gw !is HealthResult.Ok) return gw
                    return try {
                        chat.ping(profile.effectiveSummaryModel)
                        gw.copy(capabilities = gw.capabilities.copy(modelVersion = "${gw.capabilities.modelVersion} · ${profile.effectiveSummaryModel}".trim(' ', '·')))
                    } catch (e: ProviderException) {
                        unavailable(e)
                    }
                }
            },
            asr = object : AsrProvider {
                override val location = ProviderLocation.REMOTE
                override suspend fun open(config: AsrSessionConfig): AsrSession =
                    notConfigured?.let { FailedAsrSession(it.withService(ServiceKind.ASR)) } ?: remoteAsr.open(config)
            },
            translation = HunyuanTranslationProvider(chat, profile.effectiveTranslationModel),
            speaker = RemoteSpeakerProvider(client),
            minutes = HunyuanMinutesProvider(chat, profile.effectiveSummaryModel) { record ->
                notConfigured?.let { throw it.withService(ServiceKind.SHARE) }
                try {
                    remoteMinutes.createShareLink(record)
                } catch (e: Exception) {
                    throw ProviderErrors.classify(e, ServiceKind.SHARE, base, onPrem = true)
                }
            },
        )
    }

    /**
     * Tencent Hunyuan cloud: real-time ASR over Tencent Cloud's signed WebSocket, translation and
     * minutes through the Hunyuan OpenAI-compatible API. Share links need an optional share service
     * (the profile's base URL, same `/v1/meetings/{id}/share` contract as SCH-900S); without one the
     * takeaway QR falls back to the on-device link.
     */
    fun cloud(
        profile: ServerProfile,
        apiKey: () -> String?,
        asrCreds: TencentAsrCredentials,
        shareToken: () -> String?,
        spoolDir: File,
    ): ProviderBundle {
        val chat = HunyuanChatClient(profile.effectiveLlmBaseUrl, apiKey, profile.timeoutSec, onPrem = false)
        val shareBase = profile.baseUrl.trim()
        val shareMinutes = shareBase.takeIf { it.isNotEmpty() }?.let { RemoteMinutesProvider(RemoteClient(it, profile.timeoutSec, shareToken)) }
        return ProviderBundle(
            location = ProviderLocation.REMOTE,
            backend = BackendKind.HUNYUAN_CLOUD,
            gateway = object : ServerGateway {
                override suspend fun health(): HealthResult {
                    if (apiKey().isNullOrBlank()) {
                        return unavailable(ProviderException(ProviderErrorKind.NOT_CONFIGURED, ServiceKind.SUMMARY, chat.base, "API Key", onPrem = false))
                    }
                    val start = System.nanoTime()
                    TencentAsrProbe.check(asrCreds, profile.effectiveAsrEngine, profile.timeoutSec)?.let { return unavailable(it) }
                    return try {
                        chat.ping(profile.effectiveSummaryModel)
                        HealthResult.Ok((System.nanoTime() - start) / 1_000_000, capabilities("${profile.effectiveAsrEngine} · ${profile.effectiveSummaryModel}", speakers = false))
                    } catch (e: ProviderException) {
                        unavailable(e)
                    }
                }
            },
            asr = TencentAsrProvider(asrCreds, profile.effectiveAsrEngine, spoolDir, profile.timeoutSec),
            translation = HunyuanTranslationProvider(chat, profile.effectiveTranslationModel),
            speaker = StreamLabelSpeakerProvider,
            minutes = HunyuanMinutesProvider(chat, profile.effectiveSummaryModel) { record ->
                val m = shareMinutes ?: throw ProviderException(ProviderErrorKind.NOT_CONFIGURED, ServiceKind.SHARE, detail = "baseUrl", onPrem = false)
                try {
                    m.createShareLink(record)
                } catch (e: Exception) {
                    throw ProviderErrors.classify(e, ServiceKind.SHARE, shareBase, onPrem = false)
                }
            },
        )
    }
}
