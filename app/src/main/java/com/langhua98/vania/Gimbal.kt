package com.langhua98.vania

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/// 大疆 OM 手机云台（OM6 上实测过；协议见 Duml.kt）。对应 iPhone 版的 Gimbal.swift，控制逻辑一样：
/// - 蓝牙低功耗：服务 FFF0，往 FFF5 写指令，FFF4 收推送。
/// - 转动只认速度指令 04/0c：每 0.1 秒发一帧，停发云台自己停。
/// - 姿态 04/05 大约每秒一帧。「转到指定角度」边发速度边看姿态（闭环），误差大约 ±2～5°。
/// - 给大模型和聊天框用的是 aim：一套角度（相对正面，右、上为正），软限位，小角度先快转再按姿态补偏差。
/// - 谁在控制（Owner）：主人 > 遥控端 > 大模型 > 自动跟随，高的打断低的。
/// 蓝牙回调在别的线程，都先转到主线程；状态只在主线程改。
@SuppressLint("MissingPermission")
class Gimbal(private val ctx: Context) {
    enum class Phase(val label: String) {
        OFF("未连接"), SCANNING("在找云台"), CONNECTING("正在连接"), READY("已连接"),
        NO_BLUETOOTH("蓝牙关着"), DENIED("没有蓝牙权限"), UNSUPPORTED("这台手机不支持蓝牙低功耗"),
    }
    enum class Axis { YAW, PITCH }

    /// 谁在控制，优先级从低到高
    enum class Owner(val label: String) { NOBODY("没有人"), FOLLOW("自动跟随"), MODEL("大模型"), REMOTE("遥控端"), MANUAL("主人") }

    var phase = Phase.OFF; private set
    var name = ""; private set
    var model = ""; private set
    var battery: Int? = null; private set
    var charging = false; private set
    var asleep = false; private set
    var attitude: Duml.Attitude? = null; private set
    var busy = false; private set
    var note = ""; private set

