package com.aihuiji.meeting.vc

import android.content.Context
import android.content.Intent

data class VcApp(val packageName: String, val label: String)

/**
 * Opens the existing video-conference app beside AI 会记. M1 integrates at window level only
 * (no conference SDK): FLAG_ACTIVITY_LAUNCH_ADJACENT asks the system for split-screen.
 */
object VcLauncher {
    val known = listOf(
        "com.tencent.wemeet.app",
        "com.alibaba.android.rimet",
        "com.ss.android.lark",
        "com.microsoft.teams",
        "com.tencent.wework",
    )

    fun installedApp(context: Context, preferred: String): VcApp? {
        val pm = context.packageManager
        return (listOf(preferred) + known).distinct().firstNotNullOfOrNull { pkg ->
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                if (pm.getLaunchIntentForPackage(pkg) == null) null else VcApp(pkg, pm.getApplicationLabel(info).toString())
            }.getOrNull()
        }
    }

    fun launchAdjacent(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        context.startActivity(intent)
        return true
    }

    fun launchFullscreen(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
