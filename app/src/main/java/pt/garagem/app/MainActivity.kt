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
        fun fileName(): String {
            val u = fileUri() ?: return ""
            return try {
                contentResolver.query(u, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) ?: "" else ""
                } ?: ""
            } catch (e: Exception) { "" }
        }

        @JavascriptInterface
        fun createFile() {
            runOnUiThread {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "garagem-dados.json")
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
                    } catch (e: Exception) { }
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
}
