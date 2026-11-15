package com.langhua98.vania

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CloudError(message: String, val status: Int = 0) : Exception(message)

class CloudItem(val id: String, val name: String, val type: String) {
    val isFolder get() = type == "folder"
}

/// 小橘云盘 agent 接口（/api/agent/v1，Bearer xa_…）。和 iPhone 版的 Cloud.swift 一样，协议见 xiaoju-remote 的 AGENTS.md。
/// 只用 OkHttp 和 org.json，不碰安卓的东西，可以在电脑上测（CloudTest 用 xiaoju-remote 的假云盘）。
class Cloud(base: String, private val token: String, private val client: OkHttpClient = DEFAULT) {
    private val base = base.trim().trimEnd('/')

    private fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val b = (base + path).toHttpUrlOrNull()?.newBuilder() ?: throw CloudError("云盘地址不对")
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private suspend fun send(req: Request, c: OkHttpClient = client): ByteArray = withContext(Dispatchers.IO) {
        c.newCall(req).execute().use { r ->
            val data = r.body?.bytes() ?: ByteArray(0)
            if (r.code !in 200..299) {
                val msg = try { JSONObject(String(data)).optString("message").takeIf { it.isNotEmpty() } } catch (e: Exception) { null }
                throw CloudError("云盘 ${r.code}${msg?.let { "：$it" } ?: ""}", r.code)
            }
            data
        }
    }

    private fun get(u: HttpUrl) = Request.Builder().url(u).header("Authorization", "Bearer $token").build()

    suspend fun me(): String {
        val j = JSONObject(String(send(get(url("/api/agent/v1/me")))))
        return j.optJSONObject("agent")?.optString("name")?.takeIf { it.isNotEmpty() } ?: "已连接"
    }

    /// 列目录；目录不存在、读不了时返回空列表而不是抛错
    suspend fun list(path: String): List<CloudItem> {
        val out = mutableListOf<CloudItem>()
        var cursor: String? = null
        do {
            val q = mutableMapOf("path" to path, "limit" to "1000")
            cursor?.let { q["cursor"] = it }
            val j = try { JSONObject(String(send(get(url("/api/agent/v1/items", q))))) } catch (e: Exception) { return out }
            val kids = j.optJSONArray("children") ?: JSONArray()
            for (i in 0 until kids.length()) {
                val k = kids.getJSONObject(i)
                out += CloudItem(k.optString("id"), k.optString("name"), k.optString("type"))
            }
            cursor = j.optString("next_cursor").takeIf { it.isNotEmpty() && it != "null" }
        } while (cursor != null)
        return out
    }

    suspend fun download(id: String): ByteArray = send(get(url("/api/agent/v1/items/$id/content")))

    /// 上传（覆盖）。PUT /api/agent/v1/files?path=/…（不是根路径下的 /files，那个会 404）
    suspend fun upload(path: String, data: ByteArray, contentType: String = "application/octet-stream") {
        send(Request.Builder().url(url("/api/agent/v1/files", mapOf("path" to path)))
            .header("Authorization", "Bearer $token")
            .put(data.toRequestBody(contentType.toMediaType())).build())
    }

    suspend fun uploadText(path: String, text: String) = upload(path, text.toByteArray(), "application/json; charset=utf-8")

    // ───────── 即时信箱：消息在服务端内存里，长轮询，几乎没有延迟 ─────────

    /// 取走信箱里的所有消息；没有就最多等 seconds 秒（消息一到马上返回）
    suspend fun relayWait(box: String, seconds: Int = 25): List<JSONObject> {
        val c = client.newBuilder().readTimeout(seconds + 20L, TimeUnit.SECONDS).callTimeout(seconds + 30L, TimeUnit.SECONDS).build()
        val j = JSONObject(String(send(get(url("/api/agent/v1/relay", mapOf("box" to box, "wait" to "$seconds"))), c)))
        val msgs = j.optJSONArray("messages") ?: return emptyList()
        return (0 until msgs.length()).mapNotNull { msgs.optJSONObject(it)?.optJSONObject("body") }
    }

    suspend fun relaySend(box: String, obj: JSONObject) {
        send(Request.Builder().url(url("/api/agent/v1/relay", mapOf("box" to box)))
            .header("Authorization", "Bearer $token")
            .post(obj.toString().toRequestBody("application/json".toMediaType())).build())
    }

    companion object {
        val DEFAULT: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

        /// 服务端还没上即时信箱（旧版本）时是 404 / 405 / 501，调用方据此退回到文件信箱
        fun relayMissing(e: Throwable) = (e as? CloudError)?.status in setOf(404, 405, 501)
    }
}

/// 即时信箱的名字。要和「小橘推送」控制端、xiaoju-remote 的 scripts/遥控.py 保持一致
object RemoteBox {
    fun inbox(dev: String) = "遥控/$dev/收件"
    fun receipt(dev: String, id: String) = "遥控/$dev/回执/$id"
    /// 回执里直接带的文件最大多少（base64 之后要小于服务端单条 8 MB）
    const val INLINE_LIMIT = 5 shl 20
}
