package com.orokichi.m3cleaner

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class AppInfo(
    val pkg: String,
    val label: String,
    val lastUsed: Long,
    val foregroundMs: Long,
    val sizeBytes: Long,
    val installed: Long,
)

data class FileInfo(
    val path: String,
    val name: String,
    val size: Long,
    val lastAccess: Long,
    val lastModified: Long,
) {
    val folder: String get() = File(path).parent?.removePrefix(Environment.getExternalStorageDirectory().absolutePath) ?: ""
    val ext: String get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}

data class StorageSummary(val total: Long, val free: Long)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("m3cleaner", Context.MODE_PRIVATE)
    var apiKey: String
        get() = sp.getString("groq_key", "") ?: ""
        set(v) = sp.edit().putString("groq_key", v.trim()).apply()
    var model: String
        get() = sp.getString("groq_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("groq_model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()
    var unusedDays: Int
        get() = sp.getInt("unused_days", 30)
        set(v) = sp.edit().putInt("unused_days", v).apply()
    var minFileMb: Int
        get() = sp.getInt("min_file_mb", 5)
        set(v) = sp.edit().putInt("min_file_mb", v).apply()

    companion object {
        const val DEFAULT_MODEL = "llama-3.3-70b-versatile"
    }
}

object Perms {
    fun hasUsageAccess(ctx: Context): Boolean {
        val aom = ctx.getSystemService(AppOpsManager::class.java)
        val mode = aom.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasAllFiles(): Boolean = Environment.isExternalStorageManager()
}

object Scanner {
    private const val DAY = 24L * 60 * 60 * 1000

    fun storage(): StorageSummary {
        val st = StatFs(Environment.getDataDirectory().path)
        return StorageSummary(st.totalBytes, st.availableBytes)
    }

    @Suppress("DEPRECATION")
    suspend fun scanApps(ctx: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val now = System.currentTimeMillis()
        val usage: Map<String, android.app.usage.UsageStats> = try {
            val usm = ctx.getSystemService(UsageStatsManager::class.java)
            usm.queryAndAggregateUsageStats(now - 180 * DAY, now)
        } catch (e: Exception) { emptyMap() }
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        pm.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != ctx.packageName }
            .map { ai ->
                val us = usage[ai.packageName]
                val size = try {
                    val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle())
                    s.appBytes + s.dataBytes + s.cacheBytes
                } catch (e: Exception) { 0L }
                val installed = try { pm.getPackageInfo(ai.packageName, 0).firstInstallTime } catch (e: PackageManager.NameNotFoundException) { 0L }
                AppInfo(
                    pkg = ai.packageName,
                    label = pm.getApplicationLabel(ai).toString(),
                    lastUsed = us?.lastTimeUsed?.takeIf { it > 0 } ?: 0L,
                    foregroundMs = us?.totalTimeInForeground ?: 0L,
                    sizeBytes = size,
                    installed = installed,
                )
            }
            .sortedWith(compareBy<AppInfo> { it.lastUsed }.thenByDescending { it.sizeBytes })
    }

    suspend fun scanFiles(minBytes: Long, limit: Int = 3000): List<FileInfo> = withContext(Dispatchers.IO) {
        val root = Environment.getExternalStorageDirectory()
        val out = ArrayList<FileInfo>()
        root.walkTopDown()
            .onEnter { dir ->
                !(dir.parentFile?.absolutePath == root.absolutePath && dir.name == "Android") &&
                    !dir.name.startsWith(".")
            }
            .filter { it.isFile && it.length() >= minBytes }
            .forEach { f ->
                val mtime = f.lastModified()
                val atime = try { Os.stat(f.absolutePath).st_atime * 1000L } catch (e: Exception) { 0L }
                out.add(FileInfo(f.absolutePath, f.name, f.length(), maxOf(atime, mtime), mtime))
            }
        out.sortedByDescending { it.size }.take(limit)
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }
}

fun Long.fmtBytes(): String {
    if (this <= 0) return "0 B"
    val u = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = this.toDouble(); var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return if (i <= 1) String.format(Locale.ROOT, "%.0f %s", v, u[i]) else String.format(Locale.ROOT, "%.1f %s", v, u[i])
}

fun daysSince(t: Long): Int? = if (t <= 0) null else ((System.currentTimeMillis() - t) / (24L * 60 * 60 * 1000)).toInt()

fun Long.ago(): String = when (val d = daysSince(this)) {
    null -> "記録なし"
    0 -> "今日"
    else -> "${d}日前"
}
