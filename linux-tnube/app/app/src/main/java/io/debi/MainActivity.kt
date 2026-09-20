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
        // El wake lock solo mientras haya secciones/gateway activos (ahorro de batería).
        Term.onSessionCountChanged = { n -> TermProcessService.setWake(n > 0) }
        requestNeededPermissions()
        requestBatteryExemption()
    }

    /**
     * Pide exención de optimización de batería. Sin esto, muchos Android (Xiaomi,
     * Samsung, Huawei…) matan el proceso en segundo plano y el gateway se apaga al
     * salir de la app.
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

        /** Escribe una línea en la sesión activa (p.ej. instalar un perfil o abrir la pestaña). */
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


        /** Copia texto (p.ej. los logs del terminal) al portapapeles del sistema. */
        @JavascriptInterface
        fun copyToClipboard(text: String) {
            try {
                runOnUiThread {
                    try {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Linux-TNube Pro", text))
                    } catch (_: Throwable) { }
                }
            } catch (_: Throwable) { }
        }

        /* ---------------- OpenClaw ---------------- */

        private fun strJson(s: String): String = s.replace("\\", "").replace("\"", "")

        /** Estado de las dependencias de OpenClaw + gateway (chequeo rápido por ficheros). */
        @JavascriptInterface
        fun ocStatus(): String {
            return try {
                val rootfs = File(filesDir, "rootfs")
                val nodeBin = File(rootfs, "opt/node/bin/node")
                val npmPkg = File(rootfs, "opt/node/lib/node_modules/npm/package.json")
                val ocPkg = File(rootfs, "usr/local/lib/node_modules/openclaw/package.json")

                val nodeV = if (nodeBin.exists()) {
                    val h = File(rootfs, "opt/node/include/node/node_version.h")
                    if (h.exists()) {
                        val t = h.readText()
                        fun n(k: String) = Regex(k + "\\s+(\\d+)").find(t)?.groupValues?.get(1)
                        val a = n("NODE_MAJOR_VERSION"); val b = n("NODE_MINOR_VERSION"); val c = n("NODE_PATCH_VERSION")
                        if (a != null && b != null && c != null) "v$a.$b.$c" else "ok"
                    } else "ok"
                } else ""

                val npmV = if (npmPkg.exists())
                    Regex("\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(npmPkg.readText())?.groupValues?.get(1) ?: "ok"
                else ""

                val ocV = if (ocPkg.exists())
                    Regex("\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(ocPkg.readText())?.groupValues?.get(1) ?: "ok"
                else ""

                val sb = StringBuilder()
                sb.append("{")
                sb.append("\"node\":\"").append(strJson(nodeV)).append("\",")
                sb.append("\"npm\":\"").append(strJson(npmV)).append("\",")
                sb.append("\"openclaw\":\"").append(strJson(ocV)).append("\",")
                val cfg = File(rootfs, "root/.openclaw/openclaw.json")
                val cfgTxt = try { if (cfg.exists()) cfg.readText() else "" } catch (_: Throwable) { "" }
                val keySet = run {
                    val v = Regex("\\\"apiKey\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(cfgTxt)?.groupValues?.get(1) ?: ""
                    v.isNotEmpty() && !v.startsWith("__")
                }
                val tgSet = run {
                    val v = Regex("\\\"botToken\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(cfgTxt)?.groupValues?.get(1) ?: ""
                    v.isNotEmpty()
                }

                val wsCfg = Regex("\\\"workspace\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(cfgTxt)?.groupValues?.get(1) ?: ""
                val wsPath = wsCfg.ifEmpty { "/root/.openclaw/workspace" }
                val wsHost = if (wsPath.startsWith("/")) File(rootfs, wsPath.removePrefix("/")) else null
                val wsExists = wsHost?.exists() ?: false

                sb.append("\"gateway\":").append(Term.gatewayAlive()).append(",")
                sb.append("\"gwId\":").append(Term.gatewaySessionId).append(",")
                sb.append("\"keySet\":").append(keySet).append(",")
                sb.append("\"tgSet\":").append(tgSet).append(",")
                sb.append("\"workspace\":\"").append(strJson(wsPath)).append("\",")
                sb.append("\"wsExists\":").append(wsExists)
                sb.append("}")
                sb.toString()
            } catch (t: Throwable) {
                "{\"node\":\"\",\"npm\":\"\",\"openclaw\":\"\",\"gateway\":false}"
            }
        }

        /**
         * Comprueba el runtime de OpenClaw. En Linux-TNube Pro TODO viene empaquetado en el
         * APK (rootfs + Node + OpenClaw), así que NO se descarga nada: si falta, es que
         * la app está corrupta y hay que reinstalarla.
         */
        @JavascriptInterface
        fun ocInstall() {
            Thread {
                val script = buildString {
                    append("echo '== Linux-TNube Pro · OpenClaw =='; ")
                    append("echo 'Todo viene incluido en la app (sin descargas).'; ")
                    append("echo \"node:     $(command -v node >/dev/null 2>&1 && node -v || echo FALTA)\"; ")
                    append("echo \"npm:      $(command -v npm  >/dev/null 2>&1 && npm -v  || echo FALTA)\"; ")
                    append("if command -v openclaw >/dev/null 2>&1; then ")
                    append("echo \"openclaw: $(openclaw --version 2>&1 | head -1)\"; ")
                    append("else ")
                    append("echo 'openclaw: NO encontrado. Reinstala Linux-TNube Pro para restaurar el runtime empaquetado.'; ")
                    append("fi; echo '== Listo =='")
                }
                val id = Term.openSession(this@MainActivity, rows, cols) { t ->
                    postToWeb(t.toByteArray(Charsets.UTF_8))
                }
                if (id > 0) {
                    switchTo(id)
                    // Damos un instante a que arranque la shell, y escribimos el script.
                    try { Thread.sleep(400) } catch (_: Throwable) { }
                    Term.write((script + "\n").toByteArray(Charsets.UTF_8))
                } else {
                    sendTabs()
                }
            }.start()
        }

        /** Guarda la API key del proveedor (DeepSeek) y, si `start`, arranca el gateway. */
        @JavascriptInterface
        fun ocSetKey(keyRaw: String, start: Boolean) {
            Thread {
                val key = (keyRaw ?: "").trim().filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
                if (key.isNotEmpty()) {
                    try {
                        Term.runCommand(this@MainActivity,
                            "openclaw config set models.providers.deepseek.apiKey '$key'", 90000)
                    } catch (_: Throwable) { }
                }
                if (start && !Term.gatewayAlive()) {
                    try {
                        Term.startGateway(this@MainActivity, rows, cols) { t ->
                            postToWeb(t.toByteArray(Charsets.UTF_8))
                        }
                    } catch (_: Throwable) { }
                }
                sendTabs()
            }.start()
        }

        /** ¿Está el 2º plano permitido (sin optimización de batería)? */
        @JavascriptInterface
        fun ocBatteryStatus(): String {
            return try {
                val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager
                val ok = pm?.isIgnoringBatteryOptimizations(packageName) ?: false
                "{\"ignoring\":$ok}"
            } catch (_: Throwable) { "{\"ignoring\":false}" }
        }

        /** Abre el diálogo del sistema para permitir 2º plano (exención de batería). */
        @JavascriptInterface
        fun ocRequestBattery() {
            runOnUiThread { try { requestBatteryExemption() } catch (_: Throwable) { } }
        }

        /** Guarda el token del bot de Telegram y reinicia el gateway para activarlo. */
        @JavascriptInterface
        fun ocSetTgToken(tokenRaw: String, start: Boolean) {
            Thread {
                val token = (tokenRaw ?: "").trim().filter { it.isLetterOrDigit() || it == ':' || it == '-' || it == '_' }
                if (token.isNotEmpty()) {
                    try {
                        Term.runCommand(this@MainActivity,
                            "openclaw config set channels.telegram.botToken '$token' && " +
                            "openclaw config set channels.telegram.enabled true && " +
                            "openclaw config set channels.telegram.dmPolicy open && " +
                            "openclaw config set channels.telegram.allowFrom '[\"*\"]'", 150_000)
                    } catch (_: Throwable) { }
                }
                if (start) {
                    try {
                        if (Term.gatewayAlive()) { Term.stopGateway(); Thread.sleep(900) }
                        Term.startGateway(this@MainActivity, rows, cols) { t ->
                            postToWeb(t.toByteArray(Charsets.UTF_8))
                        }
                    } catch (_: Throwable) { }
                }
                sendTabs()
            }.start()
        }

        /** Repara gateway/workspace de OpenClaw (`openclaw doctor`). Se ve en una sección. */
        @JavascriptInterface
        fun ocDoctor() {
            Thread {
                val id = Term.openSession(this@MainActivity, rows, cols) { t ->
                    postToWeb(t.toByteArray(Charsets.UTF_8))
                }
                if (id > 0) {
                    switchTo(id)
                    try { Thread.sleep(400) } catch (_: Throwable) { }
                    Term.write("/usr/local/bin/oc-prepare 2>&1\n".toByteArray(Charsets.UTF_8))
                } else {
                    sendTabs()
                }
            }.start()
        }

        /** Activa/desactiva el gateway de OpenClaw en segundo plano. */
        @JavascriptInterface
        fun ocGateway(enable: Boolean) {
            Thread {
                if (enable) {
                    val ok = Term.startGateway(this@MainActivity, rows, cols,
                        { t -> postToWeb(t.toByteArray(Charsets.UTF_8)) })
                    if (!ok) postToWeb("\r\n[error] no se pudo arrancar el gateway (¿límite de pestañas?)\r\n".toByteArray(Charsets.UTF_8))
                } else {
                    Term.stopGateway()
                }
                sendTabs()
            }.start()
        }

        /* ---------------- APIs de IA (Linux-TNube Pro) ---------------- */

        private fun rootfsDir(): File = File(filesDir, "rootfs")
        private fun ocConfigFile(): File = File(rootfsDir(), "root/.openclaw/openclaw.json")
        private fun routerCfgFile(): File = File(rootfsDir(), "root/.tnube-router.json")
        private fun routerUsageFile(): File = File(rootfsDir(), "root/.tnube-router-usage.json")

        private fun readAsset(name: String): String = try {
            assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Throwable) { "" }

        private fun catalog(): org.json.JSONObject = try {
            org.json.JSONObject(readAsset("oc-config/providers.json"))
        } catch (_: Throwable) {
            org.json.JSONObject("{\"providers\":[],\"roles\":{},\"priority\":[]}")
        }

        /** Catálogo de proveedores de IA (JSON) para pintar los interruptores en Ajustes. */
        @JavascriptInterface
        fun apiCatalog(): String = readAsset("oc-config/providers.json").ifEmpty { "{}" }

        /** Estado actual: router, gateway, proveedores activos, claves puestas y uso de hoy. */
        @JavascriptInterface
        fun apiState(): String {
            return try {
                val cfg = try { org.json.JSONObject(ocConfigFile().readText()) } catch (_: Throwable) { org.json.JSONObject() }
                val provs = cfg.optJSONObject("models")?.optJSONObject("providers") ?: org.json.JSONObject()
                val enabled = org.json.JSONArray()
                val keysSet = org.json.JSONObject()
                val it = provs.keys()
                while (it.hasNext()) {
                    val id = it.next()
                    if (id == "tnube-router") continue
                    val k = provs.optJSONObject(id)?.optString("apiKey", "") ?: ""
                    keysSet.put(id, k.isNotEmpty())
                    if (k.isNotEmpty()) enabled.put(id)
                }
                val primary = cfg.optJSONObject("agents")?.optJSONObject("defaults")
                    ?.optJSONObject("model")?.optString("primary", "") ?: ""
                val out = org.json.JSONObject()
                out.put("routerEnabled", Term.routerEnabled)
                out.put("routerAlive", Term.routerAlive())
                out.put("gatewayAlive", Term.gatewayAlive())
                out.put("primary", primary)
                out.put("enabled", enabled)
                out.put("keysSet", keysSet)
                out.put("usage", try { org.json.JSONObject(routerUsageFile().readText()) } catch (_: Throwable) { org.json.JSONObject() })
                out.toString()
            } catch (t: Throwable) { "{}" }
        }

        /** Copia el script del router dentro del rootfs (con permisos de ejecución). */
        private fun installRouterScript() {
            try {
                val bin = File(rootfsDir(), "usr/local/bin")
                bin.mkdirs()
                val js = File(bin, "tnube-router.mjs")
                val src = assets.open("oc-bin/tnube-router.mjs").use { it.readBytes() }
                if (!js.exists() || !js.readBytes().contentEquals(src)) js.writeBytes(src)
                js.setReadable(true, false)
                val shim = File(bin, "tnube-router")
                val sh = "#!/bin/sh\nexec node /usr/local/bin/tnube-router.mjs \"\$@\"\n"
                if (!shim.exists() || shim.readText() != sh) shim.writeText(sh)
                shim.setExecutable(true, false)
                shim.setReadable(true, false)
            } catch (_: Throwable) { }
        }

        /**
         * Aplica el estado de las APIs de IA: escribe openclaw.json y la config del router,
         * arranca/para el router y reinicia el gateway para recargar la configuración.
         */
        @JavascriptInterface
        fun apiApply(json: String): String {
            return try {
                val o = org.json.JSONObject(json)
                val routerOn = o.optBoolean("router", false)
                val primaryWant = o.optString("primary", "")
                val enabledArr = o.optJSONArray("enabled") ?: org.json.JSONArray()
                val keys = o.optJSONObject("keys") ?: org.json.JSONObject()
                val cat = catalog()
                val catProvs = cat.optJSONArray("providers") ?: org.json.JSONArray()

                // Asegura que el rootfs existe antes de escribir dentro.
                try { Term.runCommand(this@MainActivity, "true", 300000) } catch (_: Throwable) { }

                val wanted = HashSet<String>()
                for (i in 0 until enabledArr.length()) wanted.add(enabledArr.optString(i))

                // Config actual (para conservar claves ya guardadas y el resto de ajustes).
                val cfgFile = ocConfigFile()
                val cfg = try { org.json.JSONObject(cfgFile.readText()) } catch (_: Throwable) { org.json.JSONObject() }
                val oldProvs = cfg.optJSONObject("models")?.optJSONObject("providers") ?: org.json.JSONObject()

                val provs = org.json.JSONObject()
                val rprovs = org.json.JSONObject()
                val enabledIds = ArrayList<String>()

                for (i in 0 until catProvs.length()) {
                    val p = catProvs.optJSONObject(i) ?: continue
                    val id = p.optString("id")
                    if (id.isEmpty() || !wanted.contains(id)) continue
                    var key = (keys.optString(id, "")).trim()
                    if (key == "__KEEP__") key = oldProvs.optJSONObject(id)?.optString("apiKey", "") ?: ""
                    // Proveedores sin key (p. ej. Pollinations): funcionan al instante.
                    if (key.isEmpty() && p.optBoolean("noKey", false)) key = "no-key"
                    if (key.isEmpty()) continue
                    val mm = p.optJSONObject("models") ?: org.json.JSONObject()
                    val modelsArr = org.json.JSONArray()
                    val seen = HashSet<String>()
                    val kit = mm.keys()
                    while (kit.hasNext()) {
                        val role = kit.next()
                        val mid = mm.optString(role)
                        if (mid.isEmpty() || seen.contains(mid)) continue
                        seen.add(mid)
                        val mj = org.json.JSONObject()
                        mj.put("id", mid)
                        mj.put("name", "${p.optString("name")} · $mid")
                        mj.put("api", "openai-completions")
                        mj.put("contextWindow", p.optInt("contextWindow", 131072))
                        mj.put("maxTokens", p.optInt("maxTokens", 8192))
                        if (role == "vision") mj.put("input", org.json.JSONArray(listOf("text", "image")))
                        else mj.put("input", org.json.JSONArray(listOf("text")))
                        modelsArr.put(mj)
                    }
                    val pj = org.json.JSONObject()
                    pj.put("baseUrl", p.optString("baseUrl"))
                    pj.put("api", "openai-completions")
                    pj.put("apiKey", key)
                    pj.put("models", modelsArr)
                    provs.put(id, pj)

                    val rp = org.json.JSONObject()
                    rp.put("name", p.optString("name"))
                    rp.put("baseUrl", p.optString("baseUrl"))
                    rp.put("apiKey", key)
                    rp.put("models", mm)
                    rprovs.put(id, rp)

                    enabledIds.add(id)
                }

                // El proveedor "tnube-router" solo existe si el router está activo.
                if (routerOn) {
                    val rj = org.json.JSONObject()
                    rj.put("baseUrl", "http://127.0.0.1:8790/v1")
                    rj.put("api", "openai-completions")
                    rj.put("apiKey", "tnube-local")
                    val mj = org.json.JSONObject()
                    mj.put("id", "auto")
                    mj.put("name", "Auto (router TNube)")
                    mj.put("api", "openai-completions")
                    mj.put("contextWindow", 131072)
                    mj.put("maxTokens", 8192)
                    mj.put("input", org.json.JSONArray(listOf("text")))
                    rj.put("models", org.json.JSONArray(listOf(mj)))
                    provs.put("tnube-router", rj)
                }

                // --- openclaw.json ---
                cfgFile.parentFile?.mkdirs()
                if (!cfg.has("gateway")) {
                    val g = org.json.JSONObject()
                    g.put("mode", "local"); g.put("bind", "loopback"); g.put("port", 18789)
                    val a = org.json.JSONObject(); a.put("mode", "none"); g.put("auth", a)
                    cfg.put("gateway", g)
                }
                if (!cfg.has("agents")) cfg.put("agents", org.json.JSONObject())
                val agents = cfg.getJSONObject("agents")
                if (!agents.has("defaults")) agents.put("defaults", org.json.JSONObject())
                val defaults = agents.getJSONObject("defaults")
                val models = if (cfg.has("models")) cfg.getJSONObject("models") else org.json.JSONObject().also { cfg.put("models", it) }
                models.put("providers", provs)

                // Cadena primario + fallbacks.
                val chain = ArrayList<String>()
                if (routerOn) {
                    chain.add("tnube-router/auto")
                    for (id in enabledIds) {
                        val p = rprovs.optJSONObject(id) ?: continue
                        val dm = p.optJSONObject("models")?.optString("default") ?: ""
                        if (dm.isNotEmpty()) chain.add("$id/$dm")
                    }
                } else {
                    val pid = if (wanted.contains(primaryWant) && rprovs.has(primaryWant)) primaryWant else enabledIds.firstOrNull() ?: ""
                    if (pid.isNotEmpty()) {
                        val dm = rprovs.optJSONObject(pid)?.optJSONObject("models")?.optString("default") ?: ""
                        if (dm.isNotEmpty()) chain.add("$pid/$dm")
                        for (id in enabledIds) {
                            if (id == pid) continue
                            val p = rprovs.optJSONObject(id) ?: continue
                            val m2 = p.optJSONObject("models")?.optString("default") ?: ""
                            if (m2.isNotEmpty()) chain.add("$id/$m2")
                        }
                    }
                }
                val model = org.json.JSONObject()
                if (chain.isNotEmpty()) model.put("primary", chain[0])
                val fb = org.json.JSONArray()
                for (i in 1 until chain.size) fb.put(chain[i])
                model.put("fallbacks", fb)
                defaults.put("model", model)
                if (!defaults.has("workspace")) defaults.put("workspace", "/root/.openclaw/workspace")
                cfgFile.writeText(cfg.toString(2))

                // --- config del router ---
                val rc = org.json.JSONObject()
                rc.put("port", cat.optInt("routerPort", 8790))
                rc.put("enabled", org.json.JSONArray(enabledIds))
                rc.put("roles", cat.optJSONObject("roles") ?: org.json.JSONObject())
                rc.put("priority", cat.optJSONArray("priority") ?: org.json.JSONArray())
                rc.put("providers", rprovs)
                routerCfgFile().writeText(rc.toString(2))

                installRouterScript()

                // --- router on/off ---
                if (routerOn) {
                    if (!Term.routerAlive()) {
                        Term.startRouter(this@MainActivity, rows, cols) { t -> postToWeb(t.toByteArray(Charsets.UTF_8)) }
                    }
                } else if (Term.routerAlive()) {
                    Term.stopRouter()
                }

                // --- reinicia el gateway para recargar la config ---
                if (Term.gatewayAlive()) {
                    Term.stopGateway()
                    try { Thread.sleep(900) } catch (_: Throwable) { }
                    Term.startGateway(this@MainActivity, rows, cols) { t -> postToWeb(t.toByteArray(Charsets.UTF_8)) }
                }
                sendTabs()
                "ok: ${enabledIds.size} API(s) activas" + if (routerOn) ", router ON" else ""
            } catch (t: Throwable) {
                "error: ${t.javaClass.simpleName}: ${t.message}"
            }
        }

        /** Uso local de hoy (lo escribe el router) + salud del router. */
        @JavascriptInterface
        fun apiUsage(): String {
            return try {
                val u = try { org.json.JSONObject(routerUsageFile().readText()) } catch (_: Throwable) { org.json.JSONObject() }
                val out = org.json.JSONObject()
                out.put("routerAlive", Term.routerAlive())
                out.put("usage", u)
                out.toString()
            } catch (_: Throwable) { "{}" }
        }

        /* ---- Cuota real del proveedor (consulta asíncrona) ---- */
        @Volatile private var quotaRunning = false
        @Volatile private var quotaResult = ""
        @Volatile private var quotaRaw = ""
        @Volatile private var quotaStartedAt = 0L

        /** Lanza en segundo plano la consulta de cuota: `openclaw status --usage --json`. */
        @JavascriptInterface
        fun apiQuotaStart() {
            if (quotaRunning) return
            quotaStartedAt = System.currentTimeMillis()
            val rootfs = File(filesDir, "rootfs")
            if (!rootfs.exists()) {
                quotaResult = ""
                quotaRaw = "rootfs no instalado todavía: abre una sección y espera a que termine la instalación."
                return
            }
            quotaRunning = true
            quotaResult = ""
            quotaRaw = ""
            Thread {
                try {
                    val out = Term.runCommand(this@MainActivity, "openclaw status --usage --json 2>/dev/null", 180000)
                    quotaRaw = out
                    quotaResult = extractUsage(out)
                } catch (t: Throwable) {
                    quotaRaw = "error: ${t.javaClass.simpleName}: ${t.message}"
                } finally {
                    quotaRunning = false
                }
            }.start()
        }

        /** Estado de la consulta de cuota (para que el JS la refleje sin bloquearse). */
        @JavascriptInterface
        fun apiQuotaPoll(): String {
            return try {
                val o = org.json.JSONObject()
                o.put("running", quotaRunning)
                o.put("elapsed", if (quotaStartedAt == 0L) 0 else ((System.currentTimeMillis() - quotaStartedAt) / 1000).toInt())
                o.put("result", if (quotaResult.isEmpty()) org.json.JSONObject() else org.json.JSONObject(quotaResult))
                o.put("raw", quotaRaw.take(600).replace("\n", " "))
                o.toString()
            } catch (_: Throwable) {
                "{\"running\":false,\"result\":{},\"raw\":\"\"}"
            }
        }

        /** Extrae el objeto `usage` del JSON de `openclaw status --usage --json`. */
        private fun extractUsage(out: String): String {
            var idx = out.indexOf('{')
            while (idx >= 0) {
                try {
                    val j = org.json.JSONObject(out.substring(idx))
                    val u = j.optJSONObject("usage")
                    if (u != null) return u.toString()
                } catch (_: Throwable) { }
                idx = out.indexOf('{', idx + 1)
            }
            return ""
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
