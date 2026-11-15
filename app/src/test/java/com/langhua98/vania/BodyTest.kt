package com.langhua98.vania

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/// 和 iPhone 版对得上的那些规则：短指令、回答里的标签、换装换背景、断句、WAV 解析
class BodyTest {
    @Test fun quickCommands() {
        assertEquals("action", Body.quickCommand("跳舞")!!.msg.getString("type"))
        assertEquals("dance", Body.quickCommand("跳舞")!!.msg.getString("name"))
        assertEquals("face", Body.quickCommand("特写")!!.msg.getString("shot"))
        assertEquals("face", Body.quickCommand("/镜头 特写")!!.msg.getString("shot"))
        assertEquals(180.0, Body.quickCommand("背对")!!.msg.getDouble("yaw"), 0.0)
        assertEquals(-45.0, Body.quickCommand("转身 右 45")!!.msg.getDouble("yaw"), 0.0)
        assertEquals("reset", Body.quickCommand("重置")!!.msg.getString("type"))
        assertEquals("stop", Body.quickCommand("停。")!!.msg.getString("action"))
        assertEquals("follow_off", Body.quickCommand("别跟了")!!.msg.getString("action"))
        val g = Body.quickCommand("云台 看向 右 20 上 10")!!.msg
        assertEquals("look", g.getString("action")); assertEquals(20.0, g.getDouble("right"), 0.0); assertEquals(10.0, g.getDouble("up"), 0.0)
        // 菜单里每一项打出来都能执行（换装、换背景那几项交给对话处理，不在这里）
        for (group in Body.MENU) for (item in group.items) if (item.msg != null) assertNotNull(item.name, Body.quickCommand(item.name))
        assertNull(Body.quickCommand("今天天气怎么样"))
    }

    @Test fun tagsInReplies() {
        val r = "[happy]好呀。[动作 跳舞][镜头 全身][转身 左 90][换装 洋装][左转 30][看向 右 20 上 10][回正][看一眼 后面]"
        assertEquals(listOf("跳舞"), Body.actionTags(r))
        assertEquals(listOf("全身"), Body.viewTags(r))
        assertEquals(listOf(90.0), Body.turnTags(r))
        assertEquals(listOf("洋装"), Body.outfitTags(r))
        val moves = Body.gimbalMoves(r)
        assertEquals(listOf("turn", "look", "center"), moves.map { it.action })
        assertEquals(-30.0, moves[0].right!!, 0.0)
        assertEquals(false, Body.seeTag(r))
        assertEquals(true, Body.seeTag("[看一眼]"))
        assertEquals("[happy]好呀。", Body.strip(r))
        assertEquals("好呀。", Chat.clean(Body.strip(r)))
    }

    @Test fun outfitsAndBackgrounds() {
        assertEquals("outfit2", Store.outfitCommand("换上洋装"))
        assertEquals("default", Store.outfitCommand("换回原来的", "sporty"))
        assertEquals("outfit2", Store.outfitCommand("换件衣服", "default"))
        assertNull(Store.outfitCommand("你好"))
        assertEquals("gothic", Store.backgroundCommand("背景 哥特大厅"))
        assertEquals("room_night", Store.backgroundCommand("换到晚上的卧室"))
        assertEquals("room_day", Store.backgroundCommand("换个背景", "room_dawn"))
        assertNull(Store.backgroundCommand("你好"))
    }

    @Test fun sentences() {
        assertEquals(3, Talk.sentenceEnd("你好。后面"))
        assertNull(Talk.sentenceEnd("还没说完"))
        assertEquals(16, Talk.sentenceEnd("这是一个很长很长很长很长的句子，后面还有"))
    }

    @Test fun wav() {
        // 0.12 秒的 16 位单声道 WAV：前一半没声音，后一半很响
        val rate = 16000; val n = rate * 12 / 100
        val pcm = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) pcm.putShort(if (i < n / 2) 0 else (if (i % 2 == 0) 20000 else -20000).toShort())
        val data = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(n * 2).put(pcm.array())
        val w = WavPlayer.parse(data.array())!!
        assertEquals(rate, w.rate); assertEquals(1, w.channels); assertEquals(n * 2, w.pcm.size)
        val env = WavPlayer.envelope(w)
        assertEquals(2, env.size)
        assertEquals(0f, env[0], 0.001f)
        assertTrue(env[1] > 0.9f)
        assertNull(WavPlayer.parse(ByteArray(10)))
    }
}
