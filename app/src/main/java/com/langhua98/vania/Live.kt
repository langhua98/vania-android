package com.langhua98.vania

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/// 实时语音对话（Gemini Live API）：一个模型直接「听声音、出声音」，像打电话一样聊。对应 iPhone 版的 Live.swift。
/// - 一直开着麦克风。用安卓的通话模式录音（系统的回声消除），她说话时不会把自己的声音当成你说的，你随时可以插话打断她。
///   这台手机没有回声消除时改成「对讲机模式」：她说话时麦克风的声音先不发。
/// - 发 16 kHz 16 位单声道 PCM，收 24 kHz PCM。模型名不写死：每次连接前问一次有哪些支持 bidiGenerateContent 的模型，挑最合适的。
/// - 连不上（没额度、密钥不对）时 onFail 交回给 Talk，退回普通模式。只在主线程用（音频读写在自己的线程）。
class LiveTalk(private val ctx: Context, private val store: Store, private val face: FaceBridge,
               private val gimbal: Gimbal, private val log: (String) -> Unit) {
    enum class Phase { OFF, CONNECTING, LISTENING, SPEAKING }
    var phase = Phase.OFF; private set
    /// 她正在说的话（Gemini 给的文字稿），聊天框上面当字幕
    var caption = ""; private set
    var onChange: (() -> Unit)? = null
    /// 连不上时调用（参数是原因）
    var onFail: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var ws: WebSocket? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var micThread: Thread? = null
    private var playThread: Thread? = null
    private val playQueue = LinkedBlockingQueue<ShortArray>()
    @Volatile private var running = false
    @Volatile private var micMuted = false
    @Volatile private var echoCancel = true
    @Volatile private var flushPlay = false
    /// 写进 AudioTrack 的帧数和每 60 毫秒一段的音量（嘴型用）；播放头追上写入就说完了
    @Volatile private var written = 0L
    private val envelope = ArrayList<FloatArray>()
    private var envBase = 0L

    private var turnFinished = false
    private var lastActivity = 0L
    private var heard = ""
    private var said = ""
    private var ending = false
    private var modelName = ""
    private var usedOutfitTool = false
    private var micChunks = 0
    private var audioIn = 0
    private var statTicks = 0
    private var ticker: Runnable? = null
    private var mouthTicker: Runnable? = null

    val available: Boolean get() = store.geminiKey.isNotBlank()

    private fun changed() = onChange?.invoke()
    private fun setPhase(p: Phase) { phase = p; changed() }

    // ───────── 开始 / 结束 ─────────

    fun start() {
        if (phase != Phase.OFF || !available) return
        setPhase(Phase.CONNECTING)
        ending = false
        heard = ""; said = ""; caption = ""
        face.send("type" to "robot_state", "state" to "thinking", "emotion" to "neutral")
        scope.launch { connect() }
    }

    fun stop() {
        if (phase == Phase.OFF) return
        setPhase(Phase.OFF)
        ticker?.let { main.removeCallbacks(it) }; ticker = null
        mouthTicker?.let { main.removeCallbacks(it) }; mouthTicker = null
        ws?.close(1000, null); ws = null
        stopAudio()
        caption = ""; changed()
        face.send("type" to "sensing", "mic" to false)
        face.send("type" to "robot_state", "state" to "idle", "emotion" to "neutral")
    }

    private fun fail(reason: String) {
        log("实时语音连不上：$reason")
        stop()
        onFail?.invoke(reason)
    }

    // ───────── 连接 ─────────

    private suspend fun connect() {
        val key = store.geminiKey.trim()
        modelName = try { pickModel(key) } catch (e: Exception) { return fail(e.message ?: "连不上") }
        if (phase != Phase.CONNECTING) return
        val url = HttpUrl.Builder().scheme("https").host(HOST)
            .addPathSegments("ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
            .addQueryParameter("key", key).build()
        val client = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { main.post { if (ws === webSocket) handle(text) } }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { main.post { if (ws === webSocket) handle(bytes.utf8()) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post {
                    if (ws !== webSocket) return@post
                    val detail = "${t.message ?: t.javaClass.simpleName}${response?.let { "（${it.code}）" } ?: ""}"
                    if (phase == Phase.CONNECTING) fail(detail) else if (phase != Phase.OFF) { log("实时语音断开：$detail"); stop() }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post {
                    if (ws !== webSocket) return@post
                    val detail = "关闭码 $code${if (reason.isEmpty()) "" else "：$reason"}"
                    if (phase == Phase.CONNECTING) fail(detail) else if (phase != Phase.OFF) { log("实时语音断开：$detail"); stop() }
                }
            }
        })
        val system = PERSONA + "\n" + Body.PERSONA_TOOL + "\n" + Body.eyesNoteTool(store.allowSee) + "\n云台现在的状态：" + gimbal.stateText()
        send(JSONObject().put("setup", JSONObject()
            .put("model", modelName)
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", VOICE)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("tools", JSONArray().put(Body.toolDeclaration()))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())))
        // 10 秒还没 setupComplete 就算连不上
        main.postDelayed({ if (phase == Phase.CONNECTING) fail("10 秒没连上") }, 10_000)
    }

    /// 问谷歌：这个密钥能用哪些实时（bidiGenerateContent）模型，挑原生语音的、版本新的
    private suspend fun pickModel(key: String): String = withContext(Dispatchers.IO) {
        val url = HttpUrl.Builder().scheme("https").host(HOST).addPathSegments("v1beta/models")
            .addQueryParameter("key", key).addQueryParameter("pageSize", "1000").build()
        val body = http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.code != 200) throw Exception(if (r.code == 400 || r.code == 403) "Gemini 密钥不对（${r.code}）" else "模型列表返回 ${r.code}")
            r.body?.string() ?: ""
        }
        val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
        val live = (0 until models.length()).mapNotNull { i ->
            val m = models.getJSONObject(i)
            val name = m.optString("name")
            val methods = m.optJSONArray("supportedGenerationMethods") ?: return@mapNotNull null
            if ((0 until methods.length()).none { methods.optString(it) == "bidiGenerateContent" }) return@mapNotNull null
            val n = name.lowercase()
            if (listOf("translate", "image", "tts", "music").any { n.contains(it) }) null else name
        }
        fun score(name: String): Pair<Int, Double> {
            val n = name.lowercase()
            var s = 0
            if (n.contains("native-audio") || n.contains("live")) s += 2
            if (n.contains("flash")) s += 1
            val v = Regex("""\d+(\.\d+)?""").find(n)?.value?.toDoubleOrNull() ?: 0.0
            return s to v
        }
        live.maxWithOrNull(compareBy<String>({ score(it).first }, { score(it).second })) ?: throw Exception("这个密钥没有能实时对话的模型")
    }

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    // ───────── 服务器发来的 ─────────

    private fun handle(text: String) {
        val obj = try { JSONObject(text) } catch (e: Exception) { return log("实时语音：收到看不懂的消息（${text.length} 字）") }
        if (obj.has("setupComplete")) ready()
        obj.optJSONObject("error")?.let { err ->
            val m = err.optString("message", "出错")
            return if (phase == Phase.CONNECTING) fail(m) else log("实时语音：$m")
        }
        obj.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (i in 0 until calls.length()) runTool(calls.getJSONObject(i))
        }
        if (obj.has("goAway")) log("实时语音：服务器说快要断开了（单次连接有时长上限）")
        val sc = obj.optJSONObject("serverContent") ?: return
        if (sc.optBoolean("interrupted")) interrupted()
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { heard += it; lastActivity = System.currentTimeMillis() }
        sc.optJSONObject("outputTranscription")?.optString("text")?.let { said += it; caption = tidy(said); changed() }
        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val b64 = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
                audioIn++
                play(Base64.decode(b64, Base64.DEFAULT))
            }
        }
        if (sc.optBoolean("turnComplete")) turnComplete()
    }

    private fun runTool(call: JSONObject) {
        val id = call.optString("id")
        val name = call.optString("name")
        val args = call.optJSONObject("args") ?: JSONObject()
        fun num(k: String) = if (args.has(k) && !args.isNull(k)) args.optDouble(k).takeIf { !it.isNaN() } else null
        scope.launch {
            val result: String = when (name) {
                "change_outfit" -> Store.outfitID(args.optString("name"))?.let { o ->
                    store.outfit = o; usedOutfitTool = true; "换好了：${Store.outfitName(o)}"
                } ?: "没有这套衣服，只有：${Body.outfitNames()}"
                "play_action" -> Body.actionID(args.optString("name"))?.let { a ->
                    face.send("type" to "action", "name" to a); "正在做：${args.optString("name")}"
                } ?: "不会这个动作，只会：${Body.actionNames()}"
                "turn_body" -> {
                    val yaw = Body.turnYaw(args.optString("direction", "center"), num("degrees"))
                    face.send("type" to "turn", "yaw" to yaw)
                    if (yaw == 0.0) "转回来了" else "转好了（${yaw.toInt()}°）"
                }
                "set_camera" -> Body.shotID(args.optString("shot"))?.let { s ->
                    face.send("type" to "view", "shot" to s); "镜头换成${args.optString("shot")}了"
                } ?: "没有这种镜头，只有：${Body.shotNames()}"
                "see" -> Eyes.look(args.optString("camera") != "back", store, face).let { log("眼睛：${it.second}"); it.first }
                "gimbal", "move_gimbal" -> {
                    val move = if (name == "gimbal") Body.GimbalMove(args.optString("action", "status"), num("right"), num("up"))
                    else Body.gimbalMove(args.optString("direction", "center"), num("degrees"))
                    Body.perform(gimbal, move).also { log("云台（${Body.describe(move)}）：$it") }
                }
                else -> "不认识的工具：$name"
            }
            if (name in listOf("change_outfit", "play_action", "turn_body", "set_camera")) log("工具 $name：$result")
            lastActivity = System.currentTimeMillis()
            send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(
                JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("result", result))))))
        }
    }

    private fun ready() {
        if (phase != Phase.CONNECTING) return
        try { startAudio() } catch (e: Exception) { return fail("麦克风打不开：${e.message}") }
        setPhase(Phase.LISTENING)
        lastActivity = System.currentTimeMillis()
        log("实时语音已连上（${modelName.removePrefix("models/")}，声音 $VOICE，回声消除${if (echoCancel) "开" else "没有，用对讲机模式"}）")
        face.send("type" to "sensing", "mic" to true)
        face.send("type" to "robot_state", "state" to "listening", "emotion" to "neutral")
        // 先让她打个招呼：能听到就说明「她的声音 → 扬声器」这条路是通的
        send(JSONObject().put("clientContent", JSONObject().put("turns", JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", "（主人刚打开你，用一句很短的话跟主人打个招呼。）"))))).put("turnComplete", true)))
        micChunks = 0; audioIn = 0; statTicks = 0
        val t = object : Runnable {
            override fun run() {
                if (phase == Phase.OFF) return
                // 前 30 秒每 5 秒记一次诊断
                if (statTicks < 6) {
                    statTicks++
                    log("实时诊断：麦克风发出 $micChunks 段；她的声音 $audioIn 段")
                    micChunks = 0
                }
                if (phase == Phase.LISTENING && System.currentTimeMillis() - lastActivity > IDLE_LIMIT_MS) {
                    log("${IDLE_LIMIT_MS / 1000} 秒没说话，实时对话结束")
                    stop(); return
                }
                main.postDelayed(this, 5000)
            }
        }
        ticker = t
        main.postDelayed(t, 5000)
    }

    /// 聊天框里打的字：当成你说的一句话发给 Gemini（她用声音回答）。没连上返回 false
    fun sendText(text: String): Boolean {
        if (phase != Phase.LISTENING && phase != Phase.SPEAKING) return false
        heard += text
        caption = ""; changed()
        lastActivity = System.currentTimeMillis()
        send(JSONObject().put("clientContent", JSONObject().put("turns", JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text))))).put("turnComplete", true)))
        return true
    }

    private fun turnComplete() {
        val h = tidy(heard); val s = tidy(said)
        if (h.isNotEmpty()) log("听到：$h")
        if (s.isNotEmpty()) log("回答：$s")
        Store.backgroundCommand(h, store.background)?.let { store.background = it; log("背景：${Store.backgroundName(it)}") }
        if (!usedOutfitTool) Store.outfitCommand(h, store.outfit)?.let { store.outfit = it; log("换装：${Store.outfitName(it)}") }
        if (BYE.any { h.contains(it) }) ending = true
        heard = ""; said = ""
        usedOutfitTool = false
        turnFinished = true
        lastActivity = System.currentTimeMillis()
        if (playQueue.isEmpty() && !isPlaying()) doneSpeaking()
    }

    /// 你插话了：服务器让停，排着的声音全扔掉
    private fun interrupted() {
        playQueue.clear()
        flushPlay = true
        turnFinished = false
        if (phase == Phase.SPEAKING) backToListening()
    }

    private fun doneSpeaking() {
        turnFinished = false
        if (ending) { log("说了再见，实时对话结束"); return stop() }
        if (phase == Phase.SPEAKING) backToListening()
    }

    private fun backToListening() {
        setPhase(Phase.LISTENING)
        face.send("type" to "robot_state", "state" to "listening", "mouth_level" to 0)
        // 她说完 0.4 秒后再让麦克风发声音（喇叭的余音不会被当成你在说话）
        main.postDelayed({ if (phase == Phase.LISTENING) micMuted = false }, 400)
    }

    // ───────── 声音 ─────────

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) throw Exception("没有麦克风权限")
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        // 通话模式默认走听筒，改成扬声器（她要大声说）
        if (Build.VERSION.SDK_INT >= 31) {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
        } else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = true }
        val inSize = maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 3200 * 2)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, inSize)
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw Exception("录音初始化失败") }
        echoCancel = AcousticEchoCanceler.isAvailable()
        if (echoCancel) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
        val outSize = maxOf(AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), 24000)
        val tr = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(outSize).build()
        record = rec; track = tr
        running = true; micMuted = false; flushPlay = false
        written = 0; synchronized(envelope) { envelope.clear(); envBase = 0 }
        rec.startRecording()
        tr.play()
        // 麦克风：每 100 毫秒（1600 个采样）发一段
        micThread = Thread {
            val buf = ShortArray(1600)
            val bytes = ByteBuffer.allocate(3200).order(ByteOrder.LITTLE_ENDIAN)
            while (running) {
                var n = 0
                while (n < buf.size && running) { val r = rec.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
                if (!running || n < buf.size) continue
                if (micMuted) continue
                bytes.clear(); for (s in buf) bytes.putShort(s)
                val msg = JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                    .put("data", Base64.encodeToString(bytes.array(), Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=16000")))
                ws?.send(msg.toString())
                micChunks++
            }
        }.apply { name = "vania-mic"; start() }
        // 播放：攒够 0.3 秒再开始播（网络偶尔慢一下不会一卡一卡）
        playThread = Thread {
            var started = false
            val pre = ArrayList<ShortArray>()
            var preFrames = 0
            while (running) {
                if (flushPlay) {
                    flushPlay = false; pre.clear(); preFrames = 0; started = false
                    try { tr.pause(); tr.flush(); tr.play() } catch (e: Exception) {}
                    written = 0; synchronized(envelope) { envelope.clear(); envBase = 0 }
                }
                val chunk = playQueue.poll(100, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    // 队列空了：没攒够 0.3 秒的也先播出去
                    if (!started && pre.isNotEmpty()) { pre.forEach { write(tr, it) }; pre.clear(); preFrames = 0; started = true }
                    continue
                }
                if (!started) {
                    pre += chunk; preFrames += chunk.size
                    if (preFrames < 7200) continue
                    pre.forEach { write(tr, it) }; pre.clear(); preFrames = 0; started = true
                } else write(tr, chunk)
            }
        }.apply { name = "vania-play"; start() }
        // 嘴型和「说完了没」：每 60 毫秒看一眼播放头
        val m = object : Runnable {
            override fun run() {
                if (!running) return
                val head = try { (track?.playbackHeadPosition ?: 0).toLong() and 0xffffffffL } catch (e: Exception) { 0L }
                if (phase == Phase.SPEAKING) {
                    val (level, bands) = synchronized(envelope) {
                        val i = ((head - envBase) / 1440).toInt()
                        envelope.getOrNull(i) ?: FloatArray(4)
                    }.let { it[0] to listOf(it[1], it[2], it[3]) }
                    face.send(JSONObject().put("type", "robot_state").put("mouth_level", level.toDouble())
                        .put("mouth_bands", JSONArray(bands.map { it.toDouble() })))
                    if (turnFinished && playQueue.isEmpty() && head >= written - 240) doneSpeaking()
                }
                main.postDelayed(this, 60)
            }
        }
        mouthTicker = m
        main.postDelayed(m, 60)
    }

    private fun isPlaying(): Boolean {
        val head = try { (track?.playbackHeadPosition ?: 0).toLong() and 0xffffffffL } catch (e: Exception) { 0L }
        return head < written - 240
    }

    /// 写进播放器，并按 60 毫秒（1440 个采样）一段记下音量和三个频段（给嘴型用，和 iPhone 版 LevelMeter 一样）
    private fun write(tr: AudioTrack, s: ShortArray) {
        tr.write(s, 0, s.size)
        val k1 = 1 - exp(-2 * Math.PI * 700 / 24000).toFloat()
        val k2 = 1 - exp(-2 * Math.PI * 2500 / 24000).toFloat()
        var i = 0
        synchronized(envelope) {
            while (i < s.size) {
                val end = min(s.size, i + 1440)
                var lp1 = 0f; var lp2 = 0f; var sum = 0f; var low = 0f; var mid = 0f; var high = 0f
                for (j in i until end) {
                    val x = s[j] / 32768f
                    lp1 += k1 * (x - lp1); lp2 += k2 * (x - lp2)
                    sum += x * x; low += lp1 * lp1; mid += (lp2 - lp1) * (lp2 - lp1); high += (x - lp2) * (x - lp2)
                }
                val sc = 1f / (end - i)
                envelope += floatArrayOf(min(1f, sqrt(sum * sc) * 6), min(1f, sqrt(low * sc) * 6), min(1f, sqrt(mid * sc) * 6), min(1f, sqrt(high * sc) * 6))
                i = end
            }
        }
        written += s.size
    }

    private fun stopAudio() {
        running = false
        micThread?.join(300); playThread?.join(300)
        micThread = null; playThread = null
        playQueue.clear()
        try { record?.stop() } catch (e: Exception) {}
        record?.release(); record = null
        aec?.release(); aec = null; ns?.release(); ns = null
        try { track?.stop() } catch (e: Exception) {}
        track?.release(); track = null
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = false }
        am.mode = AudioManager.MODE_NORMAL
    }

    /// 收到一段 24 kHz 16 位 PCM
    private fun play(pcm: ByteArray) {
        val b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val s = ShortArray(b.remaining()).also { b.get(it) }
        if (s.isEmpty()) return
        lastActivity = System.currentTimeMillis()
        if (phase != Phase.SPEAKING) {
            // 没有回声消除时用「对讲机模式」：她说话时麦克风的声音先不发
            if (!echoCancel) micMuted = true
            setPhase(Phase.SPEAKING)
            face.send("type" to "robot_state", "state" to "speaking", "emotion" to "happy")
        }
        playQueue.offer(s)
    }

    companion object {
        const val VOICE = "Leda"   // Gemini 自带的女声之一（温柔、年轻）
        const val HOST = "generativelanguage.googleapis.com"
        private val BYE = listOf("再见", "拜拜", "不聊了", "晚安", "先这样", "退下")
        private const val IDLE_LIMIT_MS = 30_000L

        val PERSONA = """
你是云来（Yunlai Robot），一个住在手机屏幕里的数字生命体，声音温柔。手机是你的脸，摄像头是你的眼睛，麦克风是你的耳朵，云台是你的身体。
你在和主人语音聊天：一直用自然的口语中文，回答简短，一般一两句话，像朋友聊天一样，不要念列表。
""".trimIndent()

        /// 文字稿里汉字之间多出来的空格去掉（「台 岛 啦」→「台岛啦」）
        fun tidy(s: String) = s.replace(Regex("""(?<=[\p{IsHan}，。！？、])\s+(?=[\p{IsHan}，。！？、])"""), "").trim()
    }
}
