package com.orokichi.m3cleaner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { Root() } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab(val title: String) { AI("AI提案"), APPS("アプリ"), FILES("ファイル"), SETTINGS("設定") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshPerms() }
    var tab by rememberSaveable { mutableStateOf(Tab.AI) }

    // 連続アンインストール用キュー
    val queue = remember { mutableStateListOf<String>() }
    var current by remember { mutableStateOf<String?>(null) }
    val uninstaller = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        current?.let { vm.onUninstallFinished(it) }
        current = null
    }
    LaunchedEffect(current, queue.size) {
        if (current == null && queue.isNotEmpty()) {
            val pkg = queue.removeAt(0)
            current = pkg
            @Suppress("DEPRECATION")
            val i = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$pkg"))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            runCatching { uninstaller.launch(i) }.onFailure { current = null }
        }
    }
    val uninstall: (List<String>) -> Unit = { pkgs -> queue.addAll(pkgs.filterNot { it in queue }) }

    vm.toast?.let { msg ->
        LaunchedEffect(msg) { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show(); vm.toast = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("M3 Cleaner", fontWeight = FontWeight.SemiBold) })
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.AI -> Icons.Outlined.AutoAwesome
                                    Tab.APPS -> Icons.Outlined.Apps
                                    Tab.FILES -> Icons.Outlined.FolderOpen
                                    Tab.SETTINGS -> Icons.Outlined.Settings
                                }, null
                            )
                        },
                        label = { Text(t.title) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.AI -> AiScreen(vm, uninstall)
                Tab.APPS -> AppsScreen(vm, uninstall)
                Tab.FILES -> FilesScreen(vm)
                Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
fun StorageCard(vm: MainViewModel) {
    val s = vm.storage
    val used = s.total - s.free
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("ストレージ", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("${used.fmtBytes()} / ${s.total.fmtBytes()} 使用中", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { if (s.total > 0) used.toFloat() / s.total else 0f },
                modifier = Modifier.fillMaxWidth().height(10.dp),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            Spacer(Modifier.height(8.dp))
            Text("空き ${s.free.fmtBytes()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PermissionCards(vm: MainViewModel) {
    val ctx = LocalContext.current
    if (!vm.hasUsage) {
        PermCard(
            "使用状況へのアクセス",
            "アプリの最終使用日・使用時間・容量を分析するために必要です。",
        ) { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }
    if (!vm.hasFiles) {
        PermCard(
            "すべてのファイルへのアクセス",
            "大きなファイルや長く開いていないファイルを探して消去するために必要です。",
        ) {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            runCatching { ctx.startActivity(i) }.onFailure {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }
}

@Composable
fun PermCard(title: String, desc: String, onGrant: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, null)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(desc, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = onGrant) { Text("許可する") }
        }
    }
}

@Composable
fun AppIcon(pkg: String, size: Int = 44) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    if (bmp != null) Image(bmp, null, Modifier.size(size.dp))
    else Icon(Icons.Outlined.Android, null, Modifier.size(size.dp))
}

@Composable
fun FileIcon(f: FileInfo) {
    val icon = when (f.ext) {
        "jpg", "jpeg", "png", "webp", "heic", "gif" -> Icons.Outlined.Image
        "mp4", "mov", "mkv", "webm", "3gp" -> Icons.Outlined.Movie
        "mp3", "m4a", "wav", "flac", "ogg", "aac" -> Icons.Outlined.MusicNote
        "apk", "apks", "xapk" -> Icons.Outlined.Android
        "zip", "rar", "7z", "tar", "gz" -> Icons.Outlined.FolderZip
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt" -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
    Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
}

@Composable
fun PriorityChip(p: String) {
    val (label, color) = when (p) {
        "high" -> "おすすめ" to MaterialTheme.colorScheme.errorContainer
        "low" -> "要確認" to MaterialTheme.colorScheme.surfaceVariant
        else -> "検討" to MaterialTheme.colorScheme.secondaryContainer
    }
    Surface(shape = RoundedCornerShape(50), color = color) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun AiScreen(vm: MainViewModel, uninstall: (List<String>) -> Unit) {
    var confirmFiles by remember { mutableStateOf(false) }
    val res = vm.aiResult
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { StorageCard(vm) }
        item { PermissionCards(vm) }
        item {
            Button(
                onClick = { vm.runAi() },
                enabled = !vm.aiLoading,
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 4.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                if (vm.aiLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp)); Text("AIが分析中…")
                } else {
                    Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("AIで整理候補を提案")
                }
            }
        }
        vm.aiError?.let { e ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(e, Modifier.padding(16.dp))
                }
            }
        }
        if (res != null) {
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("AIの分析", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(res.summary.ifBlank { "提案が生成されました。" })
                    }
                }
            }
            val appItems = res.apps.mapNotNull { ai -> vm.appFor(ai.id)?.let { it to ai } }
            item { SectionHeader("アンインストール候補 (${appItems.size})") }
            if (appItems.isEmpty()) item { Text("候補はありません", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(appItems, key = { "a_" + it.first.pkg }) { (a, ai) ->
                SelectRow(
                    checked = a.pkg in vm.selectedApps,
                    onCheck = { if (it) vm.selectedApps.add(a.pkg) else vm.selectedApps.remove(a.pkg) },
                    leading = { AppIcon(a.pkg) },
                    title = a.label,
                    sub = "${ai.reason}\n最終使用: ${a.lastUsed.ago()} ・ ${a.sizeBytes.fmtBytes()}",
                    chip = { PriorityChip(ai.priority) }
                )
            }
            if (appItems.isNotEmpty()) item {
                val sel = vm.selectedApps.filter { p -> appItems.any { it.first.pkg == p } }
                FilledTonalButton(
                    onClick = { uninstall(sel) },
                    enabled = sel.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) { Icon(Icons.Outlined.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("選択した${sel.size}個をアンインストール") }
            }
            val fileItems = res.files.mapNotNull { ai -> vm.fileFor(ai.id)?.let { it to ai } }
            item { SectionHeader("消去候補ファイル (${fileItems.size})") }
            if (fileItems.isEmpty()) item { Text(if (vm.hasFiles) "候補はありません" else "ファイルへのアクセスを許可すると分析できます", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(fileItems, key = { "f_" + it.first.path }) { (f, ai) ->
                FileRow(vm, f, "${ai.reason}\n${f.size.fmtBytes()} ・ 最終閲覧: ${f.lastAccess.ago()}") { PriorityChip(ai.priority) }
            }
            if (fileItems.isNotEmpty()) item {
                val sel = vm.selectedFiles.filter { p -> fileItems.any { it.first.path == p } }
                val total = fileItems.filter { it.first.path in sel }.sumOf { it.first.size }
                Button(
                    onClick = { confirmFiles = true },
                    enabled = sel.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) { Icon(Icons.Outlined.Delete, null); Spacer(Modifier.width(8.dp)); Text("選択した${sel.size}件を消去 (${total.fmtBytes()})") }
                if (confirmFiles) ConfirmDelete(sel.size, total, onDismiss = { confirmFiles = false }) {
                    confirmFiles = false; vm.deleteFiles(sel)
                }
            }
        } else if (!vm.aiLoading) {
            item {
                Text(
                    "ボタンを押すと、使用状況とファイルの閲覧履歴をGroq AIが分析し、消しても困りにくいアプリ・ファイルを提案します。アンインストールは1件ずつAndroidの確認画面が表示されます。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp)
                )
            }
        }
    }
}

@Composable
fun SectionHeader(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

@Composable
fun SelectRow(
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
    leading: @Composable () -> Unit,
    title: String,
    sub: String,
    chip: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().clickable { (onClick ?: { onCheck(!checked) })() }
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (chip != null) { Spacer(Modifier.width(6.dp)); chip() }
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Checkbox(checked = checked, onCheckedChange = onCheck)
        }
    }
}

@Composable
fun FileRow(vm: MainViewModel, f: FileInfo, sub: String, chip: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    SelectRow(
        checked = f.path in vm.selectedFiles,
        onCheck = { if (it) vm.selectedFiles.add(f.path) else vm.selectedFiles.remove(f.path) },
        leading = { FileIcon(f) },
        title = f.name,
        sub = "$sub\n${f.folder}",
        chip = chip,
        onClick = { openFile(ctx, f) }
    )
}

fun openFile(ctx: android.content.Context, f: FileInfo) {
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(f.path))
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.ext) ?: "*/*"
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.onFailure { Toast.makeText(ctx, "このファイルを開けるアプリがありません", Toast.LENGTH_SHORT).show() }
}

