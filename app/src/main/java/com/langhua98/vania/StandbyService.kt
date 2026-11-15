package com.langhua98.vania

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/// 待命的前台服务：通知栏一直显示「Vania 待命中」，被远程使用时看得出来；系统也不会随手把待命循环杀掉。
/// 待命循环本身在 RemoteEngine 里，这个服务只负责「挂着」。
class StandbyService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as VaniaApp
        val n = notification(this, app.store.device)
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
        if (wake == null) {
            // 屏幕关了也让 CPU 醒着，长轮询才不会断（屏幕本来就常亮的话这条几乎不费电）
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vania:standby").apply { setReferenceCounted(false); acquire() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "standby"

        fun notification(ctx: Context, device: String): Notification {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "待命", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Vania 在等另一台设备发来的指令"
            })
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("Vania 待命中")
                .setContentText("设备名：$device。另一台设备可以通过小橘云盘发指令过来")
                .setOngoing(true)
                .setContentIntent(open)
                .build()
        }
    }
}
