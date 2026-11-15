package com.langhua98.vania

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.Base64

/// 待命循环和小橘云盘的协议：用 xiaoju-remote 那边测试用的假云盘（tests/mock_cloud.py，python3 跑）。
/// 两种服务端各跑一遍：旧的（只有文件信箱）和新的（有即时信箱）。没有 python3 的机器上跳过。
class StandbyTest {
    private var server: Process? = null

    private fun startMock(relay: Boolean): String {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "tests") }.firstOrNull { File(it, "mock_cloud.py").exists() }
        assumeTrue("找不到 tests/mock_cloud.py", dir != null)
        val port = ServerSocket(0).use { it.localPort }
        val code = "import mock_cloud, time; mock_cloud.RELAY = ${if (relay) "True" else "False"}; mock_cloud.serve($port); time.sleep(300)"
        val p = try { ProcessBuilder("python3", "-c", code).directory(dir).redirectErrorStream(true).start() } catch (e: Exception) { null }
        assumeTrue("没有 python3", p != null)
        server = p
        // 等它起来
        repeat(50) {
            try { java.net.Socket("127.0.0.1", port).close(); return "http://127.0.0.1:$port" } catch (e: Exception) { Thread.sleep(100) }
        }
        error("假云盘没起来")
    }

    @After fun stop() { server?.destroy() }

    private fun standby(cloud: Cloud, pair: String, seen: MutableSet<String>, log: MutableList<String>) = Standby(
        cloud = cloud, device = "测试机B", pair = pair,
        isSeen = { it in seen }, markSeen = { seen += it }, log = { synchronized(log) { log += it } },
        execute = { cmd ->
            when (cmd.optString("do")) {
                "info" -> CmdResult("型号：测试")
                "photo" -> CmdResult("拍了一张", listOf("photo.jpg" to ByteArray(1000) { it.toByte() }))
                else -> CmdResult("不认识的指令：${cmd.optString("do")}")
            }
        },
        extra = { if (it == "robot") mapOf("robot" to JSONObject().put("outfit", "default")) else emptyMap() },
        relayWaitSec = 2,
    )

    @Test fun relayMode() = runBlocking {
        val base = startMock(relay = true)
        val cloud = Cloud(base, "xa_test")
        val log = mutableListOf<String>()
        val job = launch(Dispatchers.IO) { standby(cloud, "0", mutableSetOf(), log).run() }
        // 控制端：往收件箱发两条指令，等回执
        cloud.relaySend(RemoteBox.inbox("测试机B"), JSONObject().put("id", "a1").put("pair", "0").put("do", "info"))
        cloud.relaySend(RemoteBox.inbox("测试机B"), JSONObject().put("id", "a2").put("pair", "0").put("do", "photo"))
        val r1 = withTimeout(15_000) { waitReceipt(cloud, "a1") }
        assertEquals(true, r1.getBoolean("ok")); assertEquals("info", r1.getString("do")); assertEquals("型号：测试", r1.getString("text"))
        val r2 = withTimeout(15_000) { waitReceipt(cloud, "a2") }
        val f = r2.getJSONArray("files").getJSONObject(0)
        assertEquals("photo.jpg", f.getString("name"))
        assertEquals(1000, Base64.getDecoder().decode(f.getString("data")).size)   // 即时信箱里文件直接放在回执里
        // 口令不对：拒绝
        cloud.relaySend(RemoteBox.inbox("测试机B"), JSONObject().put("id", "a3").put("pair", "错的").put("do", "info"))
        val r3 = withTimeout(15_000) { waitReceipt(cloud, "a3") }
        assertEquals(false, r3.getBoolean("ok")); assertEquals("配对口令不对", r3.getString("text"))
        job.cancelAndJoin()
        assertTrue(log.toString(), log.any { it.startsWith("云盘：") })
    }

    @Test fun fileMode() = runBlocking {
        val base = startMock(relay = false)
        val cloud = Cloud(base, "xa_test")
        val log = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val job = launch(Dispatchers.IO) { standby(cloud, "", seen, log).run() }
        // 旧的发送端：写一个指令文件
        cloud.uploadText("/小橘推送/遥控/测试机B/收件/f1.json", JSONObject().put("do", "photo").toString())
        val r = withTimeout(20_000) {
            while (true) {
                val it = cloud.list("/小橘推送/遥控/测试机B/回执").firstOrNull { it.name == "f1.json" }
                if (it != null) return@withTimeout JSONObject(String(cloud.download(it.id)))
                delay(300)
            }
            @Suppress("UNREACHABLE_CODE") JSONObject()
        }
        assertEquals("f1", r.getString("id"))   // 指令里没写 id 就用文件名
        // 文件信箱里，文件传到云盘，回执里写路径
        val path = r.getJSONArray("files").getString(0)
        assertEquals("/小橘推送/遥控/测试机B/文件/f1-photo.jpg", path)
        assertTrue(cloud.list("/小橘推送/遥控/测试机B/文件").any { it.name == "f1-photo.jpg" })
        assertTrue(log.toString(), log.any { it.contains("文件信箱") })
        // 同一条指令不会执行两次
        delay(2500)
        job.cancelAndJoin()
        assertEquals(1, log.count { it.startsWith("执行：photo") })
    }

    private suspend fun waitReceipt(cloud: Cloud, id: String): JSONObject {
        while (true) cloud.relayWait(RemoteBox.receipt("测试机B", id), 2).firstOrNull()?.let { return it }
    }
}
