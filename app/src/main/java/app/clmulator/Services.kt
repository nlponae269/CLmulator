package app.clmulator

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Держит процесс живым, пока работает виртуальная машина. */
class VmService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val listener: () -> Unit = { update() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            QemuManager.stop()
        }
        startFg()
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clmulator:vm").apply { acquire() }
        }
        QemuManager.addListener(listener)
        update()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val m = QemuManager.machine
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, EmulatorActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, App.CH_VM)
            .setSmallIcon(R.drawable.ic_stat_vm)
            .setContentTitle(m?.name ?: "CLmulator")
            .setContentText(QemuManager.stateText())
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Выключить", stop).build())
            .build()
    }

    private fun startFg() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(1, notification())
    }

    private fun update() {
        if (!QemuManager.isRunning) {
            stopSelf()
            return
        }
        getSystemService(android.app.NotificationManager::class.java).notify(1, notification())
    }

    override fun onDestroy() {
        QemuManager.removeListener(listener)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
    }
}

/** Держит процесс живым, пока идут загрузки и импорт. */
class TaskService : Service() {
    private val listener: () -> Unit = { update() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(2, notification())
        Tasks.addListener(listener)
        update()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val t = Tasks.active().firstOrNull { !it.done && it.error == null }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, App.CH_TASK)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(t?.subtitle ?: "CLmulator")
            .setContentText(t?.status ?: "")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (t != null) {
            if (t.progress >= 0) b.setProgress(1000, (t.progress * 1000).toInt(), false) else b.setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun update() {
        if (Tasks.active().none { !it.done && it.error == null }) {
            stopSelf()
            return
        }
        getSystemService(android.app.NotificationManager::class.java).notify(2, notification())
    }

    override fun onDestroy() {
        Tasks.removeListener(listener)
        super.onDestroy()
    }
}
