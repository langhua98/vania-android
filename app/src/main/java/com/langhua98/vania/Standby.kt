package com.langhua98.vania

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext

/// 一条指令的结果：回执里的一句话，和要带回去的文件（名字、内容）
class CmdResult(val text: String, val files: List<Pair<String, ByteArray>> = emptyList())

/// 待命循环：等另一台设备通过小橘云盘发来的指令，执行完把回执原路送回去。对应 iPhone 版 RemoteEngine.swift 的 loop / execute。
/// 先走即时信箱（长轮询，指令一到马上执行），服务端没有即时信箱时退回文件信箱；用即时信箱时每 30 秒也看一眼文件收件箱，兼容旧的发送端。
/// 只用 OkHttp 和 org.json，不碰安卓的东西：真正干活的（拍照、录音、机器人…）由 execute 传进来，在电脑上可以测（StandbyTest）。
class Standby(
    private val cloud: Cloud,
    private val device: String,
    private val pair: String,
    private val isSeen: (String) -> Boolean,
    private val markSeen: (String) -> Unit,
    private val log: (String) -> Unit,
    /// 执行一条指令（指令名在 cmd["do"]）。回执里额外要带的字段（gimbal、robot）由 extra 给
    private val execute: suspend (JSONObject) -> CmdResult,
    private val extra: suspend (String) -> Map<String, Any> = { emptyMap() },
    /// 指令开始、结束、被拒（脸跟着变表情）
    private val events: Events = Events(),
    private val relayWaitSec: Int = 25,
) {
    class Events(
        val started: (String) -> Unit = {},
        val finished: (String, Boolean) -> Unit = { _, _ -> },
        val rejected: () -> Unit = {},
    )

    private val inbox = "/小橘推送/遥控/$device/收件"
    private val outbox = "/小橘推送/遥控/$device/回执"
    private val filesDir = "/小橘推送/遥控/$device/文件"

    /// 一直跑到协程被取消
    suspend fun run() {
        try { log("云盘：${cloud.me()}") } catch (e: Exception) { log("连不上云盘：${e.message}") }
        var useRelay = true
        var lastFileCheck = 0L
        var lastError = ""
        while (true) {
            coroutineContext.ensureActive()
            if (useRelay) {
                try {
                    val msgs = cloud.relayWait(RemoteBox.inbox(device), relayWaitSec)
                    lastError = ""
                    for (cmd in msgs) handle(cmd, viaRelay = true)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (Cloud.relayMissing(e)) {
                        useRelay = false
                        log("云盘服务端还没有即时信箱，用文件信箱（会慢几秒）")
                    } else {
                        val msg = e.message ?: e.javaClass.simpleName
                        if (msg != lastError) log("收件箱读不了：$msg")
                        lastError = msg
                        delay(2000)
                    }
                }
                // 旧的发送端还会写文件信箱，每 30 秒看一眼
                if (System.currentTimeMillis() - lastFileCheck < 30_000) continue
            }
            lastFileCheck = System.currentTimeMillis()
            val items = cloud.list(inbox).filter { !it.isFolder && it.name.endsWith(".json") }.sortedBy { it.name }
            for (it in items) {
                if (isSeen(it.id)) continue
                markSeen(it.id)
                val cmd = try { JSONObject(String(cloud.download(it.id))) } catch (e: Exception) {
                    log("收到一条看不懂的指令，跳过"); continue
                }
                if (!cmd.has("id")) cmd.put("id", it.name.removeSuffix(".json"))
                handle(cmd, viaRelay = false)
            }
            if (!useRelay) delay(2000)
        }
    }

    /// 执行一条指令，回执原路返回：从即时信箱来的回到即时信箱（文件 base64 直接放在回执里），从文件来的写回执文件
    suspend fun handle(cmd: JSONObject, viaRelay: Boolean) {
        val id = cmd.optString("id").ifEmpty { UUID.randomUUID().toString() }

        suspend fun reply(r: JSONObject) {
            try {
                if (viaRelay) cloud.relaySend(RemoteBox.receipt(device, id), r)
                else cloud.uploadText("$outbox/$id.json", r.toString())
            } catch (e: Exception) { log("传回执失败：${e.message}") }
        }

        if (pair.isNotEmpty() && cmd.optString("pair") != pair) {
            log("有一条指令口令不对，拒绝")
            events.rejected()
            reply(JSONObject().put("id", id).put("ok", false).put("text", "配对口令不对"))
            return
        }

        val action = cmd.optString("do")
        log("执行：$action${if (action == "robot") "（${cmd.optString("action", "status")}）" else ""}")
        events.started(action)
        val result = try { execute(cmd) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            CmdResult("执行出错：${e.message}")
        }
        events.finished(action, !result.text.startsWith("执行出错"))

        val files = JSONArray()
        for ((name, bytes) in result.files) {
            if (viaRelay && bytes.size <= RemoteBox.INLINE_LIMIT) {
                files.put(JSONObject().put("name", name).put("data", Base64.getEncoder().encodeToString(bytes)))
                continue
            }
            val path = "$filesDir/$id-$name"
            val type = if (name.endsWith(".jpg")) "image/jpeg" else "application/octet-stream"
            try { cloud.upload(path, bytes, type); files.put(path) } catch (e: Exception) { log("传文件失败：$name") }
        }
        val r = JSONObject().put("id", id).put("do", action).put("ok", true).put("text", result.text)
            .put("files", files).put("at", SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()))
        extra(action).forEach { (k, v) -> r.put(k, v) }
        reply(r)
        log("完成：$action — ${result.text.lineSequence().firstOrNull() ?: ""}")
    }
}
