package com.aihuiji.meeting.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler

data class MicInfo(val name: String, val external: Boolean, val device: AudioDeviceInfo?)

object AudioDevices {
    private val externalTypes = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
    )

    /** G1 has no built-in mic: prefer the certified USB omni mic, then the rear MIC In jack. */
    fun bestMic(context: Context): MicInfo? {
        val am = context.getSystemService(AudioManager::class.java)
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val usb = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        val external = usb ?: inputs.firstOrNull { it.type in externalTypes }
        if (external != null) return MicInfo(external.productName?.toString().orEmpty().ifBlank { "USB" }, true, external)
        val builtin = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } ?: return null
        return MicInfo(builtin.productName?.toString().orEmpty(), false, builtin)
    }

    fun platformAecAvailable(): Boolean = AcousticEchoCanceler.isAvailable()
}