@Composable
fun ConfirmDelete(count: Int, bytes: Long, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Warning, null) },
        title = { Text("${count}件のファイルを消去") },
        text = { Text("${bytes.fmtBytes()} を解放します。消去したファイルは元に戻せません。") },
        confirmButton = { TextButton(onClick = onOk) { Text("消去") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

private enum class AppSort(val label: String) { UNUSED("未使用順"), SIZE("容量順"), NAME("名前順") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(vm: MainViewModel, uninstall: (List<String>) -> Unit) {
    var sort by rememberSaveable { mutableStateOf(AppSort.UNUSED) }
    var q by rememberSaveable { mutableStateOf("") }
    val list = remember(vm.apps, sort, q) {
        vm.apps.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }.let { l ->
            when (sort) {
                AppSort.UNUSED -> l.sortedBy { it.lastUsed }
                AppSort.SIZE -> l.sortedByDescending { it.sizeBytes }
                AppSort.NAME -> l.sortedBy { it.label.lowercase() }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            PermissionCards(vm)
            OutlinedTextField(
                q, { q = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = { Text("アプリを検索") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true, shape = RoundedCornerShape(28.dp)
            )
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppSort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) }) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.refreshApps() }) { Icon(Icons.Outlined.Refresh, null) }
            }
            Text("${list.size}個のアプリ ・ 合計 ${list.sumOf { it.sizeBytes }.fmtBytes()}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (vm.loadingApps) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(list, key = { it.pkg }) { a ->
                SelectRow(
                    checked = a.pkg in vm.selectedApps,
                    onCheck = { if (it) vm.selectedApps.add(a.pkg) else vm.selectedApps.remove(a.pkg) },
                    leading = { AppIcon(a.pkg) },
                    title = a.label,
                    sub = "最終使用: ${a.lastUsed.ago()} ・ ${a.sizeBytes.fmtBytes()} ・ 180日で${a.foregroundMs / 60000}分"
                )
            }
        }
        if (vm.selectedApps.isNotEmpty()) {
            Button(
                onClick = { uninstall(vm.selectedApps.toList()) },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Icon(Icons.Outlined.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("${vm.selectedApps.size}個をアンインストール") }
        }
    }
}

