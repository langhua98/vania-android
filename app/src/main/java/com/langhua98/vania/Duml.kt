package com.langhua98.vania

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/// 大疆 DUML v1 帧（OM 手机云台内部用的协议）。对应 iPhone 版的 Duml.swift，那边又是照着 langhua98/Linggo 的 ble.html
/// （在 OM6 上实测过）逐字节翻译的，别自己改：CRC 初值、字节顺序错一点，云台就不认。DumlTest 用同一组标准答案核对。
///
///     55 | 长度+版本(2,LE：低 10 位 = 总长，高 6 位 = 版本 1) | CRC8(前 3 字节) | src | dst | seq(2,LE) | flags | cmdSet | cmdId | 载荷 | CRC16(2,LE)
///
/// 字节都用 0～255 的 Int 存（Kotlin 的 Byte 是有符号的，容易算错）。不碰安卓的东西。
object Duml {
    // ───────── CRC（查表法：CRC8 多项式 0x8C 初值 0x77，CRC16 多项式 0x8408 初值 0x3692） ─────────

    private val t8 = IntArray(256) { i -> var c = i; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8C else c ushr 1 }; c }
    private val t16 = IntArray(256) { i -> var c = i; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8408 else c ushr 1 }; c }

    fun crc8(bytes: List<Int>): Int {
        var c = 0x77
        for (x in bytes) c = t8[(x xor c) and 0xff]
        return c
    }

    fun crc16(bytes: List<Int>): Int {
        var c = 0x3692
        for (x in bytes) c = (c ushr 8) xor t16[(x xor c) and 0xff]
        return c and 0xffff
    }

    // ───────── 发 ─────────

    /// 组一帧。src 0x02 = 手机 App，dst 0x04 = 云台；flags 0x40 = 要应答
    fun frame(seq: Int, flags: Int = 0, cmdSet: Int, cmdId: Int, payload: List<Int> = emptyList(), src: Int = 0x02, dst: Int = 0x04): List<Int> {
        val total = 13 + payload.size
        val lv = (1 shl 10) or total
        val head = listOf(0x55, lv and 0xff, (lv ushr 8) and 0xff)
        val b = head.toMutableList()
        b += listOf(crc8(head), src, dst, seq and 0xff, (seq ushr 8) and 0xff, flags, cmdSet, cmdId)
        b += payload
        val c = crc16(b)
        b += listOf(c and 0xff, c ushr 8)
        return b
    }

    fun bytes(frame: List<Int>) = ByteArray(frame.size) { frame[it].toByte() }

    /// 有符号 16 位，小端
    fun i16(v: Int): List<Int> {
        val u = v.coerceIn(-32768, 32767) and 0xffff
        return listOf(u and 0xff, u ushr 8)
    }

    /// 速度指令 04/0c 的载荷：偏航、横滚、俯仰（各 2 字节）、模式 0x80（速度）、time。
    /// 单位不是度：OM6 实测 1 单位 ≈ 0.11 °/s。要每 0.1 秒发一帧，停发云台自己停
    fun speed(yaw: Int, pitch: Int, roll: Int = 0, time: Int = 15) = i16(yaw) + i16(roll) + i16(pitch) + listOf(0x80, time)

    /// 停：零速
    val STOP = listOf(0, 0, 0, 0, 0, 0, 0x80, 1)

    // ───────── 收 ─────────

    class Frame(val src: Int, val dst: Int, val seq: Int, val flags: Int, val cmdSet: Int, val cmdId: Int, val payload: List<Int>) {
        /// flags 最高位 = 这是应答（不是云台自己推的）
        val isAck get() = flags and 0x80 != 0
    }

    /// 蓝牙通知一包最多二十来个字节：一帧可能分好几包来，也可能几帧粘在一起。攒着拆。只验 CRC8（帧头），不验 CRC16
    class Parser {
        private val buf = ArrayList<Int>()

        fun push(data: ByteArray) = push(data.map { it.toInt() and 0xff })

        fun push(data: List<Int>): List<Frame> {
            buf += data
            val out = mutableListOf<Frame>()
            while (buf.size >= 4) {
                val i = buf.indexOf(0x55)
                if (i < 0) { buf.clear(); break }
                if (i > 0) buf.subList(0, i).clear()
                if (buf.size < 4) break
                val len = (buf[1] or (buf[2] shl 8)) and 0x3ff
                if (len < 13 || len > 1024 || crc8(buf.subList(0, 3)) != buf[3]) { buf.removeAt(0); continue }
                if (buf.size < len) break   // 半帧，等下一包
                val f = ArrayList(buf.subList(0, len))
                buf.subList(0, len).clear()
                out += Frame(f[4], f[5], f[6] or (f[7] shl 8), f[8], f[9], f[10], f.subList(11, len - 2))
            }
            return out
        }
    }

    // ───────── 姿态 04/05（云台大约每秒推一帧） ─────────

    /// 欧拉角（度）。OM6 是「+Y 朝下」装的，所以静止时 roll ≈ −89，不是歪了。gravity：机体系里的重力方向
    class Attitude(val yaw: Double, val pitch: Double, val roll: Double, val gravity: List<Double>) {
        /// 仰角（度，抬头为正）：重力落在 +Y 轴附近时才测得出来，不然是 null
        val elevation: Double?
            get() = if (gravity.size == 3 && Math.abs(gravity[1]) >= 0.8) Math.toDegrees(asin(gravity[0].coerceIn(-1.0, 1.0))) else null
    }

    /// 载荷 [24:40] = 四元数 w,x,y,z（float32，小端）
    fun attitude(pl: List<Int>): Attitude? {
        if (pl.size < 40) return null
        fun f(o: Int) = java.lang.Float.intBitsToFloat(pl[o] or (pl[o + 1] shl 8) or (pl[o + 2] shl 16) or (pl[o + 3] shl 24)).toDouble()
        val w = f(24); val x = f(28); val y = f(32); val z = f(36)
        val n = sqrt(w * w + x * x + y * y + z * z)
        if (n <= 0.9 || n >= 1.1) return null
        val roll = Math.toDegrees(atan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y)))
        val pitch = Math.toDegrees(asin((2 * (w * y - z * x)).coerceIn(-1.0, 1.0)))
        val yaw = Math.toDegrees(atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z)))
        // 世界系的「下」(0,0,-1) 转回机体系 = 旋转矩阵第三行取反
        val g = listOf(-(2 * (x * z - w * y)), -(2 * (y * z + w * x)), -(1 - 2 * (x * x + y * y)))
        return Attitude(yaw, pitch, roll, g)
    }

    /// 角度差折到 -180…180
    fun wrap(d: Double): Double {
        var v = d % 360
        if (v >= 180) v -= 360
        if (v < -180) v += 360
        return v
    }
}
