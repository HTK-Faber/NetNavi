package com.tuo.navi.habits

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap

data class AppEntry(val label: String, val pkg: String, val icon: Bitmap, val category: String)

object AppRepo {
    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { ri ->
                val ai = ri.activityInfo.applicationInfo
                AppEntry(ri.loadLabel(pm).toString(), ri.activityInfo.packageName,
                    ri.loadIcon(pm).toBitmap(96, 96), categoryName(ai.category))
            }
            .sortedBy { it.label.lowercase() }
    }

    fun categoryName(c: Int): String = when (c) {
        ApplicationInfo.CATEGORY_GAME -> "giochi"
        ApplicationInfo.CATEGORY_AUDIO -> "audio e musica"
        ApplicationInfo.CATEGORY_VIDEO -> "video"
        ApplicationInfo.CATEGORY_IMAGE -> "foto e immagini"
        ApplicationInfo.CATEGORY_SOCIAL -> "social e messaggi"
        ApplicationInfo.CATEGORY_NEWS -> "notizie"
        ApplicationInfo.CATEGORY_MAPS -> "mappe e navigazione"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "produttività"
        ApplicationInfo.CATEGORY_ACCESSIBILITY -> "accessibilità"
        else -> "generica"
    }

    fun find(apps: List<AppEntry>, name: String): AppEntry? {
        val n = name.lowercase().trim().removePrefix("l' ").removePrefix("app ")
        return apps.firstOrNull { it.label.lowercase() == n }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(n) }
            ?: apps.firstOrNull { it.label.lowercase().contains(n) }
    }
}
