package app.clmulator

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream

/** Клиент QMP (QEMU Machine Protocol) через unix-сокет. Все вызовы — из фонового потока. */
class QmpClient(private val path: String) {
    private var sock: LocalSocket? = null
    private var reader: BufferedReader? = null
    private var out: OutputStream? = null

    @Synchronized
    fun connect(timeoutMs: Long = 20000): Boolean {
        if (sock != null) return true
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            try {
                val s = LocalSocket()
                s.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                s.soTimeout = 5000
                sock = s
                reader = BufferedReader(InputStreamReader(s.inputStream))
                out = s.outputStream
                reader!!.readLine() // greeting
                exec("qmp_capabilities")
                return true
            } catch (e: IOException) {
                close()
                Thread.sleep(250)
            }
        }
        return false
    }

    @Synchronized
    fun exec(cmd: String, args: JSONObject? = null): JSONObject? {
        val o = out ?: return null
        val req = JSONObject().put("execute", cmd)
        if (args != null) req.put("arguments", args)
        return try {
            o.write((req.toString() + "\n").toByteArray())
            o.flush()
            while (true) {
                val line = reader!!.readLine() ?: throw IOException("QMP закрыт")
                val r = JSONObject(line)
                if (r.has("event")) continue
                return r
            }
            @Suppress("UNREACHABLE_CODE") null
        } catch (e: Exception) {
            close()
            null
        }
    }

    fun sendKeys(vararg qcodes: String, holdMs: Int = 80) {
        val keys = JSONArray()
        qcodes.forEach { keys.put(JSONObject().put("type", "qcode").put("data", it)) }
        exec("send-key", JSONObject().put("keys", keys).put("hold-time", holdMs))
    }

    fun inputEvents(events: JSONArray) {
        exec("input-send-event", JSONObject().put("events", events))
    }

    fun status(): String? = exec("query-status")?.optJSONObject("return")?.optString("status")

    @Synchronized
    fun close() {
        runCatching { sock?.close() }
        sock = null; reader = null; out = null
    }

    companion object {
        fun rel(dx: Int, dy: Int): JSONArray = JSONArray()
            .put(JSONObject().put("type", "rel").put("data", JSONObject().put("axis", "x").put("value", dx)))
            .put(JSONObject().put("type", "rel").put("data", JSONObject().put("axis", "y").put("value", dy)))

        fun btn(button: String, down: Boolean): JSONArray = JSONArray()
            .put(JSONObject().put("type", "btn").put("data", JSONObject().put("down", down).put("button", button)))
    }
}
