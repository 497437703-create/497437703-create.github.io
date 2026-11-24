package com.aihuiji.meeting.core.provider

import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class ServiceKind { GATEWAY, ASR, TRANSLATION, SUMMARY, SHARE }

enum class ProviderErrorKind {
    /** Required endpoint or credential is missing in settings. */
    NOT_CONFIGURED,
    /** DNS, refused connection, no route or TLS failure: the server could not be reached at all. */
    UNREACHABLE,
    /** The server answered but rejected the token / signature / API key. */
    AUTH,
    /** Account out of quota, arrears or concurrency limit. */
    QUOTA,
    TIMEOUT,
    SERVER,
    /** The server answered with something the client cannot parse. */
    BAD_RESPONSE,
}

/**
 * A backend failure the UI can explain: what failed ([service]), why ([kind]), where ([endpoint])
 * and on which deployment ([onPrem]). Providers throw or publish this instead of raw I/O errors.
 */
class ProviderException(
    val kind: ProviderErrorKind,
    val service: ServiceKind,
    val endpoint: String = "",
    val detail: String = "",
    val onPrem: Boolean = true,
    val timeoutSec: Int = 0,
    cause: Throwable? = null,
) : IOException("$service $kind${if (endpoint.isNotEmpty()) " @ $endpoint" else ""}${if (detail.isNotEmpty()) ": $detail" else ""}", cause) {
    /** Auth, quota and missing config will not fix themselves; retrying only hides the problem. */
    val fatal: Boolean get() = kind == ProviderErrorKind.AUTH || kind == ProviderErrorKind.QUOTA || kind == ProviderErrorKind.NOT_CONFIGURED

    fun withService(s: ServiceKind) = if (s == service) this else ProviderException(kind, s, endpoint, detail, onPrem, timeoutSec, cause)
}

object ProviderErrors {
    fun classify(t: Throwable, service: ServiceKind, endpoint: String, onPrem: Boolean, timeoutSec: Int = 0): ProviderException = when (t) {
        is ProviderException -> t.withService(service)
        is SocketTimeoutException, is TimeoutCancellationException ->
            ProviderException(ProviderErrorKind.TIMEOUT, service, endpoint, t.message.orEmpty(), onPrem, timeoutSec, t)
        is UnknownHostException, is ConnectException, is NoRouteToHostException, is SSLException ->
            ProviderException(ProviderErrorKind.UNREACHABLE, service, endpoint, t.message ?: t.javaClass.simpleName, onPrem, timeoutSec, t)
        // OkHttp reports its call timeout as a bare InterruptedIOException("timeout").
        is InterruptedIOException ->
            ProviderException(ProviderErrorKind.TIMEOUT, service, endpoint, t.message.orEmpty(), onPrem, timeoutSec, t)
        is kotlinx.serialization.SerializationException ->
            ProviderException(ProviderErrorKind.BAD_RESPONSE, service, endpoint, t.message.orEmpty(), onPrem, timeoutSec, t)
        else -> ProviderException(ProviderErrorKind.SERVER, service, endpoint, t.message ?: t.javaClass.simpleName, onPrem, timeoutSec, t)
    }

    fun forHttp(code: Int, body: String, service: ServiceKind, endpoint: String, onPrem: Boolean): ProviderException {
        val kind = when (code) {
            401, 403 -> ProviderErrorKind.AUTH
            402, 429 -> ProviderErrorKind.QUOTA
            408, 504 -> ProviderErrorKind.TIMEOUT
            else -> ProviderErrorKind.SERVER
        }
        return ProviderException(kind, service, endpoint, "HTTP $code ${body.take(200)}".trim(), onPrem)
    }
}
