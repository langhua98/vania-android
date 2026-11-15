package com.langhua98.vania

import android.app.Application
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/// 整个 App 共用的东西：设置、脸的桥、云台、对话、运行记录。对应 iOS 版的 RemoteEngine（第 2 批再加待命循环）
class VaniaApp : Application() {
    lateinit var store: Store; private set
    val face = FaceBridge()
    val gimbal = Gimbal()
    lateinit var talk: Talk; private set

    /// 运行记录（设置页最下面显示），最多留 200 行。不记密钥、不记照片内容
    val logs = ArrayDeque<String>()
    var onLog: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        talk = Talk(this, store, face, gimbal) { log(it) }
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

    companion object {
        lateinit var instance: VaniaApp; private set
    }
}
