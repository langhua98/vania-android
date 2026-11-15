package com.langhua98.vania

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/// 和她对话：点麦克风 → 系统语音识别听你说 → 大模型（OpenAI 兼容接口，流式）→ 逐句交给语音合成服务合成并播放。
/// 大模型写出第一句就开始合成、播放，后面的边播边合成。合成服务连不上的那句改用系统自带的朗读，她不会哑。
/// 脸的状态：倾听 → 思考 → 说话（嘴跟着真实音量）→ 待机。对应 iOS 版的 Talk.swift。只在主线程用。
class Talk(private val ctx: Context, private val store: Store, private val face: FaceBridge,
           private val gimbal: Gimbal, private val log: (String) -> Unit) {
    enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

    var phase = Phase.IDLE; private set
    /// 连续对话：点一下开始，之后她说完自动接着听；说「再见」或者一直不说话就结束
    var conversing = false; private set
    /// 字幕：你刚说的和她正在说的。闲下来 10 秒后清掉
    var userLine = ""; private set
    var subtitle = ""; private set
    /// 上面这几个变了（界面刷新用）
    var onChange: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var captionGen = 0
    private val history = mutableListOf<JSONObject>()

    // 听
    private var recognizer: SpeechRecognizer? = null
    private var heard = ""

    // 想
    private var llmJob: Job? = null
    private var buffer = ""
    private var emotion = "neutral"
    private var emotionKnown = false
    private var reply = ""
    private var lookWanted: Boolean? = null

    // 说：一句一句排队（合成下一句时播这一句）
    private class Line(val text: String, val audio: Deferred<ByteArray?>)
    private var queue = Channel<Line>(Channel.UNLIMITED)
    private var playJob: Job? = null
    private var startedSpeaking = false
    private val systemVoice = SystemVoice(ctx)

    // 云台标签一个接一个转
    private var gimbalChain: Job? = null

    /// 实时语音（Gemini Live）：填了 Gemini 密钥就优先用它，连不上退回下面的普通模式
    val live = LiveTalk(ctx, store, face, gimbal, log).also { l ->
        l.onChange = { changed() }
        l.onFail = { _ ->
            log("改用普通对话模式")
            conversing = true
            startListening()
        }
    }

    private fun changed() = onChange?.invoke()
    private fun setPhase(p: Phase) { phase = p; changed() }

    /// 打开画面时叫醒语音服务
    fun warmUp() { scope.launch { Voice.warmUp() } }

    /// 点了麦克风按钮：没事时开始连续对话；正在听就当说完了；正在想或正在说就打断她，接着听你说
    fun tap() {
        // 实时语音：点一下开始，再点一下结束（中间直接说话就行，能随时插话）
        if (live.phase != LiveTalk.Phase.OFF) { live.stop(); return }
        if (phase == Phase.IDLE && live.available) {
            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { needMic?.invoke(); return }
            live.start(); return
        }
        when (phase) {
            Phase.IDLE -> { conversing = true; startListening() }
            Phase.LISTENING -> recognizer?.stopListening()
            Phase.THINKING, Phase.SPEAKING -> if (conversing) interrupt() else cancel()
        }
    }

    private fun interrupt() {
        llmJob?.cancel(); llmJob = null
        stopSpeaking()
        setPhase(Phase.IDLE)
        beginRecording()
    }

    fun cancel() {
        live.stop()
        conversing = false
        llmJob?.cancel(); llmJob = null
        stopRecognizer()
        stopSpeaking()
        setPhase(Phase.IDLE)
        face.send("type" to "robot_state", "state" to "idle", "emotion" to "neutral")
    }

    fun destroy() {
        cancel()
        recognizer?.destroy(); recognizer = null
        scope.cancel()
    }

    // ───────── 听 ─────────

    private fun startListening() {
        if (store.llmBase.isEmpty() || store.llmModel.isEmpty() || store.llmKey.isEmpty()) {
            conversing = false
            say("请先在设置里填好大模型的密钥，我才能回答你。", "confused")
            return
        }
        warmUp()
        beginRecording()
    }

    private fun beginRecording() {
        if (phase != Phase.IDLE) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            conversing = false
            say("这台手机没有语音识别服务，可以打字和我聊。", "confused")
            return
        }
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            conversing = false
            say("请先允许我用麦克风。", "confused")
            needMic?.invoke()
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also { recognizer = it }
        heard = ""
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { heard = it }
            }
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { heard = it }
                finishListening()
            }
            override fun onError(error: Int) {
                // 没听到话（超时、没匹配上）就当这一轮结束；有半句就照半句回答
                finishListening()
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        }
        setPhase(Phase.LISTENING)
        face.send("type" to "sensing", "mic" to true)
        face.send("type" to "robot_state", "state" to "listening", "emotion" to "neutral")
        try { r.startListening(intent) } catch (e: Exception) { finishListening() }
    }

    /// 没有麦克风权限时，让界面去要（MainActivity 填上）
    var needMic: (() -> Unit)? = null

    private fun stopRecognizer() {
        try { recognizer?.cancel() } catch (e: Exception) {}
        face.send("type" to "sensing", "mic" to false)
    }

    private fun finishListening() {
        if (phase != Phase.LISTENING) return
        stopRecognizer()
        val text = heard.trim()
        if (text.isEmpty()) {
            if (conversing) log("一直没说话，对话结束")
            conversing = false
            setPhase(Phase.IDLE)
            face.send("type" to "robot_state", "state" to "idle", "emotion" to "neutral")
            return
        }
        log("听到：$text")
        // 说「再见」：这一句还是让她回答（道个别），回答完不再接着听
        if (BYE.any { text.contains(it) }) conversing = false
        respond(text)
    }

    // ───────── 打字 ─────────

    /// 聊天框里打了一句话。短指令（「跳舞」「特写」「转身 左 90」…）直接做；其余交给大模型，她用声音回答
    fun typed(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        captionGen++
        userLine = text; changed()
        Store.backgroundCommand(text, store.background)?.let { id ->
            store.background = id
            log("背景：${Store.backgroundName(id)}")
            scheduleClearCaptions()
            return
        }
        Body.quickCommand(text)?.let { q ->
            if (q.msg.optString("type") == "body") {
                // 云台的短指令：主人亲自下的，优先级最高；「停」连屏幕里的动作一起停
                val move = Body.gimbalMove(q.msg)
                if (move.action == "stop") { face.send("type" to "action", "name" to "stop"); gimbalChain?.cancel() }
                scope.launch { log("云台（${Body.describe(move)}）：${Body.perform(gimbal, move, Gimbal.Owner.MANUAL)}") }
            } else {
                face.send(q.msg)
            }
            log("打字指令：${q.label}")
            scheduleClearCaptions()
            return
        }
        subtitle = ""; changed()
        if (live.phase != LiveTalk.Phase.OFF) {
            if (live.sendText(text)) log("打字：$text") else log("实时语音还没连上，这句没发出去")
            return
        }
        log("打字：$text")
        conversing = false
        llmJob?.cancel(); llmJob = null
        stopRecognizer()
        stopSpeaking()
        setPhase(Phase.IDLE)
        respond(text)
    }

    /// 回答一句话（听到的或者打的）：停、换背景、换装直接办，其余问大模型
    private fun respond(text: String) {
        userLine = text; subtitle = ""; captionGen++; changed()
        Body.quickCommand(text)?.takeIf { it.msg.optString("type") == "body" }?.let { q ->
            when (q.msg.optString("action")) {
                "stop" -> {
                    face.send("type" to "action", "name" to "stop")
                    gimbalChain?.cancel()
                    gimbal.halt()
                    log("停下（主人说的）")
                    say("好，停了。", "neutral")
                    return
                }
                "follow_off" -> {
                    log("跟随：" + (Body.followSwitch?.invoke(false) ?: "没有这个功能"))
                    say("好，不跟了。", "neutral")
                    return
                }
            }
        }
        setPhase(Phase.THINKING)
        Store.backgroundCommand(text, store.background)?.let { id ->
            store.background = id
            log("背景：${Store.backgroundName(id)}")
            say("好，换到${Store.backgroundName(id)}啦。", "happy")
            return
        }
        Store.outfitCommand(text, store.outfit)?.let { id ->
            store.outfit = id
            log("换装：${Store.outfitName(id)}")
            say(if (id == "default") "好，换回原来的衣服啦。" else "好呀，换上${Store.outfitName(id)}啦，好看吗？", "happy")
            return
        }
        if (store.llmBase.isEmpty() || store.llmModel.isEmpty() || store.llmKey.isEmpty()) {
            conversing = false
            say("请先在设置里填好大模型的密钥，我才能回答你。", "confused")
            return
        }
        face.send("type" to "robot_state", "state" to "thinking", "emotion" to "neutral")
        llmJob = scope.launch { ask(text) }
    }

    // ───────── 想 ─────────

    private suspend fun ask(text: String) {
        history += JSONObject().put("role", "user").put("content", text)
        while (history.size > 12) history.removeAt(0)
        beginSpeaking()
        try {
            // 她写了 [看一眼] 就拍一张，把看到的告诉她，让她接着回答；一句话里最多看 4 次
            var looks = 0
            while (true) {
                val system = Chat.PERSONA + "\n" + Body.PERSONA_TAGS + "\n" + Body.eyesNote(store.allowSee) + "\n云台现在的状态：" + gimbal.stateText()
                val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                history.forEach { messages.put(it) }
                buffer = ""; reply = ""; emotionKnown = false; lookWanted = null
                if (looks == 0) emotion = "neutral"
                Chat.stream(store.llmBase, store.llmKey, store.llmModel, messages) { onPiece(it) }
                flush(final = true)
                history += JSONObject().put("role", "assistant").put("content", reply.ifEmpty { "嗯。" })
                log("回答：$reply")
                val front = lookWanted
                if (front == null || looks >= 4) break
                looks++
                gimbalChain?.join()   // 先等云台转到位再拍
                val seen = Eyes.look(front, store, face)
                log("眼睛：${seen.second}")
                val more = if (looks < 4) "" else "已经看了 4 次，不要再写 [看一眼] 了。"
                history += JSONObject().put("role", "user").put("content", "（这是 App 告诉你的，不是主人说的：${seen.first}\n现在照着这个回答主人刚才的话。$more）")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (history.isNotEmpty() && history.last().optString("role") == "user") history.removeAt(history.size - 1)
            log("大模型出错：${e.message}")
            if (reply.isEmpty()) { emotion = "confused"; emotionKnown = true; enqueue("我好像连不上大脑了。") }
        }
        queue.close()
    }

    private fun onPiece(piece: String) {
        if (phase != Phase.THINKING && phase != Phase.SPEAKING) return
        buffer += piece
        flush(final = false)
    }

    /// 从 buffer 里切出完整的句子交给语音；final 时把剩下的也交出去
    private fun flush(final: Boolean) {
        // 有的模型先输出 <think>…</think>，想完之前什么都不读
        buffer = buffer.replace(Regex("(?s)<think>.*?</think>"), "")
        if (buffer.contains("<think>") && !final) return
        if (!emotionKnown) {
            val head = buffer.trim()
            if (head.startsWith("[")) {
                val close = head.indexOf(']')
                if (close < 0) {
                    if (!final && head.length < 16) return
                    emotionKnown = true
                    return flush(final)
                }
                val tag = head.substring(1, close).lowercase()
                // 只拿走情绪标签；开头要是动作标签（[左转 30]）就留着，后面照样执行
                if (tag in Chat.EMOTIONS) { emotion = tag; buffer = head.substring(close + 1) }
                emotionKnown = true
            } else if (head.isNotEmpty() || final) {
                emotionKnown = true
            } else return
        }
        while (true) {
            val cut = sentenceEnd(buffer) ?: break
            val sentence = buffer.substring(0, cut)
            buffer = buffer.substring(cut)
            enqueue(sentence)
        }
        if (final) { enqueue(buffer); buffer = "" }
    }

    // ───────── 说 ─────────

    private fun beginSpeaking() {
        stopSpeaking()
        startedSpeaking = false
        val q = Channel<Line>(Channel.UNLIMITED)
        queue = q
        playJob = scope.launch { runQueue(q) }
    }

    /// 别人（遥控指令）让她说一句话。text 里也可以带动作、镜头、转身标签
    fun speak(text: String, emotion: String = "neutral"): Boolean {
        if (live.phase != LiveTalk.Phase.OFF) return false   // 实时语音进行中不插话
        conversing = false
        llmJob?.cancel(); llmJob = null
        stopRecognizer()
        setPhase(Phase.IDLE)
        say(text, emotion)
        return true
    }

    private fun say(text: String, emo: String) {
        beginSpeaking()
        emotion = emo
        enqueue(text)
        queue.close()
    }

    private fun enqueue(raw: String) {
        // 云台标签：一个接一个转（上一个转完再转下一个），不耽误说话
        for (move in Body.gimbalMoves(raw)) {
            val previous = gimbalChain
            gimbalChain = scope.launch {
                previous?.join()
                if (!isActive) return@launch
                log("云台（${Body.describe(move)}）：${Body.perform(gimbal, move)}")
            }
        }
        Body.seeTag(raw)?.let { lookWanted = it }
        for (name in Body.outfitTags(raw)) {
            val id = Store.outfitID(name)
            if (id == null) log("换装：没有叫「$name」的衣服") else { store.outfit = id; log("换装：${Store.outfitName(id)}") }
        }
        for (name in Body.viewTags(raw)) {
            val shot = Body.shotID(name)
            if (shot == null) log("镜头：没有「$name」") else { face.send("type" to "view", "shot" to shot); log("镜头：$name") }
        }
        for (yaw in Body.turnTags(raw)) { face.send("type" to "turn", "yaw" to yaw); log("转身：${yaw.toInt()}°") }
        for (name in Body.actionTags(raw)) {
            val id = Body.actionID(name)
            if (id == null) log("动作：不会做「$name」") else { face.send("type" to "action", "name" to id); log("动作：$name") }
        }
        val text = Chat.clean(Body.strip(raw))
        if (text.none { it.isLetterOrDigit() }) return
        reply += text
        subtitle = reply; changed()
        queue.trySend(Line(text, scope.async(Dispatchers.IO) { Voice.fetchWithBackup(text) }))
    }

    private suspend fun runQueue(q: Channel<Line>) {
        for (line in q) {
            val data = line.audio.await()
            if (!startedSpeaking) {
                startedSpeaking = true
                setPhase(Phase.SPEAKING)
                face.send("type" to "robot_state", "state" to "speaking", "emotion" to emotion)
            }
            if (data != null && WavPlayer.play(data) { level -> face.send("type" to "robot_state", "mouth_level" to level.toDouble()) }) continue
            systemVoice.speak(line.text)
        }
        doneSpeaking()
    }

    private fun stopSpeaking() {
        playJob?.cancel(); playJob = null
        queue.close()
        systemVoice.stop()
    }

    private fun scheduleClearCaptions() {
        val gen = captionGen
        main.postDelayed({
            if (captionGen == gen && phase == Phase.IDLE) { userLine = ""; subtitle = ""; changed() }
        }, 10_000)
    }

    private fun doneSpeaking() {
        playJob = null
        if (phase != Phase.SPEAKING && phase != Phase.THINKING) return
        setPhase(Phase.IDLE)
        if (conversing) {
            // 连续对话：说完马上接着听（留一点时间让声音彻底停下，免得把自己的声音录进去）
            main.postDelayed({ if (conversing && phase == Phase.IDLE) beginRecording() }, 300)
            return
        }
        face.send("type" to "robot_state", "state" to "idle")
        scheduleClearCaptions()
        main.postDelayed({ if (phase == Phase.IDLE) face.send("type" to "robot_state", "emotion" to "neutral") }, 2000)
    }

    companion object {
        private val BYE = listOf("再见", "拜拜", "不聊了", "晚安", "先这样", "退下")

        /// 句号、问号、感叹号、换行处断句；太长的句子在逗号处先断，让第一句尽快读出来
        fun sentenceEnd(s: String): Int? {
            val hard = "。！？!?；;\n…"
            val soft = "，,、："
            for ((i, c) in s.withIndex()) {
                val count = i + 1
                if (c in hard && count >= 2) return i + 1
                if (c in soft && count >= 14) return i + 1
            }
            return null
        }
    }
}
