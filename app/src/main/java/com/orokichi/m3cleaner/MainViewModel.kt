package com.orokichi.m3cleaner

import android.app.Application
import android.media.MediaScannerConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    val prefs = Prefs(app)

    var hasUsage by mutableStateOf(false); private set
    var hasFiles by mutableStateOf(false); private set
    var storage by mutableStateOf(Scanner.storage()); private set

    var apps by mutableStateOf<List<AppInfo>>(emptyList()); private set
    var files by mutableStateOf<List<FileInfo>>(emptyList()); private set
    var loadingApps by mutableStateOf(false); private set
    var loadingFiles by mutableStateOf(false); private set

    var aiLoading by mutableStateOf(false); private set
    var aiError by mutableStateOf<String?>(null)
    var aiResult by mutableStateOf<AiResult?>(null); private set
    private var aiApps: List<AppInfo> = emptyList()
    private var aiFiles: List<FileInfo> = emptyList()

    val selectedApps = mutableStateListOf<String>()
    val selectedFiles = mutableStateListOf<String>()
    var toast by mutableStateOf<String?>(null)

    fun refreshPerms() {
        hasUsage = Perms.hasUsageAccess(ctx)
        hasFiles = Perms.hasAllFiles()
        storage = Scanner.storage()
        if (apps.isEmpty() && !loadingApps) refreshApps()
        if (hasFiles && files.isEmpty() && !loadingFiles) refreshFiles()
    }

    fun refreshApps() {
        loadingApps = true
        viewModelScope.launch {
            apps = runCatching { Scanner.scanApps(ctx) }.getOrDefault(emptyList())
            loadingApps = false
        }
    }

    fun refreshFiles() {
        if (!Perms.hasAllFiles()) return
        loadingFiles = true
        viewModelScope.launch {
            files = runCatching { Scanner.scanFiles(prefs.minFileMb * 1024L * 1024L) }.getOrDefault(emptyList())
            loadingFiles = false
        }
    }

    fun runAi() {
        val key = prefs.apiKey
        if (key.isBlank()) { aiError = "設定タブでGroq APIキーを入力してください"; return }
        aiLoading = true; aiError = null
        viewModelScope.launch {
            try {
                if (apps.isEmpty()) apps = Scanner.scanApps(ctx)
                if (hasFiles && files.isEmpty()) files = Scanner.scanFiles(prefs.minFileMb * 1024L * 1024L)
                val days = prefs.unusedDays
                // 候補: 未使用期間が長い / 履歴なし のアプリを優先
                aiApps = apps.filter { (daysSince(it.lastUsed) ?: Int.MAX_VALUE) >= days }
                    .ifEmpty { apps }
                    .take(120)
                aiFiles = files.filter { (daysSince(it.lastAccess) ?: Int.MAX_VALUE) >= days }
                    .ifEmpty { files }
                    .take(150)
                aiResult = Groq.suggest(key, prefs.model, aiApps, aiFiles)
                selectedApps.clear(); selectedFiles.clear()
                aiResult?.apps?.filter { it.priority == "high" }?.mapNotNull { appFor(it.id)?.pkg }?.let { selectedApps.addAll(it) }
                aiResult?.files?.filter { it.priority == "high" }?.mapNotNull { fileFor(it.id)?.path }?.let { selectedFiles.addAll(it) }
            } catch (e: Exception) {
                aiError = e.message ?: e.toString()
            } finally {
                aiLoading = false
            }
        }
    }

    fun appFor(id: String): AppInfo? = id.removePrefix("a").toIntOrNull()?.let { aiApps.getOrNull(it) }
        ?.takeIf { a -> apps.any { it.pkg == a.pkg } }
    fun fileFor(id: String): FileInfo? = id.removePrefix("f").toIntOrNull()?.let { aiFiles.getOrNull(it) }
        ?.takeIf { f -> files.any { it.path == f.path } }

    fun onUninstallFinished(pkg: String) {
        if (!Scanner.isInstalled(ctx, pkg)) {
            apps = apps.filterNot { it.pkg == pkg }
            selectedApps.remove(pkg)
        }
        storage = Scanner.storage()
    }

    fun deleteFiles(paths: List<String>) {
        viewModelScope.launch {
            var ok = 0; var freed = 0L
            val deleted = withContext(Dispatchers.IO) {
                paths.filter { p ->
                    val f = File(p); val sz = f.length()
                    val r = f.delete()
                    if (r) { ok++; freed += sz }
                    r
                }
            }
            if (deleted.isNotEmpty()) {
                MediaScannerConnection.scanFile(ctx, deleted.toTypedArray(), null, null)
            }
            files = files.filterNot { it.path in deleted }
            selectedFiles.removeAll(deleted)
            storage = Scanner.storage()
            toast = "${ok}件削除 (${freed.fmtBytes()} 解放)" + if (ok < paths.size) " / ${paths.size - ok}件失敗" else ""
        }
    }
}
