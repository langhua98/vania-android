package com.langhua98.vania

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/// 整个 App 共用的东西：设置、脸的桥、云台、对话、待命、运行记录。对应 iPhone 版的 RemoteEngine
class VaniaApp : Application() {
    lateinit var store: Store; private set
    val face = FaceBridge()
    lateinit var gimbal: Gimbal; private set
    /// 跟着人转（见 Follow.kt）
    lateinit var follower: FaceFollower; private set
    lateinit var talk: Talk; private set

    /// 运行记录（设置页最下面显示），最多留 200 行。不记密钥、不记照片内容
    val logs = ArrayDeque<String>()
    var onLog: (() -> Unit)? = null
    /// 待命开了 / 关了（界面刷新用）
    val onStandby = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var standbyJob: Job? = null
    /// 有没有界面在前台（拍照、录音、定位只在前台做）
    var foreground = false; private set

    val running: Boolean get() = standbyJob?.isActive == true

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        gimbal = Gimbal(this)
        gimbal.onLog = { log(it) }
        // 脸跟着云台的朝向看过去（网页的 gimbal 消息：yaw 负 = 屏幕左，pitch 正 = 抬头）
        gimbal.onPose = { yaw, pitch, connected ->
            face.send("type" to "gimbal", "yaw" to Math.round(yaw * 10) / 10.0, "pitch" to Math.round(pitch * 10) / 10.0, "connected" to connected)
        }
        if (store.gimbalAuto && gimbal.hasPermission()) gimbal.connect()
        follower = FaceFollower(this, gimbal)
        follower.onLog = { log(it) }
        follower.onSensing = { on -> face.send("type" to "sensing", "camera" to on) }
        // 跟随开着时前摄被它占着，「看一眼」从它那里借一帧
        Eyes.borrowFrame = { follower.snapshot() }
        // 大模型、聊天框开关跟随
        Body.followSwitch = { on ->
            val why = if (on) follower.blocker() else null
            if (why != null) why else {
                setFollow(on)
                if (on) "好，开始跟着主人了：前面的摄像头在找主人的脸，手机会自己一直面对着主人。" else "不跟了，摄像头已经关掉。"
            }
        }
        talk = Talk(this, store, face, gimbal) { log(it) }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var resumed = 0
            override fun onActivityResumed(a: Activity) { resumed++; foreground = true }
            override fun onActivityPaused(a: Activity) { resumed = (resumed - 1).coerceAtLeast(0); foreground = resumed > 0 }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    fun log(s: String) {
        val line = "${fmt.format(Date())}  $s"
        val add = Runnable {
            logs.addLast(line)
            while (logs.size > 200) logs.removeFirst()
            onLog?.invoke()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) add.run() else main.post(add)
    }

    /// 开关「跟着人转」。设置页的开关变了也走这里
    fun setFollow(on: Boolean) {
        if (on) {
            follower.blocker()?.let { log("跟随：$it"); store.follow = false; follower.onChange.toList().forEach { f -> f() }; return }
            store.follow = true
            follower.start()
        } else {
            store.follow = false
            follower.stop()
        }
    }

    // ───────── 待命 ─────────

    fun startStandby() {
        if (running) return
        if (store.token.isEmpty()) { log("先填云盘令牌"); return }
        val device = store.device
        val standby = Standby(
            cloud = Cloud(store.base, store.token),
            device = device,
            pair = store.pair,
            isSeen = { store.isSeen(it) },
            markSeen = { store.markSeen(it) },
            log = { log(it) },
            execute = { cmd -> withContext(Dispatchers.Main) { execute(cmd) } },
            extra = { action -> withContext(Dispatchers.Main) { extra(action) } },
            events = Standby.Events(
                started = { a -> main.post { face.commandStarted(a) } },
                finished = { a, ok -> if (a != "robot") main.post { face.commandFinished(ok) } },
                rejected = { main.post { face.commandRejected() } },
            ),
        )
        log("开始待命（设备名：$device）")
        standbyJob = scope.launch(Dispatchers.IO) { standby.run() }
        startForegroundService(Intent(this, StandbyService::class.java))
        onStandby.toList().forEach { it() }
    }

    fun stopStandby() {
        standbyJob?.cancel()
        standbyJob = null
        stopService(Intent(this, StandbyService::class.java))
        log("已停止待命")
        onStandby.toList().forEach { it() }
    }

    private suspend fun execute(cmd: org.json.JSONObject): CmdResult = when (cmd.optString("do")) {
        // 云台要用这台 App 里连着的云台，机器人要用屏幕上的脸，都不走 Commands
        "gimbal" -> CmdResult(gimbal.command(cmd))
        "robot" -> RobotCommand.run(cmd, face, talk, store)
        else -> Commands.run(this, cmd, foreground)
    }

    private suspend fun extra(action: String): Map<String, Any> = when (action) {
        "gimbal" -> mapOf("gimbal" to gimbal.statusJSON())
        "robot" -> RobotCommand.status(face)?.let { mapOf("robot" to it) } ?: emptyMap()
        else -> emptyMap()
    }

    companion object {
        lateinit var instance: VaniaApp; private set
    }
}
