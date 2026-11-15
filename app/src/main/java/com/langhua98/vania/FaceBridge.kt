package com.langhua98.vania

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/// App 和 3D 角色网页（assets/live2d，来自 xiaoju-remote 的 Robot/live2d）之间互相发消息。对应 iOS 版的 FaceBridge。
/// App → 网页：window.YunlaiFace.apply({...})；网页 → App：网页里写的是 window.webkit.messageHandlers.yunlai.postMessage(obj)，
/// 安卓没有这个对象，加载前注入一小段脚本把它接到 YunlaiAndroid.post(json) 上（见 SHIM）。
/// 只在主线程调用。
class FaceBridge {
    private var web: WebView? = null
    private var ready = false
    private val pending = ArrayDeque<String>()
    /// 网页发来的消息（touch、outfit_done、action_done…）
    var onMessage: ((JSONObject) -> Unit)? = null

    val isReady: Boolean get() = ready && web != null

    /// 发一条消息给脸，格式见 xiaoju-remote 的 Robot/README.md（robot_state、sensing 等）
    fun send(msg: JSONObject) {
        val js = "window.YunlaiFace && window.YunlaiFace.apply(${msg})"
        val w = web
        if (ready && w != null) w.evaluateJavascript(js, null)
        else {
            pending.addLast(js)
            while (pending.size > 20) pending.removeFirst()
        }
    }

    fun send(vararg kv: Pair<String, Any?>) = send(JSONObject().apply { kv.forEach { put(it.first, it.second ?: JSONObject.NULL) } })

    /// 发一条消息，拿回 apply 的返回值（Boolean、JSONObject…）。页面没好返回 null
    suspend fun request(msg: JSONObject): Any? = eval("window.YunlaiFace ? JSON.stringify(window.YunlaiFace.apply($msg)) : null")

    /// 脸现在的状态（window.YunlaiFace.get()）
    suspend fun status(): JSONObject? = eval("window.YunlaiFace ? JSON.stringify(window.YunlaiFace.get()) : null") as? JSONObject

    private suspend fun eval(js: String): Any? {
        val w = web ?: return null
        if (!ready) return null
        val raw: String? = suspendCancellableCoroutine { c -> w.evaluateJavascript(js) { c.resume(it) } }
        // evaluateJavascript 把结果再包一层 JSON：先解出字符串，再解里面的 JSON
        val inner = try { JSONTokener(raw ?: "null").nextValue() as? String } catch (e: Exception) { null } ?: return null
        return try { JSONTokener(inner).nextValue() } catch (e: Exception) { null }
    }

    @SuppressLint("JavascriptInterface")
    fun attach(w: WebView) {
        web = w
        w.addJavascriptInterface(object {
            @JavascriptInterface
            fun post(json: String) {
                val o = try { JSONObject(json) } catch (e: Exception) { return }
                w.post { onMessage?.invoke(o) }
            }
        }, "YunlaiAndroid")
    }

    fun pageLoaded() {
        val w = web ?: return
        ready = true
        while (pending.isNotEmpty()) w.evaluateJavascript(pending.removeFirst(), null)
    }

    fun pageClosed() {
        ready = false
    }

    fun detach() {
        ready = false
        web = null
    }

    companion object {
        /// 让网页里 iOS 的写法在安卓上也能用
        const val SHIM = """
window.webkit = window.webkit || {};
window.webkit.messageHandlers = window.webkit.messageHandlers || {};
window.webkit.messageHandlers.yunlai = { postMessage: function (m) { try { YunlaiAndroid.post(JSON.stringify(m)); } catch (e) {} } };
"""
    }
}
