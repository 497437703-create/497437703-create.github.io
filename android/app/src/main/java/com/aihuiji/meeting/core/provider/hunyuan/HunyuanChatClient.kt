package com.aihuiji.meeting.core.provider.hunyuan

import com.aihuiji.meeting.core.provider.ProviderErrorKind
import com.aihuiji.meeting.core.provider.ProviderErrors
import com.aihuiji.meeting.core.provider.ProviderException
import com.aihuiji.meeting.core.provider.ServiceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.3,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
data class ChatChoice(val message: ChatMessage? = null)

@Serializable
data class ChatErrorDto(val message: String = "", val code: String? = null, val type: String? = null)

@Serializable
data class ChatResponse(val choices: List<ChatChoice> = emptyList(), val error: ChatErrorDto? = null)

/**
 * OpenAI-compatible `POST {base}/chat/completions` client. Tencent Hunyuan cloud exposes this API
 * (`https://api.hunyuan.cloud.tencent.com/v1`, `Authorization: Bearer <API key>`), and the same
 * Hunyuan models on SCH-900S are served the same way (vLLM / IPEX-LLM on Intel Arc B70), so one client
 * covers both deployments; only base URL, key and model names differ.
 */
class HunyuanChatClient(
    baseUrl: String,
    private val apiKey: () -> String?,
    val timeoutSec: Int,
    private val onPrem: Boolean,
    http: OkHttpClient? = null,
) {
    val base: String = baseUrl.trim().trimEnd('/')
    private val http: OkHttpClient = http ?: OkHttpClient.Builder()
        .connectTimeout(timeoutSec.toLong().coerceAtMost(10), TimeUnit.SECONDS)
        .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun complete(
        model: String,
        messages: List<ChatMessage>,
        service: ServiceKind,
        temperature: Double = 0.3,
        maxTokens: Int? = null,
        callTimeoutSec: Int = timeoutSec,
    ): String = withContext(Dispatchers.IO) {
        val endpoint = "$base/chat/completions"
        if (base.isEmpty()) throw ProviderException(ProviderErrorKind.NOT_CONFIGURED, service, detail = "llmBaseUrl", onPrem = onPrem)
        val body = json.encodeToString(ChatRequest(model, messages, temperature, maxTokens = maxTokens))
        val req = Request.Builder().url(endpoint).post(body.toRequestBody(jsonType)).apply {
            apiKey()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        }.build()
        val call = http.newBuilder().callTimeout(callTimeoutSec.toLong(), TimeUnit.SECONDS).build().newCall(req)
        try {
            call.execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { json.decodeFromString<ChatResponse>(text).error?.message }.getOrNull()
                    throw ProviderErrors.forHttp(resp.code, msg ?: text, service, endpoint, onPrem)
                }
                val parsed = json.decodeFromString<ChatResponse>(text)
                parsed.error?.let { throw ProviderException(ProviderErrorKind.SERVER, service, endpoint, it.message, onPrem) }
                parsed.choices.firstOrNull()?.message?.content
                    ?: throw ProviderException(ProviderErrorKind.BAD_RESPONSE, service, endpoint, "no choices", onPrem)
            }
        } catch (e: Exception) {
            throw ProviderErrors.classify(e, service, endpoint, onPrem, callTimeoutSec)
        }
    }

    /** Cheapest call that proves endpoint, key and model name: one output token. */
    suspend fun ping(model: String) {
        complete(model, listOf(ChatMessage("user", "ping")), ServiceKind.GATEWAY, maxTokens = 1)
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}
