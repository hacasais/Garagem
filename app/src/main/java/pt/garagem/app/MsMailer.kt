package pt.garagem.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Envio por conta Microsoft (Outlook.com / Hotmail / Microsoft 365) com início de sessão OAuth 2.0.
 * A Microsoft já não aceita utilizador+palavra-passe por SMTP. Aqui usa-se o "device code flow"
 * (o utilizador escreve um código em microsoft.com/link) e a API Microsoft Graph para enviar.
 */
object MsMailer {
    private const val AUTH = "https://login.microsoftonline.com/common/oauth2/v2.0"
    private const val SCOPE = "https://graph.microsoft.com/Mail.Send https://graph.microsoft.com/User.Read offline_access"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun readBody(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return try { if (text.isBlank()) JSONObject() else JSONObject(text) } catch (e: Exception) { JSONObject().put("raw", text) }
    }

    private fun postForm(url: String, form: Map<String, String>): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = form.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return readBody(conn)
    }

    private fun errText(j: JSONObject): String =
        j.optString("error_description", j.optString("error", "erro desconhecido")).lines().firstOrNull() ?: "erro"

    /** Pede o código que o utilizador tem de escrever em microsoft.com/link. */
    fun deviceStart(clientId: String): JSONObject {
        val j = postForm("$AUTH/devicecode", mapOf("client_id" to clientId, "scope" to SCOPE))
        if (!j.has("device_code")) throw Exception(errText(j))
        return j
    }

    /** Espera (a fazer polling) que o utilizador conclua o início de sessão. Devolve {refresh, user}. */
    fun devicePoll(clientId: String, start: JSONObject): JSONObject {
        val deviceCode = start.getString("device_code")
        var interval = start.optInt("interval", 5).coerceAtLeast(3)
        val deadline = System.currentTimeMillis() + start.optInt("expires_in", 900) * 1000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L)
            val j = postForm("$AUTH/token", mapOf(
                "client_id" to clientId,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "device_code" to deviceCode
            ))
            if (j.has("access_token")) {
                val access = j.getString("access_token")
                val refresh = j.optString("refresh_token", "")
                if (refresh.isEmpty()) throw Exception("A Microsoft não devolveu autorização permanente (offline_access).")
                return JSONObject().put("refresh", refresh).put("user", whoAmI(access))
            }
            when (j.optString("error")) {
                "authorization_pending" -> {}
                "slow_down" -> interval += 5
                else -> throw Exception(errText(j))
            }
        }
        throw Exception("O código expirou. Tenta de novo.")
    }

    private fun whoAmI(access: String): String = try {
        val conn = URL("https://graph.microsoft.com/v1.0/me").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Authorization", "Bearer $access")
        val j = readBody(conn)
        j.optString("mail", "").ifBlank { j.optString("userPrincipalName", "") }
    } catch (e: Exception) { "" }

    /**
     * Obtém um access token a partir do refresh token. A Microsoft devolve um refresh token novo de cada vez;
     * guardamo-lo localmente (ligado ao token do ficheiro JSON de onde veio) e a app copia-o para o JSON ao abrir.
     */
    private fun accessToken(ctx: Context, clientId: String, jsonRefresh: String): String {
        val p = ctx.getSharedPreferences("garagem_ms", Context.MODE_PRIVATE)
        val useRefresh = if (p.getString("base", "") == jsonRefresh && !p.getString("cur", "").isNullOrEmpty())
            p.getString("cur", "")!! else jsonRefresh
        val j = postForm("$AUTH/token", mapOf(
            "client_id" to clientId, "grant_type" to "refresh_token",
            "refresh_token" to useRefresh, "scope" to SCOPE
        ))
        if (!j.has("access_token")) {
            if (j.optString("error") == "invalid_grant")
                throw Exception("A sessão Microsoft expirou. Abre ✉️ Emails de alerta e inicia sessão de novo.")
            throw Exception(errText(j))
        }
        val nr = j.optString("refresh_token", "")
        if (nr.isNotEmpty()) p.edit().putString("base", jsonRefresh).putString("cur", nr).apply()
        return j.getString("access_token")
    }

    /** Refresh token mais recente guardado localmente para este token do JSON ("" se não houver). */
    fun currentRefresh(ctx: Context, jsonRefresh: String): String {
        val p = ctx.getSharedPreferences("garagem_ms", Context.MODE_PRIVATE)
        return if (p.getString("base", "") == jsonRefresh) p.getString("cur", "") ?: "" else ""
    }

    fun send(ctx: Context, clientId: String, jsonRefresh: String, to: List<String>, subject: String, body: String) {
        if (to.isEmpty()) throw Exception("Sem destinatários")
        val access = accessToken(ctx, clientId, jsonRefresh)
        val rec = JSONArray()
        to.forEach { rec.put(JSONObject().put("emailAddress", JSONObject().put("address", it.trim()))) }
        val msg = JSONObject()
            .put("subject", subject)
            .put("body", JSONObject().put("contentType", "Text").put("content", body))
            .put("toRecipients", rec)
        val payload = JSONObject().put("message", msg).put("saveToSentItems", true)

        val conn = URL("https://graph.microsoft.com/v1.0/me/sendMail").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("Authorization", "Bearer $access")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val j = readBody(conn)
            val m = j.optJSONObject("error")?.optString("message") ?: "HTTP $code"
            throw Exception("Microsoft: $m")
        }
    }
}