    /// 状态变了（设置页刷新用）
    val onChange = mutableSetOf<() -> Unit>()
    /// 朝向变了（大约每秒一次）或者断了：(右为正的度数, 上为正的度数, 连着没有)。给机器人的脸用
    var onPose: ((Double, Double, Boolean) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = ctx.getSharedPreferences("gimbal", Context.MODE_PRIVATE)
    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private var gatt: BluetoothGatt? = null
    private var writer: BluetoothGattCharacteristic? = null
    private var chunk = 20
    private val writes = ArrayDeque<ByteArray>()
    private var writing = false
    private var parser = Duml.Parser()
    private var seq = 1
    private var wantConnected = false
    private var attSeq = 0
    private var lastAttAt = 0L
    private var connectTimeout: Runnable? = null
    private var scanTimer: Runnable? = null
    private val notGimbal = mutableSetOf<String>()

    // 定时转（move）：每 0.1 秒发一帧，到时间发零速
    private var driveYaw = 0
    private var drivePitch = 0
    private var driveUntil = 0L
    private var ticking = false
    private var aborted = false
    /// 每开始一个新的转动（或者停下）就加 1。还在跑的旧转动看到这个数变了就自己退出
    private var gen = 0

    var owner = Owner.NOBODY; private set
    /// 刚下的指令要转到哪（姿态大约一秒才来一帧，转完马上问朝向时用这个）
    private var aimed: Triple<Double, Double, Long>? = null
    /// 主人、遥控端、大模型刚让云台转过：这个时间之前自动跟随不动它
    private var heldUntil = 0L
    private var tracking = false

    /// 归位点（正面）：连上后第一帧姿态自动记下，也可以「设为正面」
    private var home: Pair<Double, Double>? = null
    /// 速度单位的正负和角度变化方向是否一致（不一致的话自己学，记在手机上）
    private val dirSign = mutableMapOf(Axis.YAW to 1.0, Axis.PITCH to 1.0)
    /// 这个轴的速度正负已经用实测确认过（闭环转过一次）。没确认过的轴不走「先快后准」
    private val dirKnown = mutableSetOf<Axis>()

    init {
        for (ax in Axis.values()) {
            if (prefs.getFloat("dir.$ax", 1f) < 0) dirSign[ax] = -1.0
            if (prefs.getBoolean("dirKnown.$ax", false)) dirKnown += ax
        }
    }

    val connected get() = phase == Phase.READY

    /// 「抬头」在姿态里是变大还是变小。OM6 上 William 实测是变小（-1）；真机上反了就在设置页打开「抬头低头反了」
    val upSign get() = if (pitchFlipped) 1.0 else -1.0
    var pitchFlipped: Boolean
        get() = prefs.getBoolean("pitchFlip", false)
        set(v) { prefs.edit().putBoolean("pitchFlip", v).apply(); changed() }

    /// 相对归位点的朝向（度，姿态里的正负）。没连上返回 null
    val pose: Pair<Double, Double>? get() {
        val a = attitude ?: return null
        val h = home ?: return null
        return Duml.wrap(a.yaw - h.first) to Duml.wrap(a.pitch - h.second)
    }

    /// 给人和大模型看的朝向：右、上为正（度，相对正面）。刚下过指令、姿态还没更新时用指令的目标
    val heading: Pair<Double, Double>? get() {
        val a = aimed
        if (a != null && System.currentTimeMillis() - a.third < 2500 && connected) return a.first to a.second * upSign
        val p = pose ?: return null
        return p.first to p.second * upSign
    }

    private fun changed() = onChange.toList().forEach { it() }
    private fun log(s: String) = onLog?.invoke(s)
    private fun setPhase(p: Phase, n: String = note) { phase = p; note = n; changed() }

    // ───────── 连接 ─────────

    /// 需要的蓝牙权限（Android 12 起是「附近的设备」，以前是定位）
    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermission() = permissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun connect() {
        wantConnected = true
        if (phase == Phase.SCANNING || phase == Phase.CONNECTING || phase == Phase.READY) return
        notGimbal.clear()
        start()
    }

    fun disconnect() {
        wantConnected = false
        stopNow()
        stopScan()
        connectTimeout?.let { main.removeCallbacks(it) }; connectTimeout = null
        gatt?.let { try { it.disconnect(); it.close() } catch (e: Exception) {} }
        gatt = null; writer = null
        setPhase(Phase.OFF, "")
        onPose?.invoke(0.0, 0.0, false)
    }

    private fun start() {
        if (!wantConnected) return
        val ad = adapter ?: return setPhase(Phase.UNSUPPORTED)
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return setPhase(Phase.UNSUPPORTED)
        if (!hasPermission()) return setPhase(Phase.DENIED, "在设置页点「授权硬件」，允许「附近的设备」（蓝牙）")
        if (!ad.isEnabled) return setPhase(Phase.NO_BLUETOOTH, "在下拉菜单或设置里打开蓝牙")
        // 1. 云台已经和这台手机连着（在系统设置里连的，或者刚用过 Mimo）：这时它不广播，扫描找不到，要直接拿
        systemConnected(logAll = true)?.let { d ->
            log("云台已经和手机连着：${d.name ?: "云台"}")
            return attach(d, fallbackToScan = true)
        }
        // 2. 上次连过的那台（不用扫描）
        prefs.getString("address", null)?.takeIf { it !in notGimbal && BluetoothAdapter.checkBluetoothAddress(it) }?.let { addr ->
            return attach(ad.getRemoteDevice(addr), fallbackToScan = true)
        }
        // 3. 扫描
        scan()
    }

    private fun isGimbalName(n: String?) = n != null && NAME_PREFIXES.any { n.startsWith(it) }

    /// 系统里已经连着的云台（名字是 OM / Osmo / DJI 开头）
    private fun systemConnected(logAll: Boolean = false): BluetoothDevice? {
        val all = try { manager?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList() } catch (e: SecurityException) { emptyList() }
        if (logAll) log(if (all.isEmpty()) "系统里没有连着的蓝牙低功耗设备" else "系统里连着：${all.joinToString("、") { it.name ?: "（没名字）" }}")
        return all.firstOrNull { it.address !in notGimbal && isGimbalName(it.name) }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            main.post {
                val d = r.device
                val n = d.name ?: r.scanRecord?.deviceName ?: ""
                val svc = r.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE)) == true
                if (phase != Phase.SCANNING || d.address in notGimbal || !(isGimbalName(n) || svc)) return@post
                stopScan()
                log("找到云台：$n")
                attach(d, fallbackToScan = false)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            main.post { if (phase == Phase.SCANNING) { stopScan(); setPhase(Phase.OFF, "扫描失败（错误 $errorCode），关掉蓝牙再打开试试") } }
        }
    }

    private fun scan() {
        val sc = adapter?.bluetoothLeScanner ?: return setPhase(Phase.NO_BLUETOOTH, "在下拉菜单或设置里打开蓝牙")
        gatt = null; writer = null
        setPhase(Phase.SCANNING, "把云台打开、放在手机旁边")
        // OM 系列不一定在广播包里放 FFF0，所以不按服务过滤，按名字认
        try { sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb) } catch (e: Exception) {
            return setPhase(Phase.OFF, "扫描不了：${e.message}")
        }
        log("开始扫描云台")
        val t0 = System.currentTimeMillis()
        // 扫描时每 2 秒再看一眼系统连接：在系统设置里连上云台后它就不广播了
        val tick = object : Runnable {
            override fun run() {
                if (phase != Phase.SCANNING) return
                systemConnected()?.let { d -> stopScan(); log("在系统连接里找到云台：${d.name ?: "云台"}"); attach(d, false); return }
                if (System.currentTimeMillis() - t0 >= 30_000) {
                    stopScan()
                    setPhase(Phase.OFF, "30 秒没找到云台：确认云台开着、没被别的手机（Mimo）连着。运行记录里有找到了哪些设备")
                    systemConnected(logAll = true)
                    return
                }
                main.postDelayed(this, 2000)
            }
        }
        scanTimer = tick
        main.postDelayed(tick, 2000)
    }

    private fun stopScan() {
        scanTimer?.let { main.removeCallbacks(it) }; scanTimer = null
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (e: Exception) {}
    }

    /// 连一台设备。10 秒没连好（包括找服务、找特征）就放弃：fallbackToScan = true 就改成扫描，否则报连不上
    private fun attach(d: BluetoothDevice, fallbackToScan: Boolean) {
        gatt?.let { try { it.close() } catch (e: Exception) {} }
        name = d.name ?: "云台"
        setPhase(Phase.CONNECTING, "")
        parser = Duml.Parser()
        writes.clear(); writing = false; chunk = 20
        val g = d.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        connectTimeout?.let { main.removeCallbacks(it) }
        val t = Runnable {
            if (phase != Phase.CONNECTING || gatt !== g) return@Runnable
            drop(g)
            if (fallbackToScan) { log("连不上上次那台云台，改成扫描"); scan() }
            else { setPhase(Phase.OFF, "10 秒没连上云台：把云台关机再开，确认没被 Mimo 或别的手机连着"); log("云台连接超时") }
        }
        connectTimeout = t
        main.postDelayed(t, 10_000)
    }

    private fun drop(g: BluetoothGatt) {
        if (gatt === g) { gatt = null; writer = null }
        try { g.disconnect(); g.close() } catch (e: Exception) {}
    }

    /// 连上了却不是 OM 云台：记下来，换一台接着找
    private fun notThisOne(g: BluetoothGatt, why: String) {
        log("${g.device.name ?: "这台设备"}不是 OM 云台（$why），接着找")
        notGimbal += g.device.address
        if (prefs.getString("address", null) == g.device.address) prefs.edit().remove("address").apply()
        drop(g)
        scan()
    }

    private fun lost(g: BluetoothGatt, why: String) {
        if (gatt !== g) return
        connectTimeout?.let { main.removeCallbacks(it) }; connectTimeout = null
        writer = null
        busy = false
        ticking = false
        try { g.close() } catch (e: Exception) {}
        setPhase(Phase.OFF, why)
        onPose?.invoke(0.0, 0.0, false)
        log("云台断开：$why")
        // 想连着的话 3 秒后重连（云台关机、走远了都会断）
        if (wantConnected) {
            val d = g.device
            main.postDelayed({ if (wantConnected && phase == Phase.OFF) attach(d, fallbackToScan = true) }, 3000)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (gatt !== g) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // 大一点的包：一帧 21 字节，默认一包只能 20 字节
                    if (!g.requestMtu(100)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    lost(g, if (status == 0) "云台关了或走远了" else "连接断了（错误 $status）")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                if (gatt !== g) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) chunk = max(20, mtu - 3)
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (gatt !== g) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { lost(g, "读不到云台的服务（错误 $status）"); return@post }
                val s = g.getService(SERVICE) ?: return@post notThisOne(g, "没有 FFF0 服务")
                val w = s.getCharacteristic(WRITE_CHAR) ?: return@post notThisOne(g, "没有 FFF5 写特征")
                writer = w
                s.getCharacteristic(NOTIFY_CHAR)?.let { n ->
                    g.setCharacteristicNotification(n, true)
                    n.getDescriptor(CCCD)?.let { desc ->
                        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        else @Suppress("DEPRECATION") { desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(desc) }
                        writing = true   // 等订阅写完再写别的（同一时间只能有一个写操作）
                    }
                } ?: log("找不到云台的推送特征（收不到姿态，转到指定角度会不准）")
                // 确认是 OM 云台了才记住，下次直接连
                connectTimeout?.let { main.removeCallbacks(it) }; connectTimeout = null
                prefs.edit().putString("address", g.device.address).apply()
                setPhase(Phase.READY, "")
                log("云台已连接：$name")
                // 问一次版本（全项目最安全的只读帧），顺便认型号
                send(0x00, 0x01)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) log("订阅云台推送失败（错误 $status）：收不到姿态，转到指定角度会不准")
                writing = false
                pump()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post { writing = false; pump() }
        }

        @Deprecated("Android 13 以下走这个")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = c.value ?: return
            main.post { received(g, v) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { received(g, value) }
        }
    }

    private fun received(g: BluetoothGatt, v: ByteArray) {
        if (gatt !== g) return
        for (f in parser.push(v)) handle(f)
    }

    // ───────── 发帧（安卓同一时间只能有一个写操作，排队一个一个写） ─────────

    private fun send(cmdSet: Int, cmdId: Int, payload: List<Int> = emptyList(), flags: Int = 0x40): Boolean {
        if (gatt == null || writer == null) return false
        // 速度帧是周期性的，排不上就丢这一帧，下一帧接上
        if (cmdSet == 0x04 && cmdId == 0x0c && writes.size > 4 && payload != Duml.STOP) return false
        seq = (seq + 1) and 0xffff
        val bytes = Duml.bytes(Duml.frame(seq, flags, cmdSet, cmdId, payload))
        var i = 0
        while (i < bytes.size) { writes.addLast(bytes.copyOfRange(i, min(i + chunk, bytes.size))); i += chunk }
        pump()
        return true
    }

    private fun pump() {
        if (writing) return
        val g = gatt ?: return
        val w = writer ?: return
        val data = writes.removeFirstOrNull() ?: return
        val noResp = w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(w, data, type) == BluetoothGatt.GATT_SUCCESS
        else @Suppress("DEPRECATION") run { w.writeType = type; w.value = data; g.writeCharacteristic(w) }
        if (ok) writing = true
        else main.postDelayed({ pump() }, 20)   // 蓝牙忙，等一下再写这包后面的
    }

    private fun sendSpeed(yaw: Int, pitch: Int) {
        val y = (yaw * (dirSign[Axis.YAW] ?: 1.0)).toInt()
        val p = (pitch * (dirSign[Axis.PITCH] ?: 1.0)).toInt()
        send(0x04, 0x0c, Duml.speed(y, p))
    }

    /// 马上停（零速），也停掉定时转
    fun stopNow() {
        aborted = true
        gen++
        ticking = false
        driveYaw = 0; drivePitch = 0
        writes.removeAll { true }
        send(0x04, 0x0c, Duml.STOP)
    }

    // ───────── 收帧 ─────────

    private fun handle(f: Duml.Frame) {
        when {
            f.cmdSet == 0x04 && f.cmdId == 0x05 -> {
                val a = Duml.attitude(f.payload) ?: return
                attitude = a
                attSeq++
                lastAttAt = System.currentTimeMillis()
                if (home == null) { home = a.yaw to a.pitch; log("记下正面：偏航 ${fmt(a.yaw)}，俯仰 ${fmt(a.pitch)}") }
                pose?.let { onPose?.invoke(it.first, it.second * upSign, true) }
                changed()
            }
            f.cmdSet == 0x05 && f.cmdId == 0x06 -> {
                // 电池：[0] 电量 %，最后一个字节 1 = 在充电
                battery = f.payload.firstOrNull() ?: return
                charging = f.payload.lastOrNull() == 1
                changed()
            }
            f.cmdSet == 0x04 && f.cmdId == 0x27 && f.payload.size >= 5 -> { asleep = f.payload[4] and 1 != 0; changed() }
            f.cmdSet == 0x00 && f.cmdId == 0x01 && f.payload.size >= 6 -> {
                val ascii = f.payload.map { if (it in 32..126) it.toChar() else '.' }.joinToString("")
                model = if (ascii.contains("GB66")) "OM6" else if (f.payload.size >= 18) String(f.payload.subList(2, 18).filter { it != 0 }.map { it.toByte() }.toByteArray()) else model
                log("云台型号：${model.ifEmpty { "未知" }}")
                changed()
            }
        }
    }

    // ───────── 动作 ─────────

    /// 按速度转一会儿（°/s，正负见 dirSign），到时间自己停
    fun move(yawSpeed: Double, pitchSpeed: Double, seconds: Double) {
        if (!connected) return
        begin()
        driveYaw = clampUnits(yawSpeed / DEG_PER_UNIT)
        drivePitch = clampUnits(pitchSpeed / DEG_PER_UNIT)
        driveUntil = System.currentTimeMillis() + (seconds.coerceIn(0.1, 5.0) * 1000).toLong()
        val my = gen
        ticking = true
        val tick = object : Runnable {
            override fun run() {
                if (gen != my || !ticking) return
                if (aborted || System.currentTimeMillis() >= driveUntil || (driveYaw == 0 && drivePitch == 0)) {
                    ticking = false
                    driveYaw = 0; drivePitch = 0
                    send(0x04, 0x0c, Duml.STOP)
                    return
                }
                sendSpeed(driveYaw, drivePitch)
                main.postDelayed(this, 100)
            }
        }
        tick.run()
    }

    private fun begin(): Int {
        gen++
        aborted = false
        busy = false
        ticking = false
        driveYaw = 0; drivePitch = 0
        return gen
    }

    private fun clampUnits(u: Double): Int {
        val v = u.roundToInt()
        return if (v == 0) 0 else (if (v > 0) 1 else -1) * min(MAX_UNITS, abs(v))
    }

    /// 闭环转动：相对现在转 delta 度。返回 (实际转了多少, 结果)：done 到了 / stall 转到一半不动 / noexec 一点没动 / timeout / abort / nofeedback
    suspend fun turn(axis: Axis, delta: Double): Pair<Double, String> {
        val start = attitude
        if (!connected || start == null) return 0.0 to "nofeedback"
        if (abs(delta) < TOLERANCE) return 0.0 to "done"
        val my = begin()
        busy = true; changed()
        try {
            fun value(a: Duml.Attitude) = if (axis == Axis.YAW) a.yaw else a.pitch
            var prev = value(start); var seen = attSeq
            var traveled = 0.0; var est = 0.0; var units = 0
            var hits = 0; var frozen = 0; var flipped = false
            var last = System.currentTimeMillis()
            val t0 = last
            while (true) {
                val now = System.currentTimeMillis()
                est += units * DEG_PER_UNIT * (now - last) / 1000.0   // 两帧姿态之间靠推算
                last = now
                val a = attitude
                if (attSeq != seen && a != null) {   // 来了新的实测值
                    seen = attSeq
                    val step = Duml.wrap(value(a) - prev)
                    prev = value(a)
                    // 方向反了（发正速度角度却在变小）：记下来，以后都反着发
                    if (!flipped && units != 0 && abs(step) >= 1.5 && (step > 0) != (units > 0)) {
                        flipped = true
                        val s = -(dirSign[axis] ?: 1.0)
                        dirSign[axis] = s
                        prefs.edit().putFloat("dir.$axis", s.toFloat()).apply()
                        log("云台${if (axis == Axis.YAW) "左右" else "上下"}方向和预想的相反，已改过来")
                    }
                    if (units != 0 && abs(step) >= 1.5 && axis !in dirKnown) {
                        dirKnown += axis
                        prefs.edit().putBoolean("dirKnown.$axis", true).apply()
                    }
                    traveled += step
                    est = traveled
                    val e = delta - traveled
                    hits = if (abs(e) < TOLERANCE) hits + 1 else 0                // 到位只认实测
                    frozen = if (units != 0 && abs(step) < 0.3) frozen + 1 else 0   // 还在发速度却连着两帧不动
                    if (hits >= 2) return traveled to "done"
                    if (frozen >= 2) return traveled to (if (abs(traveled) < 1) "noexec" else "stall")
                }
                if (aborted || gen != my || !connected) return traveled to "abort"
                if (now - t0 > TIMEOUT_MS) return traveled to "timeout"
                if (now - lastAttAt > 5000) return traveled to "nofeedback"
                val err = delta - est
                units = if (abs(err) < TOLERANCE) 0 else {
                    val want = (err / LEAD / DEG_PER_UNIT).roundToInt()   // 比例控制，大约 0.8 秒收敛
                    (if (want > 0) 1 else -1) * min(MAX_UNITS, max(MIN_UNITS, abs(want)))
                }
                if (axis == Axis.YAW) sendSpeed(units, 0) else sendSpeed(0, units)
                delay(100)
            }
        } finally {
            // 被新的转动顶掉时不要去停（那样会把新的也停了）
            if (gen == my) { busy = false; stopNow(); changed() }
        }
    }

    // ───────── 给大模型、聊天框、自动跟随用的转动（右、上为正，相对正面） ─────────

    /// relative：true = 在现在的基础上再转；false = 转到相对正面的这个方向。null 的那个轴不动
    suspend fun aim(right: Double?, up: Double?, relative: Boolean, who: Owner): String {
        if (!connected) return "云台没连上，转不了（请主人在设置页点「连接云台」）。"
        if (asleep) return "云台在睡眠，转不了（请主人按一下云台上的按键）。"
        val now = heading
        if (now == null || pose == null) return "还没收到云台的朝向，等一两秒再试。"
        if (busy && owner > who) return "${owner.label}正在转云台，这次没转。" + stateText()
        owner = who
        tracking = false
        if (who > Owner.FOLLOW) heldUntil = System.currentTimeMillis() + ((FOLLOW_HOLD + 4) * 1000).toLong()
        try {
            var tr = right?.let { if (relative) now.first + it else it } ?: now.first
            var tu = up?.let { if (relative) now.second + it else it } ?: now.second
            val edge = mutableListOf<String>()
            if (tr > LIMIT_YAW) { tr = LIMIT_YAW; edge += "右边" }
            if (tr < -LIMIT_YAW) { tr = -LIMIT_YAW; edge += "左边" }
            if (tu > LIMIT_UP) { tu = LIMIT_UP; edge += "上面" }
            if (tu < -LIMIT_DOWN) { tu = -LIMIT_DOWN; edge += "下面" }
            val dy = tr - now.first
            val dp = (tu - now.second) * upSign   // 换成姿态里的角度差
            val targetPitch = tu * upSign
            val moveY = abs(dy) >= TOLERANCE; val moveP = abs(dp) >= TOLERANCE
            val limited = if (edge.isEmpty()) "" else "${edge.joinToString("、")}到头了，只转到这里。"
            if (!moveY && !moveP) return (if (edge.isEmpty()) "已经朝着那个方向了。" else limited) + stateText()

            val notes = mutableListOf<String>()
            val small = abs(dy) <= FAST_BELOW && abs(dp) <= FAST_BELOW
            val known = (!moveY || Axis.YAW in dirKnown) && (!moveP || Axis.PITCH in dirKnown)
            if (small && known) {
                val secs = max(0.1, max(abs(dy), abs(dp)) / FAST_SPEED)
                move(if (moveY) dy / secs else 0.0, if (moveP) dp / secs else 0.0, secs)
                val token = gen
                aimed = Triple(tr, targetPitch, System.currentTimeMillis())
                delay(((secs + 0.15) * 1000).toLong())
                if (gen != token) return "转到一半被打断了。" + stateText()
                aimed = Triple(tr, targetPitch, System.currentTimeMillis())
                scope.launch { settle(token, tr, targetPitch) }
            } else {
                aimed = null
                for ((ax, delta) in listOf(Axis.YAW to dy, Axis.PITCH to dp)) {
                    if (abs(delta) < TOLERANCE) continue
                    val r = turn(ax, delta)
                    if (r.second != "done") notes += "${if (ax == Axis.YAW) "左右" else "上下"}${verdictText(r.second)}。"
                    if (r.second == "abort") break
                }
                if (notes.isEmpty()) aimed = Triple(tr, targetPitch, System.currentTimeMillis())
            }
            return (if (notes.isEmpty()) "转好了。" else notes.joinToString("")) + limited + stateText()
        } finally {
            if (who > Owner.FOLLOW) heldUntil = System.currentTimeMillis() + (FOLLOW_HOLD * 1000).toLong()
        }
    }

    /// 快转之后在后台按姿态把偏差补掉（中间有新的转动就不补了）
    private suspend fun settle(token: Int, yaw: Double, pitch: Double) {
        delay(1300)   // 等停稳以后的一帧姿态
        val p = pose
        if (gen != token || !connected || p == null) return
        for ((ax, e) in listOf(Axis.YAW to Duml.wrap(yaw - p.first), Axis.PITCH to pitch - p.second)) {
            if (abs(e) < TOLERANCE || abs(e) >= 25) continue
            if (turn(ax, e).second == "abort") return
        }
    }

    /// 停下，并让出控制权
    fun halt() {
        stopNow()
        owner = Owner.NOBODY
        aimed = null
        tracking = false
        heldUntil = System.currentTimeMillis() + ((FOLLOW_HOLD + 4) * 1000).toLong()   // 主人说了停：自动跟随也先别动
    }

    /// 自动跟随用：按速度转一小会儿（°/s，右、上为正），要一直叫才会一直转。
    /// 别人正在转、或者刚转完没几秒，返回 null（让开）；否则返回真正发出去的速度（到限位的那个方向是 0）
    fun track(right: Double, up: Double): Pair<Double, Double>? {
        if (!connected || asleep || busy || System.currentTimeMillis() < heldUntil) { tracking = false; return null }
        var r = right; var u = up
        heading?.let { h ->
            if ((h.first >= LIMIT_YAW && r > 0) || (h.first <= -LIMIT_YAW && r < 0)) r = 0.0
            if ((h.second >= LIMIT_UP && u > 0) || (h.second <= -LIMIT_DOWN && u < 0)) u = 0.0
        }
        if (r == 0.0 && u == 0.0) {
            if (tracking) { stopNow(); tracking = false }
            return 0.0 to 0.0
        }
        owner = Owner.FOLLOW
        aimed = null
        tracking = true
        move(r, u * upSign, 0.45)
        return r to u
    }

    /// 把现在的朝向记成正面
    fun setHomeHere() {
        val a = attitude ?: return
        home = a.yaw to a.pitch
        aimed = null
        onPose?.invoke(0.0, 0.0, connected)
        changed()
    }

    /// 主人手动转一下（设置页的箭头）：°/s，右、上为正
    fun manualMove(right: Double, up: Double, seconds: Double) {
        owner = Owner.MANUAL
        aimed = null
        tracking = false
        heldUntil = System.currentTimeMillis() + (FOLLOW_HOLD * 1000).toLong()
        move(right, up * upSign, seconds)
    }

    /// 一句话说清云台现在的状态，给大模型看：朝哪、还能转多少、电量
    fun stateText(): String {
        if (!connected) return "云台没连上。"
        val h = heading ?: return "云台已连上，还没收到朝向。"
        fun side(v: Double, pos: String, neg: String, zero: String) = if (abs(v) < 1.5) zero else "${if (v > 0) pos else neg} ${abs(v).roundToInt()}°"
        fun room(v: Double) = max(0, v.roundToInt())
        var s = "现在${side(h.first, "朝右", "朝左", "左右在正面")}、${side(h.second, "抬头", "低头", "上下是平的")}（相对正面）。"
        s += "还能往右 ${room(LIMIT_YAW - h.first)}°、往左 ${room(LIMIT_YAW + h.first)}°、往上 ${room(LIMIT_UP - h.second)}°、往下 ${room(LIMIT_DOWN + h.second)}°。"
        battery?.let { s += "云台电量 $it%${if (it <= 10 && !charging) "，快没电了" else ""}。" }
        if (asleep) s += "云台在睡眠。"
        return s
    }

    // ───────── 遥控指令 {"do":"gimbal","action":…} ─────────

    /// 动作：status / connect / disconnect / turn(yaw,pitch 相对度数) / look(yaw,pitch 相对归位点) / move(yaw,pitch 度每秒, ms) / stop / center / home。
    /// 遥控指令里的角度用姿态里的正负（和 iPhone 版、遥控.py 一样）
    suspend fun command(cmd: JSONObject): String {
        val action = cmd.optString("action", "status")
        fun num(k: String): Double? = if (cmd.has(k) && !cmd.isNull(k)) cmd.optDouble(k).takeIf { !it.isNaN() } else null
        when (action) {
            "connect" -> {
                connect()
                repeat(200) {
                    if (connected || phase == Phase.OFF || phase == Phase.NO_BLUETOOTH || phase == Phase.DENIED || phase == Phase.UNSUPPORTED) return@repeat
                    delay(100)
                }
                return if (connected) "云台已连接。\n" + status() else "还没连上云台：${phase.label}。$note"
            }
            "disconnect" -> { disconnect(); return "已断开云台" }
            "status" -> return status()
        }
        if (!connected) return "云台没连上（${phase.label}）。先发 {\"do\":\"gimbal\",\"action\":\"connect\"}，或者在 Vania 的设置里点「连接云台」。"
        if (busy && action != "stop") {
            if (owner > Owner.REMOTE) return "主人正在手动转云台，先发 stop"
            if (owner == Owner.REMOTE) return "云台正在转，先发 stop"
            stopNow()   // 大模型或自动跟随在转：遥控端优先，直接顶掉
        }
        owner = Owner.REMOTE; aimed = null
        tracking = false
        heldUntil = System.currentTimeMillis() + ((FOLLOW_HOLD + 15) * 1000).toLong()   // 闭环最长 15 秒
        try {
            return when (action) {
                "stop" -> { stopNow(); "已停。\n" + status() }
                "home" -> { setHomeHere(); "已把现在的朝向记成归位点" }
                "move" -> {
                    val secs = ((num("ms") ?: 500.0) / 1000).coerceIn(0.1, 5.0)
                    move(num("yaw") ?: 0.0, num("pitch") ?: 0.0, secs)
                    delay(((secs + 1.2) * 1000).toLong())
                    "转了 ${"%.1f".format(secs)} 秒。\n" + status()
                }
                "turn", "look", "center" -> {
                    val (dy, dp) = if (action == "turn") (num("yaw") ?: 0.0) to (num("pitch") ?: 0.0) else {
                        val p = pose ?: return "还没收到云台姿态，等一两秒再试"
                        val ty = if (action == "center") 0.0 else num("yaw") ?: p.first
                        val tp = if (action == "center") 0.0 else num("pitch") ?: p.second
                        Duml.wrap(ty - p.first) to (tp - p.second)
                    }
                    val lines = mutableListOf<String>()
                    for ((ax, delta) in listOf(Axis.YAW to dy, Axis.PITCH to dp)) {
                        if (abs(delta) < TOLERANCE) continue
                        val r = turn(ax, delta)
                        lines += "${if (ax == Axis.YAW) "左右" else "上下"} 目标 ${fmt(delta)}，实际 ${fmt(r.first)}：${verdictText(r.second)}"
                        if (r.second == "abort") break
                    }
                    (if (lines.isEmpty()) "已经在那个方向了" else lines.joinToString("\n")) + "\n" + status()
                }
                else -> "不认识的云台动作：$action（可以用 status、connect、disconnect、turn、look、move、stop、center、home）"
            }
        } finally {
            heldUntil = System.currentTimeMillis() + (FOLLOW_HOLD * 1000).toLong()
        }
    }

    fun status(): String {
        var s = "云台：${name.ifEmpty { "大疆 OM" }}${if (model.isEmpty()) "" else "（$model）"}，${phase.label}"
        battery?.let { s += "，电量 $it%${if (charging) "（在充电）" else ""}" }
        if (asleep) s += "，在睡眠"
        pose?.let { s += "\n朝向（相对归位点）：左右 ${fmt(it.first)}，上下 ${fmt(it.second)}" }
        attitude?.elevation?.let { s += "\n镜头仰角：${fmt(it)}" }
        if (note.isNotEmpty() && !connected) s += "\n$note"
        return s
    }

    fun statusJSON(): JSONObject {
        val o = JSONObject().put("connected", connected).put("phase", phase.label).put("name", name).put("model", model).put("busy", busy)
        battery?.let { o.put("battery", it).put("charging", charging) }
        pose?.let { o.put("yaw", Math.round(it.first * 10) / 10.0).put("pitch", Math.round(it.second * 10) / 10.0) }
        attitude?.elevation?.let { o.put("elevation", Math.round(it * 10) / 10.0) }
        return o
    }

    private fun verdictText(v: String) = when (v) {
        "done" -> "到了"
        "stall" -> "转到一半不动了（可能到头了或被挡住）"
        "noexec" -> "云台一点没动（可能在睡眠，或者被 Mimo 占着）"
        "timeout" -> "15 秒没转到"
        "nofeedback" -> "收不到云台的姿态"
        else -> "停了"
    }

    private fun fmt(v: Double) = "%+.0f°".format(v)

    companion object {
        val SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val NOTIFY_CHAR: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        val WRITE_CHAR: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val NAME_PREFIXES = listOf("OM", "Osmo", "DJI")

        /// 速度标度：1 单位 ≈ 0.11 °/s（OM6 实测）
        const val DEG_PER_UNIT = 0.11
        private const val TOLERANCE = 2.0
        private const val LEAD = 0.8
        private const val MIN_UNITS = 40
        private const val MAX_UNITS = 900
        private const val TIMEOUT_MS = 15_000L

        /// 软限位（度，相对正面）：左右各多少、最多抬头多少、最多低头多少
        const val LIMIT_YAW = 120.0
        const val LIMIT_UP = 35.0
        const val LIMIT_DOWN = 20.0
        /// 小于这个角度先按速度直接转（快），转完再按姿态补偏差
        const val FAST_BELOW = 30.0
        const val FAST_SPEED = 60.0
        /// 别人转完以后，自动跟随过几秒再接着跟
        const val FOLLOW_HOLD = 6.0
    }
}
