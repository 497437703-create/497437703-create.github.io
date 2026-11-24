package com.aihuiji.meeting.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import com.aihuiji.meeting.audio.AudioDevices
import com.aihuiji.meeting.core.provider.HealthResult
import com.aihuiji.meeting.core.provider.ServerGateway

enum class CheckStatus { OK, WARN, ERROR }

enum class CheckItem { MIC, CAMERA, NETWORK, SERVER, STORAGE, PERMISSIONS }

data class CheckResult(val item: CheckItem, val status: CheckStatus, val detail: String)

/** S16b / F-21 manual self-check. Results are ordered for the sequential "light-up" animation. */
class DeviceChecker(private val context: Context) {

    fun mic(): CheckResult {
        val mic = AudioDevices.bestMic(context)
        return when {
            mic == null -> CheckResult(CheckItem.MIC, CheckStatus.ERROR, "")
            !mic.external -> CheckResult(CheckItem.MIC, CheckStatus.WARN, mic.name)
            else -> CheckResult(CheckItem.MIC, CheckStatus.OK, mic.name)
        }
    }

    fun camera(): CheckResult {
        val cm = context.getSystemService(CameraManager::class.java)
        val ids = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        val external = ids.firstOrNull { id ->
            runCatching { cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL }.getOrDefault(false)
        }
        return when {
            external != null -> CheckResult(CheckItem.CAMERA, CheckStatus.OK, external)
            ids.isNotEmpty() -> CheckResult(CheckItem.CAMERA, CheckStatus.OK, ids.first())
            else -> CheckResult(CheckItem.CAMERA, CheckStatus.WARN, "")
        }
    }

    fun network(): CheckResult {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities)
            ?: return CheckResult(CheckItem.NETWORK, CheckStatus.ERROR, "")
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            else -> "other"
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return CheckResult(CheckItem.NETWORK, if (validated) CheckStatus.OK else CheckStatus.WARN, kind)
    }

    suspend fun server(gateway: ServerGateway): CheckResult = when (val h = gateway.health()) {
        is HealthResult.Ok -> CheckResult(CheckItem.SERVER, CheckStatus.OK, "${h.latencyMs} ms")
        is HealthResult.Unavailable -> CheckResult(CheckItem.SERVER, CheckStatus.ERROR, h.reason)
        HealthResult.NotConfigured -> CheckResult(CheckItem.SERVER, CheckStatus.ERROR, "")
    }

    fun storage(): CheckResult {
        val free = context.filesDir.usableSpace
        val gb = free / (1024.0 * 1024 * 1024)
        val status = when {
            gb < 0.5 -> CheckStatus.ERROR
            gb < 2 -> CheckStatus.WARN
            else -> CheckStatus.OK
        }
        return CheckResult(CheckItem.STORAGE, status, "%.1f GB".format(gb))
    }

    fun permissions(): CheckResult {
        val mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(context)
        val status = when {
            !mic -> CheckStatus.ERROR
            !overlay -> CheckStatus.WARN
            else -> CheckStatus.OK
        }
        return CheckResult(CheckItem.PERMISSIONS, status, "")
    }

    suspend fun runAll(gateway: ServerGateway, includeCamera: Boolean): List<CheckResult> = buildList {
        add(mic())
        if (includeCamera) add(camera())
        add(network())
        add(server(gateway))
        add(storage())
        add(permissions())
    }
}
