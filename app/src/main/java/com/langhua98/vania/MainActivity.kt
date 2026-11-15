package com.langhua98.vania

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/// 主界面：整个屏幕是 3D 角色（App 里的 assets/live2d 网页），下面是字幕和聊天框，左上角齿轮打开设置。
/// 对应 iOS 版的 RobotModeView。打开 App 就是她；第一次用（密钥都没填）自动打开设置。
class MainActivity : Activity() {
    private val app get() = application as VaniaApp
    private lateinit var root: FrameLayout
    private var web: WebView? = null
    private lateinit var panel: LinearLayout
    private lateinit var userBubble: TextView
    private lateinit var herBubble: TextView
    private lateinit var input: EditText
    private lateinit var actionBtn: TextView
    private lateinit var status: TextView
    private var crashes = 0
    private val standbyListener: () -> Unit = { refresh() }
    private var loadedQuality = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        buildFace(app.store.faceQuality)
        buildOverlay()

        app.face.onMessage = { handle(it) }
        app.talk.onChange = { refresh() }
        app.talk.needMic = { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1) }
        app.store.onChange { key ->
            when (key) {
                "outfit" -> app.face.send("type" to "outfit", "id" to app.store.outfit)
                "background" -> app.face.send("type" to "bg", "id" to app.store.background)
                "faceQuality" -> if (app.store.faceQuality != loadedQuality) {
                    app.log("画质改成：${if (app.store.faceQuality == "low") "省电" else "高"}，重新加载画面")
                    buildFace(app.store.faceQuality)
                }
            }
        }
        app.talk.warmUp()
        app.onStandby += standbyListener
        if (savedInstanceState == null && app.store.autoStandby && app.store.token.isNotEmpty() && !app.running) app.startStandby()
        refresh()
        val s = app.store
        if (s.llmKey.isEmpty() && s.geminiKey.isEmpty() && s.token.isEmpty() && savedInstanceState == null) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onDestroy() {
        app.onStandby -= standbyListener
        app.talk.cancel()
        app.face.detach()
        web?.destroy()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        app.talk.cancel()
    }

    // ───────── 3D 角色 ─────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildFace(quality: String) {
        web?.let { old -> app.face.detach(); root.removeView(old); old.destroy() }
        loadedQuality = quality
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        val w = WebView(this)
        w.setBackgroundColor(Color.BLACK)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
        }
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        app.face.attach(w)
        // 网页里写的是 iOS 的发消息办法，加载前接到安卓上
        val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (early) WebViewCompat.addDocumentStartJavaScript(w, FaceBridge.SHIM, setOf("https://appassets.androidplatform.net"))
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.host != "appassets.androidplatform.net"

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!early) view.evaluateJavascript(FaceBridge.SHIM, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                app.face.pageLoaded()
                app.face.send("type" to "outfit", "id" to app.store.outfit)
                app.face.send("type" to "bg", "id" to app.store.background)
            }

            /// 画面进程被系统关掉（多半是内存不够）：用省电画质重新打开，最多试 3 次
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                app.face.detach()
                crashes++
                app.log("画面进程被系统关掉了（多半是内存不够）${if (crashes <= 3) "，用省电画质重新打开" else ""}")
                root.post { if (crashes <= 3) buildFace("low") }
                return true
            }
        }
        root.addView(w, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = w
        val outfit = Uri.encode(app.store.outfit)
        w.loadUrl("https://appassets.androidplatform.net/assets/live2d/index.html?demo=0&outfit=$outfit${Store.faceQuery(quality)}")
    }

    /// 网页发来的消息：换装结果记一笔；点了一下画面：正在打字就收起键盘，她在听、想、说时打断
    private fun handle(msg: org.json.JSONObject) {
        when (msg.optString("type")) {
            "outfit_done" -> {
                val id = msg.optString("id")
                if (msg.optBoolean("ok")) app.log("换上了：${Store.outfitName(id)}")
                else app.log("换装失败（$id）：${msg.optString("error", "不知道为什么")}")
            }
            "touch" -> {
                if (input.hasFocus()) { hideKeyboard(); return }
                if (app.talk.phase != Talk.Phase.IDLE) app.talk.tap()
            }
        }
    }

    // ───────── 字幕、聊天框、齿轮 ─────────

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun round(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun circleButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = round(0x61000000, 21, 0x2EFFFFFF)
        setOnClickListener { onClick() }
    }

    private fun buildOverlay() {
        // 左上角齿轮
        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0x8CFFFFFF.toInt())
            background = round(0x40000000, 19)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        root.addView(gear, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })

        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(4)) }
        // 字幕：你刚说的（靠右、淡一点）和她正在说的
        userBubble = TextView(this).apply {
            textSize = 13f; setTextColor(0xD9FFFFFF.toInt())
            setPadding(dp(12), dp(7), dp(12), dp(7)); background = round(0x29FFFFFF, 14)
        }
        herBubble = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); maxLines = 4
            setPadding(dp(12), dp(8), dp(12), dp(8)); background = round(0x6B000000, 14)
        }
        panel.addView(userBubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END; leftMargin = dp(60); bottomMargin = dp(6)
        })
        panel.addView(herBubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.START; rightMargin = dp(40); bottomMargin = dp(8)
        })

        // 聊天框：左边快捷菜单，中间打字，右边有字时是发送、没字时是麦克风
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        lateinit var menuBtn: TextView
        menuBtn = circleButton("✦") { showMenu(menuBtn) }
        input = EditText(this).apply {
            hint = "输入消息……"
            setHintTextColor(0x80FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(16), 0, dp(16), 0)
            background = round(0x61000000, 21, 0x2EFFFFFF)
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEND || (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) { submit(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { refresh() }
            })
        }
        actionBtn = circleButton("🎤") { if (input.text.isNotBlank()) submit() else app.talk.tap() }
        bar.addView(menuBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
        bar.addView(input, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        bar.addView(actionBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
        panel.addView(bar)

        // 最下面一行淡淡的状态
        status = TextView(this).apply { textSize = 11f; setTextColor(0x59FFFFFF); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        panel.addView(status)
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // 键盘弹出来时只有聊天框往上走，画面不缩
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottom = maxOf(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            panel.setPadding(dp(12), 0, dp(12), bottom + dp(4))
            insets
        }
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        Body.MENU.forEachIndexed { g, group ->
            val sub = pm.menu.addSubMenu(0, g, g, group.title)
            group.items.forEachIndexed { i, item -> sub.add(g, g * 100 + i, i, item.name) }
        }
        pm.setOnMenuItemClickListener { mi ->
            if (mi.hasSubMenu()) return@setOnMenuItemClickListener false
            val g = mi.itemId / 100; val i = mi.itemId % 100
            Body.MENU.getOrNull(g)?.items?.getOrNull(i)?.let { app.talk.typed(it.name) }
            true
        }
        pm.show()
    }

    private fun submit() {
        val text = input.text.toString()
        input.setText("")
        hideKeyboard()
        app.talk.typed(text)
    }

    private fun hideKeyboard() {
        input.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun refresh() {
        val t = app.talk
        userBubble.text = t.userLine
        userBubble.visibility = if (t.userLine.isEmpty()) View.GONE else View.VISIBLE
        herBubble.text = t.subtitle
        herBubble.visibility = if (t.subtitle.isEmpty()) View.GONE else View.VISIBLE
        val typing = input.text.isNotBlank()
        val active = t.phase != Talk.Phase.IDLE
        actionBtn.text = when {
            typing -> "➤"
            t.phase == Talk.Phase.IDLE -> "🎤"
            t.phase == Talk.Phase.LISTENING -> "〰"
            else -> "■"
        }
        actionBtn.background = round(if (typing || active) 0xD9FF7A17.toInt() else 0x61000000, 21, 0x2EFFFFFF)
        val hint = when (t.phase) {
            Talk.Phase.IDLE -> "打字或点麦克风聊天"
            Talk.Phase.LISTENING -> if (t.conversing) "在听，说「再见」结束" else "正在听"
            Talk.Phase.THINKING -> "在想"
            Talk.Phase.SPEAKING -> "点一下打断"
        }
        status.text = (if (app.running) "● 待命中 · ${app.store.device}" else "○ 未待命") + " · $hint"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) app.talk.tap()
    }
}
