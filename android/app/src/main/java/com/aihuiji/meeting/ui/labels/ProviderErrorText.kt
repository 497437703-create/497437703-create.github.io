package com.aihuiji.meeting.ui.labels

import android.content.Context
import com.aihuiji.meeting.R
import com.aihuiji.meeting.core.provider.ProviderErrorKind
import com.aihuiji.meeting.core.provider.ProviderException
import com.aihuiji.meeting.core.provider.ServiceKind

/** One-line, actionable explanation of a backend failure for the floating window, minutes screen and settings. */
object ProviderErrorText {
    fun describe(context: Context, t: Throwable): String {
        val e = t as? ProviderException ?: return t.message ?: t.javaClass.simpleName
        val service = context.getString(
            when (e.service) {
                ServiceKind.GATEWAY -> R.string.svc_gateway
                ServiceKind.ASR -> R.string.svc_asr
                ServiceKind.TRANSLATION -> R.string.svc_translation
                ServiceKind.SUMMARY -> R.string.svc_summary
                ServiceKind.SHARE -> R.string.svc_share
            },
        )
        val backend = context.getString(if (e.onPrem) R.string.backend_onprem_short else R.string.backend_cloud_short)
        val where = e.endpoint.ifBlank { backend }
        return when (e.kind) {
            ProviderErrorKind.NOT_CONFIGURED -> context.getString(R.string.err_not_configured, backend, service, e.detail)
            ProviderErrorKind.UNREACHABLE ->
                if (e.onPrem) context.getString(R.string.err_unreachable_onprem, where) else context.getString(R.string.err_unreachable_cloud, where)
            ProviderErrorKind.AUTH ->
                if (e.onPrem) context.getString(R.string.err_auth_onprem, e.detail) else context.getString(R.string.err_auth_cloud, e.detail)
            ProviderErrorKind.QUOTA -> context.getString(R.string.err_quota, backend, service, e.detail)
            ProviderErrorKind.TIMEOUT -> context.getString(R.string.err_timeout, service, backend, e.timeoutSec.takeIf { it > 0 }?.toString() ?: "-", where)
            ProviderErrorKind.SERVER -> context.getString(R.string.err_server, backend, service, e.detail)
            ProviderErrorKind.BAD_RESPONSE -> context.getString(R.string.err_bad_response, backend, service, e.detail)
        }
    }
}
