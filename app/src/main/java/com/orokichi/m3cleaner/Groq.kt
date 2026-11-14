package com.orokichi.m3cleaner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AiItem(val id: String, val reason: String, val priority: String)
data class AiResult(val summary: String, val apps: List<AiItem>, val files: List<AiItem>)

object Groq {
    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

    private val SYSTEM = """
あなたはAndroidスマートフォンのストレージ整理アシスタントです。
ユーザーの端末にあるアプリの使用状況と、大きなファイルのアクセス状況が渡されます。
長期間使っていない・ほぼ使っていないアプリや、長期間開いていない大きなファイルの中から、
削除・アンインストールしても困らない可能性が高いものを選んで提案してください。
ルール:
- 銀行・決済・認証(2FA)・キャリア・ウォレット・メッセージ系アプリは、未使用でも原則提案しない（提案する場合はpriorityをlowにし理由で注意を促す）。
- 写真・動画・書類など個人的価値が高そうなファイルは「バックアップ後に削除」と理由に書く。
- APK、ダウンロード済みのインストーラ、重複しそうなファイル、キャッシュ的なファイルは優先度高め。
- 理由は日本語で40文字以内、具体的に（例: 「120日未使用・1.2GB占有」）。
- priorityは high / medium / low のいずれか。
- 必ず次のJSONのみを返す:
{"summary":"全体の要約(日本語・100文字以内・削減見込み容量を含める)","apps":[{"id":"a0","reason":"...","priority":"high"}],"files":[{"id":"f0","reason":"...","priority":"medium"}]}
- idは入力にあるものだけを使う。該当なしなら空配列。
""".trimIndent()

    suspend fun suggest(
        apiKey: String,
        model: String,
        apps: List<AppInfo>,
        files: List<FileInfo>,
    ): AiResult = withContext(Dispatchers.IO) {
        val appArr = JSONArray()
        apps.forEachIndexed { i, a ->
            appArr.put(JSONObject().apply {
                put("id", "a$i")
                put("name", a.label)
                put("package", a.pkg)
                put("days_since_last_use", daysSince(a.lastUsed) ?: -1)
                put("foreground_minutes_180d", a.foregroundMs / 60000)
                put("size_mb", a.sizeBytes / (1024 * 1024))
                put("days_since_install", daysSince(a.installed) ?: -1)
            })
        }
        val fileArr = JSONArray()
        files.forEachIndexed { i, f ->
            fileArr.put(JSONObject().apply {
                put("id", "f$i")
                put("name", f.name)
                put("folder", f.folder)
                put("size_mb", f.size / (1024 * 1024))
                put("days_since_access", daysSince(f.lastAccess) ?: -1)
                put("days_since_modified", daysSince(f.lastModified) ?: -1)
            })
        }
        val userContent = JSONObject().apply {
            put("note", "days_since_last_use が -1 は使用履歴なし(180日以上未使用の可能性)")
            put("apps", appArr)
            put("files", fileArr)
        }.toString()

        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM))
                .put(JSONObject().put("role", "user").put("content", userContent)))
        }.toString()

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                throw RuntimeException("Groq API エラー ($code): ${msg ?: text.take(200)}")
            }
            val content = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            parse(content)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(content: String): AiResult {
        val start = content.indexOf('{'); val end = content.lastIndexOf('}')
        val o = JSONObject(content.substring(start, end + 1))
        fun items(key: String): List<AiItem> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val jo = arr.optJSONObject(i) ?: return@mapNotNull null
                AiItem(jo.optString("id"), jo.optString("reason"), jo.optString("priority", "medium"))
            }
        }
        return AiResult(o.optString("summary"), items("apps"), items("files"))
    }
}
