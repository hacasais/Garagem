package pt.garagem.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Importação com a app fechada: com a app fechada, abre a própria app num WebView invisível, que lê o ficheiro JSON
 * e substitui os dados da app (a mesma rotina que corre quando abres a app). Os dados da app vivem
 * dentro do WebView, por isso é esta a forma de os atualizar sem a app estar aberta.
 */
class ImportWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        if (MainActivity.visible) return Result.success()   // a app está aberta: ela própria sincroniza
        val prefs = applicationContext.getSharedPreferences("garagem", Context.MODE_PRIVATE)
        if (prefs.getInt("import_min", 0) <= 0) return Result.success()   // importação desligada
        val uriStr = prefs.getString("uri", null) ?: return Result.success()   // ainda não há ficheiro escolhido

        // Poupança de bateria: só abre a app (WebView) se o ficheiro tiver mudado desde a última importação.
        val sig = fileSig(Uri.parse(uriStr))
        if (sig.isNotEmpty() && sig == prefs.getString("import_sig", "")) return Result.success()

        val latch = CountDownLatch(1)
        val bridge = HeadlessBridge(applicationContext, latch)
        var wv: WebView? = null
        Handler(Looper.getMainLooper()).post {
            try {
                val loader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(applicationContext))
                    .build()
                val w = WebView(applicationContext)
                wv = w
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.addJavascriptInterface(bridge, "Android")
                w.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader.shouldInterceptRequest(request.url)
                }
                w.loadUrl("https://appassets.androidplatform.net/assets/index.html?headless=1")
            } catch (e: Exception) {
                latch.countDown()
            }
        }
        latch.await(45, TimeUnit.SECONDS)
        // Fecha logo a app invisível, para não gastar bateria
        Handler(Looper.getMainLooper()).post {
            try {
                wv?.stopLoading()
                wv?.loadUrl("about:blank")
                wv?.destroy()
            } catch (e: Exception) { }
        }
        if (bridge.ok && sig.isNotEmpty()) prefs.edit().putString("import_sig", sig).apply()
        return Result.success()
    }

    /** Assinatura barata do ficheiro (data de modificação + tamanho; se não houver data, conteúdo). */
    private fun fileSig(u: Uri): String {
        try {
            applicationContext.contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (mi >= 0 && !c.isNull(mi)) {
                        val size = if (si >= 0 && !c.isNull(si)) c.getLong(si) else 0L
                        return "${c.getLong(mi)}|$size"
                    }
                }
            }
            val text = applicationContext.contentResolver.openInputStream(u)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            if (text != null) return "c|${text.length}|${text.hashCode()}"
        } catch (e: Exception) { }
        return ""
    }
}

/** Ponte mínima para o WebView invisível: só lê o ficheiro; nunca escreve nele. */
class HeadlessBridge(private val ctx: Context, private val latch: CountDownLatch) {
    @Volatile var ok = false

    private fun uri(): Uri? =
        ctx.getSharedPreferences("garagem", Context.MODE_PRIVATE).getString("uri", null)?.let { Uri.parse(it) }

    @JavascriptInterface fun importIntervalMin(): Int =
        ctx.getSharedPreferences("garagem", Context.MODE_PRIVATE).getInt("import_min", 0)

    @JavascriptInterface fun hasFile(): Boolean = uri() != null

    @JavascriptInterface fun saveFile(data: String): Boolean = false

    @JavascriptInterface fun readFile(): String {
        val u = uri() ?: return ""
        return try {
            ctx.contentResolver.openInputStream(u)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) { "" }
    }

    @JavascriptInterface fun fileName(): String = JSONObject(fileInfo()).optString("name", "")

    @JavascriptInterface fun fileInfo(): String {
        val u = uri() ?: return "{}"
        val o = JSONObject()
        try {
            ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
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
        return o.toString()
    }

    /** A página avisa que terminou a importação. */
    @JavascriptInterface fun headlessDone(success: Boolean) { ok = success; latch.countDown() }
}
