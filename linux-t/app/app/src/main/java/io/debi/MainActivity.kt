package io.debi

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.util.ArrayList

class MainActivity : Activity() {

    private lateinit var web: WebView

    @Volatile private var cols = 80
    @Volatile private var rows = 24

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.isScrollbarFadingEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = WebViewClient()
        setContentView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        web.loadUrl("file:///android_asset/web/index.html")

        try { TermProcessService.start(this) } catch (_: Throwable) { }
        // El wake lock / wifi lock solo mientras haya secciones activas (ahorro de batería).
        Term.onSessionCountChanged = { n -> TermProcessService.setWake(n > 0) }
        requestNeededPermissions()
        requestBatteryExemption()
    }

    /**
     * Pide exención de optimización de batería. Sin esto, muchos Android (Xiaomi,
     * Samsung, Huawei…) matan el proceso en segundo plano y las sesiones se apagan
     * al salir de la app.
     */
    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager ?: return
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val i = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                i.data = android.net.Uri.parse("package:$packageName")
                startActivity(i)
            }
        } catch (_: Throwable) { }
    }

    private fun requestNeededPermissions() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) {
            try { requestPermissions(need.toTypedArray(), 1) } catch (_: Throwable) { }
        }
    }

    /** Puente con el JS del terminal (pestañas incluidas). */
    inner class Bridge {
        @JavascriptInterface
        fun ready() {
            Thread {
                if (Term.sessionIds().isEmpty()) {
                    openAndShow()
                } else {
                    switchTo(Term.activeSessionId().let { if (it > 0) it else Term.sessionIds().first() })
                }
            }.start()
        }

        @JavascriptInterface
        fun newTab() {
            Thread { openAndShow() }.start()
        }

        @JavascriptInterface
        fun selectTab(id: Int) {
            Thread { switchTo(id) }.start()
        }

        @JavascriptInterface
        fun closeTab(id: Int) {
            Thread {
                val newActive = Term.closeSession(id)
                if (newActive > 0) switchTo(newActive) else openAndShow()
            }.start()
        }

        @JavascriptInterface
        fun send(b64: String) {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            Term.write(bytes)
        }

        @JavascriptInterface
        fun resize(c: Int, r: Int) {
            cols = c; rows = r
            Term.resize(r, c)
        }

        /** Escribe una línea en la sesión activa (p.ej. instalar un perfil). */
        @JavascriptInterface
        fun runLine(line: String) {
            Term.write(("\u0015" + line + "\n").toByteArray(Charsets.UTF_8))
        }

        /** Devuelve el contenido de DOCUMENTACION.md (assets/docs) para leerlo dentro de la app. */
        @JavascriptInterface
        fun readDoc(): String {
            return try {
                assets.open("docs/DOCUMENTACION.md").use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (_: Throwable) { "" }
        }


        /* ---------------- segundo plano ---------------- */

        /** ¿Está el 2º plano permitido (sin optimización de batería)? */
        @JavascriptInterface
        fun bgStatus(): String {
            return try {
                val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager
                val ok = pm?.isIgnoringBatteryOptimizations(packageName) ?: false
                "{\"ignoring\":$ok,\"sessions\":" + Term.sessionIds().size + "}"
            } catch (_: Throwable) { "{\"ignoring\":false}" }
        }

        /** Abre el diálogo del sistema para permitir 2º plano (exención de batería). */
        @JavascriptInterface
        fun bgRequestBattery() {
            runOnUiThread { try { requestBatteryExemption() } catch (_: Throwable) { } }
        }
        
        /**
         * Estadísticas del sistema para el monitor. Solo lo que Android permite leer a
         * las apps normales (no /proc/stat, que está vetado desde Android O).
         */
        @JavascriptInterface
        fun sysStats(): String {
            return try {
                val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                val cores = Runtime.getRuntime().availableProcessors()
                val load = try {
                    File("/proc/loadavg").readText().trim().split(Regex("\\s+")).take(3).joinToString(" ")
                } catch (_: Throwable) { "" }
                val cpu = try {
                    File("/proc/cpuinfo").readLines().firstOrNull {
                        it.startsWith("Hardware") || it.startsWith("model name")
                    }?.substringAfter(":")?.trim().orEmpty()
                } catch (_: Throwable) { "" }
                val st = StatFs(filesDir.path)
                val diskTotal = st.blockCountLong * st.blockSizeLong
                val diskAvail = st.availableBlocksLong * st.blockSizeLong
                val sb = StringBuilder()
                sb.append("{")
                sb.append("\"memTotal\":").append(mi.totalMem).append(",")
                sb.append("\"memAvail\":").append(mi.availMem).append(",")
                sb.append("\"cores\":").append(cores).append(",")
                sb.append("\"load\":\"").append(load.replace("\\", "").replace("\"", "")).append("\",")
                sb.append("\"cpu\":\"").append(cpu.replace("\\", "").replace("\"", "")).append("\",")
                sb.append("\"diskTotal\":").append(diskTotal).append(",")
                sb.append("\"diskAvail\":").append(diskAvail)
                sb.append("}")
                sb.toString()
            } catch (t: Throwable) {
                "{}"
            }
        }
    }

    /** Abre una sesión nueva y la muestra. */
    private fun openAndShow() {
        val id = try {
            Term.openSession(this, rows, cols) { t -> postToWeb(t.toByteArray(Charsets.UTF_8)) }
        } catch (t: Throwable) {
            postToWeb("\r\n[error] ${t.javaClass.simpleName}: ${t.message}\r\n".toByteArray(Charsets.UTF_8))
            -1
        }
        if (id > 0) switchTo(id) else sendTabs()
    }

    /** Activa una sesión: limpia la pantalla y vuelca su salida acumulada. */
    private fun switchTo(id: Int) {
        if (id <= 0) { sendTabs(); return }
        val backlog = Term.attach(id) { chunk -> postToWeb(chunk) }
        web.post {
            try { web.evaluateJavascript("window.termReset && window.termReset()", null) } catch (_: Throwable) { }
            if (backlog.isNotEmpty()) {
                val b64 = Base64.encodeToString(backlog, Base64.NO_WRAP)
                web.evaluateJavascript("window.termWrite && window.termWrite('$b64')", null)
            }
            sendTabsJs()
        }
    }

    private fun sendTabs() {
        web.post { sendTabsJs() }
    }

    private fun sendTabsJs() {
        val csv = Term.sessionIds().joinToString(",")
        val a = Term.activeSessionId()
        try {
            web.evaluateJavascript("window.onTabs && window.onTabs('$csv', $a)", null)
        } catch (_: Throwable) { }
    }

    private fun postToWeb(bytes: ByteArray) {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        web.post { web.evaluateJavascript("window.termWrite && window.termWrite('$b64')", null) }
    }

    override fun onDestroy() {
        // NO matamos las sesiones: siguen en segundo plano (TermProcessService).
        Term.detach()
        super.onDestroy()
    }
}
