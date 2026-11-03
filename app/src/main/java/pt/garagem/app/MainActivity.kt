package pt.garagem.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var pendingPerm: PermissionRequest? = null
    private val prefs by lazy { getSharedPreferences("garagem", MODE_PRIVATE) }

    private fun fileUri(): Uri? = prefs.getString("uri", null)?.let { Uri.parse(it) }

    inner class Bridge {
        @JavascriptInterface
        fun hasFile(): Boolean = fileUri() != null

        @JavascriptInterface
        fun isSlave(): Boolean = BuildConfig.SLAVE

        // Intervalo da importação em segundo plano (minutos; 0 = desligada)
        @JavascriptInterface
        fun importIntervalMin(): Int = prefs.getInt("import_min", 180)

        @JavascriptInterface
        fun setImportInterval(minutes: Int) {
            prefs.edit().putInt("import_min", minutes).apply()
            scheduleImport(this@MainActivity, minutes, ExistingPeriodicWorkPolicy.UPDATE)
        }

        @JavascriptInterface
        fun fileName(): String {
            val u = fileUri() ?: return ""
            return try {
                contentResolver.query(u, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) ?: "" else ""
                } ?: ""
            } catch (e: Exception) { "" }
        }

        // Nome, local, tamanho e data de modificação do ficheiro ligado (para o utilizador saber onde a app grava)
        @JavascriptInterface
        fun fileInfo(): String {
            val u = fileUri() ?: return "{}"
            val o = JSONObject()
            try {
                contentResolver.query(u, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0) o.put("name", c.getString(ni) ?: "")
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (si >= 0 && !c.isNull(si)) o.put("size", c.getLong(si))
                        val mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        if (mi >= 0 && !c.isNull(mi)) o.put("modified", c.getLong(mi))
                    }
                }
            } catch (e: Exception) { }
            o.put("where", describeLocation(u))
            return o.toString()
        }

        private fun describeLocation(u: Uri): String {
            val auth = u.authority ?: ""
            val docId = try { DocumentsContract.getDocumentId(u) } catch (e: Exception) { "" }
            return when {
                auth == "com.android.externalstorage.documents" -> {
                    val parts = docId.split(":", limit = 2)
                    val vol = if (parts[0] == "primary") "Armazenamento interno" else "Cartão SD"
                    val path = (parts.getOrNull(1) ?: "").substringBeforeLast('/', "")
                    if (path.isEmpty()) vol else vol + " › " + path.replace("/", " › ")
                }
                auth.contains("downloads") -> "Transferências"
                auth.contains("google.android.apps.docs") -> "Google Drive"
                auth.contains("onedrive") || auth.contains("skydrive") -> "OneDrive"
                auth.contains("dropbox") -> "Dropbox"
                else -> auth
            }
        }

        @JavascriptInterface
        fun createFile() {
            runOnUiThread {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "rltec-ativos-dados.json")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(i, 1)
            }
        }

        @JavascriptInterface
        fun pickFile() {
            runOnUiThread {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(i, 2)
            }
        }

        @JavascriptInterface
        fun saveFile(data: String): Boolean {
            val u = fileUri() ?: return false
            return try {
                contentResolver.openOutputStream(u, "wt")!!.use { it.write(data.toByteArray(Charsets.UTF_8)) }
                true
            } catch (e: Exception) { false }
        }

        @JavascriptInterface
        fun readFile(): String {
            val u = fileUri() ?: return ""
            return try {
                contentResolver.openInputStream(u)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Exception) { "" }
        }

        @JavascriptInterface
        fun clearFile() { prefs.edit().remove("uri").apply() }

        // ---- Emails de alerta (SMTP) ----
        // ---- Início de sessão Microsoft (Outlook) ----
        @JavascriptInterface
        fun msLoginStart(clientId: String) {
            Thread {
                try {
                    val st = MsMailer.deviceStart(clientId.trim())
                    val info = JSONObject()
                        .put("code", st.optString("user_code"))
                        .put("uri", st.optString("verification_uri", "https://www.microsoft.com/link"))
                    runOnUiThread { web.evaluateJavascript("window.onMsLogin&&window.onMsLogin(${JSONObject.quote(info.toString())})", null) }
                    val r = MsMailer.devicePoll(clientId.trim(), st)
                    val done = JSONObject().put("done", true).put("refresh", r.optString("refresh")).put("user", r.optString("user"))
                    runOnUiThread { web.evaluateJavascript("window.onMsLogin&&window.onMsLogin(${JSONObject.quote(done.toString())})", null) }
                } catch (e: Exception) {
                    val err = JSONObject().put("error", e.message ?: e.toString())
                    runOnUiThread { web.evaluateJavascript("window.onMsLogin&&window.onMsLogin(${JSONObject.quote(err.toString())})", null) }
                }
            }.start()
        }

        @JavascriptInterface
        fun msCurrent(jsonRefresh: String): String = MsMailer.currentRefresh(this@MainActivity, jsonRefresh)

        @JavascriptInterface
        fun openUrl(url: String) {
            try {
                if (url.startsWith("https://")) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) { }
        }

        @JavascriptInterface
        fun emailStatus(): String =
            getSharedPreferences("garagem_email_status", MODE_PRIVATE).getString("last", "") ?: ""

        @JavascriptInterface
        fun emailTest(recipientsCsv: String, smtpJson: String) {
            Thread {
                val res = JSONObject()
                try {
                    val to = recipientsCsv.split(Regex("[,;\\s]+")).filter { it.contains("@") }
                    val cfg = SmtpCfg.fromJson(JSONObject(smtpJson)) ?: throw Exception("Servidor de email por configurar")
                    Mailer.send(
                        this@MainActivity, cfg, to, "RLTEC Ativos: email de teste",
                        "Este é um email de teste da app RLTEC Ativos.\nSe o recebeste, os avisos por email estão a funcionar."
                    )
                    res.put("ok", true).put("msg", "Email de teste enviado para ${to.size} destinatário(s).")
                } catch (e: Exception) {
                    res.put("ok", false).put("msg", e.message ?: e.toString())
                }
                runOnUiThread {
                    web.evaluateJavascript("window.onEmailTest&&window.onEmailTest(${JSONObject.quote(res.toString())})", null)
                }
            }.start()
        }

        // Partilha uma cópia dos dados (JSON) pelo menu "Partilhar" do Android (email, Drive, WhatsApp…)
        @JavascriptInterface
        fun shareJson(json: String, filename: String) {
            runOnUiThread {
                try {
                    val dir = File(cacheDir, "attach").apply { mkdirs() }
                    val safeName = (if (filename.isBlank()) "rltec-ativos.json" else filename).replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val file = File(dir, safeName)
                    file.writeText(json, Charsets.UTF_8)
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "RLTEC Ativos — cópia de segurança")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Partilhar cópia"))
                } catch (e: Exception) {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("Não foi possível partilhar a cópia: " + (e.message ?: e.toString()))
                        .setPositiveButton("OK", null).show()
                }
            }
        }

        @JavascriptInterface
        fun openDataUri(dataUri: String, filename: String) {
            runOnUiThread {
                try {
                    val comma = dataUri.indexOf(',')
                    val header = dataUri.substring(5, comma)
                    val mime = header.substringBefore(";").ifBlank { "application/octet-stream" }
                    val bytes = android.util.Base64.decode(dataUri.substring(comma + 1), android.util.Base64.DEFAULT)
                    val dir = File(cacheDir, "attach").apply { mkdirs() }
                    val safeName = (if (filename.isBlank()) "ficheiro" else filename).replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val file = File(dir, safeName)
                    file.writeBytes(bytes)
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    } else {
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage("Não há nenhuma app instalada neste telemóvel para abrir este tipo de ficheiro.")
                            .setPositiveButton("OK", null).show()
                    }
                } catch (e: Exception) {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("Não foi possível abrir o ficheiro.")
                        .setPositiveButton("OK", null).show()
                }
            }
        }

        @JavascriptInterface
        fun scanOdometer() {
            runOnUiThread {
                try {
                    val dir = File(cacheDir, "odo").apply { mkdirs() }
                    val file = File.createTempFile("odo_", ".jpg", dir)
                    photoFile = file
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivityForResult(intent, 4)
                    } else {
                        callJs(JSONObject().put("ok", false).put("error", "Sem app de câmara disponível"))
                    }
                } catch (e: Exception) {
                    callJs(JSONObject().put("ok", false).put("error", e.message ?: "erro ao abrir a câmara"))
                }
            }
        }
    }

    private var photoFile: File? = null

    private fun callJs(json: JSONObject) {
        web.evaluateJavascript("window.onOdometerScan&&window.onOdometerScan(${JSONObject.quote(json.toString())})", null)
    }

    private fun processOdometerPhoto() {
        val file = photoFile
        if (file == null || !file.exists()) { callJs(JSONObject().put("ok", false).put("error", "fotografia não encontrada")); return }
        try {
            var bmp: Bitmap = BitmapFactory.decodeFile(file.absolutePath)
                ?: throw Exception("não foi possível abrir a fotografia")
            val orientation = ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotation != 0f) {
                val m = Matrix().apply { postRotate(rotation) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val image = InputImage.fromBitmap(bmp, 0)
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
                .addOnSuccessListener { result ->
                    val candidates = extractCandidates(result.text)
                    val arr = JSONArray()
                    candidates.forEach { arr.put(it) }
                    callJs(JSONObject().put("ok", true).put("candidates", arr))
                    file.delete()
                }
                .addOnFailureListener { e ->
                    callJs(JSONObject().put("ok", false).put("error", e.message ?: "falha no reconhecimento"))
                    file.delete()
                }
        } catch (e: Exception) {
            callJs(JSONObject().put("ok", false).put("error", e.message ?: "erro ao processar a fotografia"))
        }
    }

    // Números de 4 a 7 dígitos, sem ponto nem vírgula decimal (o odómetro total é sempre inteiro;
    // o parcial costuma ter casas decimais, por isso fica de fora à partida).
    private fun extractCandidates(text: String): List<Long> {
        val tokens = Regex("\\d+(?:[.,]\\d+)?").findAll(text).map { it.value }.toList()
        return tokens.filter { !it.contains(".") && !it.contains(",") }
            .mapNotNull { it.toLongOrNull() }
            .filter { it in 1000..9999999 }
            .distinct()
            .sortedDescending()
    }

    companion object {
        // true enquanto a app está visível (a importação em segundo plano não corre nessa altura)
        @Volatile var visible = false

        /** Agenda (ou cancela, se minutos <= 0) a importação em segundo plano da versão slave. Mínimo do Android: 15 min. */
        fun scheduleImport(ctx: android.content.Context, minutes: Int, policy: ExistingPeriodicWorkPolicy) {
            val wm = WorkManager.getInstance(ctx)
            if (!BuildConfig.SLAVE || minutes <= 0) { wm.cancelUniqueWork("garagem_import_check"); return }
            val req = PeriodicWorkRequestBuilder<ImportWorker>(maxOf(15, minutes).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())   // poupa bateria
                .build()
            wm.enqueueUniquePeriodicWork("garagem_import_check", policy, req)
        }
    }

    override fun onResume() { super.onResume(); visible = true }
    override fun onPause() { visible = false; super.onPause() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.resources)
                    } else {
                        pendingPerm = request
                        requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
                    }
                }
            }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                chooser?.onReceiveValue(null)
                chooser = callback
                return try {
                    startActivityForResult(params.createIntent(), 3)
                    true
                } catch (e: Exception) {
                    chooser = null
                    false
                }
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("Cancelar") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
        }
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
        }
        val work = PeriodicWorkRequestBuilder<AlertWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("garagem_alert_check", ExistingPeriodicWorkPolicy.KEEP, work)
        // Versão slave: importa o ficheiro JSON com a app fechada, no intervalo escolhido pelo utilizador (por defeito 3 h)
        if (BuildConfig.SLAVE) scheduleImport(this, prefs.getInt("import_min", 180), ExistingPeriodicWorkPolicy.KEEP)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            val p = pendingPerm
            pendingPerm = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) p?.grant(p.resources) else p?.deny()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            1, 2 -> {
                val u = data?.data
                if (resultCode == RESULT_OK && u != null) {
                    try {
                        contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    } catch (e: Exception) {
                        try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e2: Exception) { }
                    }
                    prefs.edit().putString("uri", u.toString()).apply()
                    val mode = if (requestCode == 1) "create" else "open"
                    web.evaluateJavascript("window.onAndroidFile&&window.onAndroidFile('$mode')", null)
                }
            }
            3 -> {
                chooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
                chooser = null
            }
            4 -> {
                if (resultCode == RESULT_OK) {
                    processOdometerPhoto()
                } else {
                    callJs(JSONObject().put("ok", false).put("error", "cancelado"))
                }
            }
        }
    }

    // Botão "Voltar": a app decide (fecha janelas / volta ao menu principal); só sai quando já está no menu principal.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("(function(){try{return window.appBack?window.appBack():false}catch(e){return false}})()") { r ->
            if (r != "true") finish()
        }
    }
}
