package com.langhua98.vania

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

/// 大模型：OpenAI 兼容的 /chat/completions 接口（DeepSeek、智谱、通义、Kimi 等都行），流式输出。对应 iOS 版 Talk.swift 里的 Chat
object Chat {
    val EMOTIONS = setOf("neutral", "happy", "surprised", "confused", "alert")

    val PERSONA = """
你是云来（Yunlai Robot），一个住在手机屏幕里的数字生命体。手机是你的脸，摄像头是你的眼睛，麦克风是你的耳朵，云台是你的身体。
你的回答会被直接读出来，所以：
- 用自然的口语中文，一般一到两句话，最多三句。
- 不要用列表、表格、markdown、表情符号。
- 回答最前面先写一个情绪标签，只能是这五个之一：[neutral] [happy] [surprised] [confused] [alert]，然后再写回答。
""".trimIndent()

    class Failure(message: String) : Exception(message)

    /// 流式请求，每收到一段文字就交给 onPiece（在主线程上调）
    suspend fun stream(base: String, key: String, model: String, messages: JSONArray, onPiece: (String) -> Unit) {
        val url = base.trim().trimEnd('/') + "/chat/completions"
        val reasoner = model.contains("reasoner")
        // deepseek-reasoner 不认 temperature，max_tokens 把思考也算进去，给少了会只想不答
        val body = JSONObject().put("model", model).put("messages", messages).put("stream", true)
        if (reasoner) body.put("max_tokens", 4000) else body.put("temperature", 0.8).put("max_tokens", 400)
        val req = try {
            Request.Builder().url(url)
                .header("Authorization", "Bearer $key")
                .header("Accept", "text/event-stream")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        } catch (e: IllegalArgumentException) { throw Failure("接口地址不对") }
        val client = http.newBuilder().readTimeout(if (reasoner) 120L else 60L, TimeUnit.SECONDS).build()
        val call = client.newCall(req)
        val ctx = coroutineContext
        withContext(Dispatchers.IO) {
            call.execute().use { resp ->
                if (resp.code != 200) throw Failure("接口返回 ${resp.code}")
                val src = resp.body?.source() ?: throw Failure("接口没有内容")
                while (true) {
                    ctx.ensureActive()
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    val piece = try {
                        JSONObject(payload).getJSONArray("choices").getJSONObject(0).getJSONObject("delta").optString("content", "")
                    } catch (e: Exception) { "" }
                    if (piece.isNotEmpty()) withContext(Dispatchers.Main) { onPiece(piece) }
                }
            }
        }
    }

    /// 去掉朗读时不该念的东西
    fun clean(s: String): String =
        s.replace(Regex("""\[[A-Za-z]+\]"""), "").replace(Regex("""[*#`>_|]"""), "").trim()
}

/// 语音合成服务（和 iOS 版一样，地址写死，不在设置页填）
object Voice {
    /// 默认：Cloudflare Workers AI 上的 MeloTTS 女声（langhua98/xiaoju-music 的 yunlai-tts/），不会冷启动
    const val DEFAULT_BASE = "https://yunlai-tts.langhua98.workers.dev"
    /// 备用：小橘视频流式服务 Space 上的 Kokoro 女声 zf_088，闲了会关，叫醒要十几秒
    const val BACKUP_BASE = "https://langhua1998-douyin-proxy.hf.space/tts"
    const val BACKUP_VOICE = "zf_088"

    /// 叫醒备用服务（第一次加载要十几秒）
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        try {
            val c = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
            c.newCall(Request.Builder().url(DEFAULT_BASE.trimEnd('/') + "/health").build()).execute().close()
        } catch (e: Exception) {}
    }

    /// 先问 Cloudflare（8 秒内没回音就算失败），不行再问备用的；都不行返回 null，调用方改用系统自带的朗读
    suspend fun fetchWithBackup(text: String): ByteArray? =
        fetch(DEFAULT_BASE, BACKUP_VOICE, text, 8) ?: fetch(BACKUP_BASE, BACKUP_VOICE, text, 45)

    /// 合成一句，拿到 WAV；失败返回 null
    suspend fun fetch(base: String, voice: String, text: String, timeoutSec: Long): ByteArray? = withContext(Dispatchers.IO) {
        val url = (base.trim().trimEnd('/') + "/tts").toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("text", text.take(300))
            ?.addQueryParameter("voice", voice.ifEmpty { BACKUP_VOICE })
            ?.addQueryParameter("speed", "1.05")
            ?.build() ?: return@withContext null
        try {
            val c = http.newBuilder().callTimeout(timeoutSec, TimeUnit.SECONDS).build()
            c.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val type = r.header("Content-Type") ?: ""
                val data = r.body?.bytes()
                if (r.code == 200 && type.startsWith("audio") && data != null && data.size > 44) data else null
            }
        } catch (e: Exception) { null }
    }
}
