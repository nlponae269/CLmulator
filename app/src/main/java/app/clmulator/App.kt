package app.clmulator

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // если приложение упадёт — сохраняем причину, чтобы показать её при следующем запуске
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, "crash.txt").writeText(
                    "CLmulator ${BuildConfig.VERSION_NAME} • Android ${android.os.Build.VERSION.RELEASE} • " +
                        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nПоток: ${t.name}\n\n$sw")
            }
            prev?.uncaughtException(t, e)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_VM, "Виртуальная машина", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_TASK, "Загрузки и импорт", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val CH_VM = "vm"
        const val CH_TASK = "tasks"
        lateinit var instance: App
        val main = Handler(Looper.getMainLooper())
        val prefs get() = instance.getSharedPreferences("clm", Context.MODE_PRIVATE)
    }
}

object Log {
    private const val TAG = "CLmulator"
    fun i(msg: String) { android.util.Log.i(TAG, msg) }
    fun w(msg: String, t: Throwable? = null) { android.util.Log.w(TAG, msg, t) }
    fun e(msg: String, t: Throwable? = null) { android.util.Log.e(TAG, msg, t) }
}
