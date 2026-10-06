package com.boopathi.bg.service.tool

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

data class AppInfo(
    val appName: String,
    val packageName: String,
    val isInstalled: Boolean
)

class AppResolver(private val context: Context) {

    // Common known application packages for popular apps
    private val knownAppPackages = mapOf(
        "youtube" to listOf("com.google.android.youtube", "com.google.android.youtube.tv"),
        "instagram" to listOf("com.instagram.android"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "gmail" to listOf("com.google.android.gm"),
        "chrome" to listOf("com.android.chrome"),
        "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
        "settings" to listOf("com.android.settings"),
        "camera" to listOf("com.google.android.GoogleCamera", "com.android.camera", "com.android.camera2"),
        "calculator" to listOf("com.google.android.calculator", "com.android.calculator2"),
        "maps" to listOf("com.google.android.apps.maps"),
        "play store" to listOf("com.android.vending"),
        "playstore" to listOf("com.android.vending"),
        "clock" to listOf("com.google.android.deskclock", "com.android.deskclock"),
        "calendar" to listOf("com.google.android.calendar"),
        "contacts" to listOf("com.google.android.contacts", "com.android.contacts"),
        "files" to listOf("com.google.android.apps.nbu.files", "com.android.documentsui"),
        "messages" to listOf("com.google.android.apps.messaging", "com.android.mms"),
        "phone" to listOf("com.google.android.dialer", "com.android.dialer"),
        "spotify" to listOf("com.spotify.music"),
        "netflix" to listOf("com.netflix.mediaclient"),
        "twitter" to listOf("com.twitter.android"),
        "x" to listOf("com.twitter.android")
    )

    fun resolveApp(query: String): AppInfo? {
        val cleanQuery = query.trim().lowercase()
        val pm = context.packageManager

        // 1. Check known mappings first
        val targetPackages = knownAppPackages[cleanQuery]
        if (targetPackages != null) {
            for (pkg in targetPackages) {
                if (isPackageInstalled(pkg)) {
                    val appName = try {
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        formatDisplayName(cleanQuery)
                    }
                    return AppInfo(appName = appName, packageName = pkg, isInstalled = true)
                }
            }
        }

        // 2. Query all installed launcher activities dynamically
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }
        } catch (e: Exception) {
            emptyList()
        }

        // Exact match on launcher label
        for (info in resolveInfos) {
            val label = info.loadLabel(pm).toString()
            if (label.equals(cleanQuery, ignoreCase = true)) {
                return AppInfo(
                    appName = label,
                    packageName = info.activityInfo.packageName,
                    isInstalled = true
                )
            }
        }

        // Partial match on launcher label
        for (info in resolveInfos) {
            val label = info.loadLabel(pm).toString()
            if (label.lowercase().contains(cleanQuery) || cleanQuery.contains(label.lowercase())) {
                return AppInfo(
                    appName = label,
                    packageName = info.activityInfo.packageName,
                    isInstalled = true
                )
            }
        }

        // Match on package name
        for (info in resolveInfos) {
            val pkg = info.activityInfo.packageName.lowercase()
            if (pkg.contains(cleanQuery)) {
                return AppInfo(
                    appName = info.loadLabel(pm).toString(),
                    packageName = info.activityInfo.packageName,
                    isInstalled = true
                )
            }
        }

        return null
    }

    fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun formatDisplayName(query: String): String {
        return query.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}