private enum class FileSort(val label: String) { SIZE("容量順"), OLD("未閲覧順") }

@Composable
fun FilesScreen(vm: MainViewModel) {
    var sort by rememberSaveable { mutableStateOf(FileSort.SIZE) }
    var confirm by remember { mutableStateOf(false) }
    val list = remember(vm.files, sort) {
        when (sort) {
            FileSort.SIZE -> vm.files.sortedByDescending { it.size }
            FileSort.OLD -> vm.files.sortedBy { it.lastAccess }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            PermissionCards(vm)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FileSort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) }) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.refreshFiles() }, enabled = vm.hasFiles) { Icon(Icons.Outlined.Refresh, null) }
            }
            Text("${vm.prefs.minFileMb}MB以上: ${list.size}件 ・ 合計 ${list.sumOf { it.size }.fmtBytes()}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (vm.loadingFiles) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(list, key = { it.path }) { f ->
                FileRow(vm, f, "${f.size.fmtBytes()} ・ 最終閲覧: ${f.lastAccess.ago()}")
            }
        }
        if (vm.selectedFiles.isNotEmpty()) {
            val total = vm.files.filter { it.path in vm.selectedFiles }.sumOf { it.size }
            Button(
                onClick = { confirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Icon(Icons.Outlined.Delete, null); Spacer(Modifier.width(8.dp)); Text("${vm.selectedFiles.size}件を消去 (${total.fmtBytes()})") }
            if (confirm) ConfirmDelete(vm.selectedFiles.size, total, onDismiss = { confirm = false }) {
                confirm = false; vm.deleteFiles(vm.selectedFiles.toList())
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(vm.prefs.apiKey) }
    var model by remember { mutableStateOf(vm.prefs.model) }
    var days by remember { mutableStateOf(vm.prefs.unusedDays.toString()) }
    var minMb by remember { mutableStateOf(vm.prefs.minFileMb.toString()) }
    var show by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Groq Console API", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            key, { key = it }, Modifier.fillMaxWidth(),
            label = { Text("APIキー (gsk_…)") }, singleLine = true,
            visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null) } },
            shape = RoundedCornerShape(16.dp)
        )
        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) }) {
            Text("console.groq.com でキーを取得")
        }
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("モデル") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "llama-3.1-8b-instant").forEach { m ->
                AssistChip(onClick = { model = m }, label = { Text(m.substringAfter('/'), maxLines = 1) })
            }
        }
        Text("分析の条件", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(days, { days = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("未使用とみなす日数") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp))
            OutlinedTextField(minMb, { minMb = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("対象ファイル (MB以上)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp))
        }
        Button(
            onClick = {
                vm.prefs.apiKey = key
                vm.prefs.model = model
                vm.prefs.unusedDays = days.toIntOrNull()?.coerceIn(1, 3650) ?: 30
                vm.prefs.minFileMb = minMb.toIntOrNull()?.coerceIn(1, 100000) ?: 5
                vm.refreshFiles()
                Toast.makeText(ctx, "保存しました", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存") }
        Spacer(Modifier.weight(1f))
        Text(
            "APIキーは端末内にのみ保存され、分析時はアプリ名・使用日数・容量・ファイル名などのメタデータだけがGroqに送信されます。ファイルの中身は送信しません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
