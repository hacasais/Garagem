package pt.garagem.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import java.util.TimeZone

/**
 * Corre em segundo plano (agendado pelo WorkManager), mesmo com a app fechada.
 * Lê o mesmo ficheiro de dados que a app usa, calcula os avisos do plano de
 * manutenção e dos avisos manuais, e envia uma notificação se algo estiver
 * a vencer em breve ou já passado.
 */
class AlertWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    // Todos os avisos considerados nesta execução (para o email, que tem as suas próprias datas de aviso)
    private class Cand(
        val key: String, val vehicle: String, val title: String, val dateStr: String, val kmTarget: Double,
        val days: Long?, val kmLeft: Double?, val unitSuf: String, val pref: JSONObject?
    )
    private val cands = mutableListOf<Cand>()

    private class Cfg(val on: Boolean, val leadDays: Double, val leadKm: Double, val repeatDays: Int, val hour: Int)

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("garagem", Context.MODE_PRIVATE)
        val uriStr = prefs.getString("uri", null) ?: return Result.success()
        val uri = try { Uri.parse(uriStr) } catch (e: Exception) { return Result.success() }

        val text = try {
            applicationContext.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (e: Exception) { null } ?: return Result.success()

        val json = try { JSONObject(text) } catch (e: Exception) { return Result.success() }
        if (json.optString("app") != "garagem") return Result.success()
        val vehicles = json.optJSONArray("vehicles") ?: return Result.success()

        // Definições gerais de alarmes (guardadas pela app dentro do ficheiro)
        val s = json.optJSONObject("settings")?.optJSONObject("alerts")
        val cfg = Cfg(
            on = s?.optBoolean("on", true) ?: true,
            leadDays = s?.optDouble("leadDays", 14.0) ?: 14.0,
            leadKm = s?.optDouble("leadKm", 500.0) ?: 500.0,
            repeatDays = maxOf(1, s?.optInt("repeatDays", 1) ?: 1),
            hour = (s?.optInt("hour", 9) ?: 9).coerceIn(0, 23)
        )
        val now = Calendar.getInstance()
        val nowHour = now.get(Calendar.HOUR_OF_DAY)
        val emailCfg = s?.optJSONObject("email")
        val smtpCfg = SmtpCfg.fromJson(json.optJSONObject("settings")?.optJSONObject("smtp"))

        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val todayStr = String.format("%04d-%02d-%02d", today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH))
        val due = mutableListOf<Pair<String, String>>()   // (chave, texto)

        for (i in 0 until vehicles.length()) {
            val v = vehicles.getJSONObject(i)
            val vid = v.optString("id", "v$i")
            val name = v.optString("name", "Viatura")
            val curKm = v.optDouble("km", 0.0)
            val unitSuf = when (v.optString("unit", "km")) { "horas" -> "h"; "none" -> ""; else -> "km" }
            val aPrefs = v.optJSONObject("alertPrefs")

            v.optJSONArray("alerts")?.let { alerts ->
                for (j in 0 until alerts.length()) {
                    val a = alerts.getJSONObject(j)
                    val aid = a.optString("id", "a$j")
                    checkAndAdd(due, "$vid|$aid", name, a.optString("title"), a.optString("date", ""), a.optDouble("km", 0.0), curKm, today, todayStr, aPrefs?.optJSONObject(aid), cfg, unitSuf)
                }
            }

            val records = v.optJSONArray("records")
            v.optJSONArray("plan")?.let { plan ->
                for (j in 0 until plan.length()) {
                    val p = plan.getJSONObject(j)
                    val type = p.optString("type")
                    val pid = "plan:" + p.optString("id", "p$j")
                    var lastDate = p.optString("lastDate", "")
                    var lastKm = p.optDouble("lastKm", 0.0)

                    if (records != null) {
                        var bestDate = ""
                        var bestKm = 0.0
                        var found = false
                        for (k in 0 until records.length()) {
                            val r = records.getJSONObject(k)
                            if (recMatches(r, type)) {
                                val rd = r.optString("date", "")
                                if (!found || rd >= bestDate) { bestDate = rd; bestKm = r.optDouble("km", 0.0); found = true }
                            }
                        }
                        if (found && (lastDate.isEmpty() || bestDate >= lastDate)) { lastDate = bestDate; lastKm = bestKm }
                    }

                    var dateDue = ""
                    val months = p.optDouble("months", 0.0)
                    if (months > 0 && lastDate.isNotEmpty()) {
                        parseDate(lastDate)?.let { cal ->
                            cal.add(Calendar.MONTH, months.toInt())
                            dateDue = String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
                        }
                    }
                    var kmDue = 0.0
                    val kmInterval = p.optDouble("km", 0.0)
                    if (kmInterval > 0 && lastKm > 0) kmDue = lastKm + kmInterval

                    // Data limite fixa (ex.: IUC): usa a data guardada, não a do último registo
                    if (p.optBoolean("fixed", false) && p.optString("nextDue", "").isNotEmpty() && months > 0) {
                        dateDue = p.optString("nextDue")
                        kmDue = 0.0
                    }

                    // Nova data / novo valor definidos manualmente na app (valem até haver novo registo)
                    val pp = aPrefs?.optJSONObject(pid)
                    val ovDate = pp?.optString("date", "") ?: ""
                    val ovKm = pp?.optDouble("km", 0.0) ?: 0.0
                    if (ovDate.isNotEmpty()) dateDue = ovDate
                    if (ovKm > 0) kmDue = ovKm

                    checkAndAdd(due, "$vid|$pid", name, type, dateDue, kmDue, curKm, today, todayStr, pp, cfg, unitSuf)
                }
            }
        }

        if (cfg.on && nowHour >= cfg.hour) runNotifications(due, cfg)
        try { processEmails(emailCfg, smtpCfg, nowHour, today, todayStr) } catch (e: Exception) { }
        return Result.success()
    }

    private fun runNotifications(due: List<Pair<String, String>>, cfg: Cfg) {
        // Lembretes: cada aviso é (re)notificado no máximo de X em X dias.
        val ctx = applicationContext
        val nPrefs = ctx.getSharedPreferences("garagem_notified", Context.MODE_PRIVATE)
        val epochDay = (System.currentTimeMillis() + TimeZone.getDefault().getOffset(System.currentTimeMillis())) / 86400000L
        val keys = due.map { it.first }.toSet()

        // Esquece avisos que já não estão ativos (concluídos, adiados, fora da janela) para voltarem a notificar quando reaparecerem.
        val editor = nPrefs.edit()
        for (k in nPrefs.all.keys) if (k !in keys) editor.remove(k)

        if (due.isEmpty()) {
            editor.apply()
            try { NotificationManagerCompat.from(ctx).cancel(1001) } catch (e: Exception) { }
            return
        }

        val needNotify = due.any { epochDay - nPrefs.getLong(it.first, -100000L) >= cfg.repeatDays }
        if (needNotify) {
            notify(due.map { it.second })
            for (k in keys) editor.putLong(k, epochDay)
        }
        editor.apply()
    }

    // Avisa quando está vencido ou dentro da antecedência definida (geral ou do próprio aviso).
    // Ignora avisos adiados (snoozeUntil no futuro) e silenciados (mute).
    private fun checkAndAdd(
        due: MutableList<Pair<String, String>>, key: String, vehicle: String, title: String,
        dateStr: String, kmTarget: Double, curKm: Double, today: Calendar, todayStr: String,
        pref: JSONObject?, cfg: Cfg, unitSuf: String
    ) {
        if (pref != null) {
            if (pref.optBoolean("mute", false)) return
            val sn = pref.optString("snoozeUntil", "")
            if (sn.isNotEmpty() && sn > todayStr) return
        }
        val leadDays = if (pref != null && pref.has("leadDays")) pref.optDouble("leadDays", cfg.leadDays) else cfg.leadDays
        val leadKm = if (pref != null && pref.has("leadKm")) pref.optDouble("leadKm", cfg.leadKm) else cfg.leadKm

        var days: Long? = null
        var kmLeft: Double? = null
        if (dateStr.isNotEmpty()) parseDate(dateStr)?.let { days = daysBetween(today, it) }
        if (kmTarget > 0 && unitSuf.isNotEmpty()) kmLeft = kmTarget - curKm

        val d = days
        val k = kmLeft
        cands.add(Cand(key, vehicle, title, dateStr, kmTarget, d, k, unitSuf, pref))
        val active = (d != null && d <= leadDays) || (k != null && k <= leadKm)
        if (!active) return

        val parts = mutableListOf<String>()
        if (d != null) parts.add(if (d < 0) "há ${-d} dias" else if (d == 0L) "hoje" else "em $d dias")
        if (k != null) parts.add(if (k <= 0) "já passou" else "faltam ${k.toInt()} $unitSuf")
        due.add(Pair(key, "$vehicle — $title (${parts.joinToString(" · ")})"))
    }

    // Um registo cobre um tipo se for desse tipo ou se uma das suas ações tiver esse nome
    private fun recMatches(r: JSONObject, type: String): Boolean {
        val want = type.trim().lowercase()
        if (r.optString("type").trim().lowercase() == want) return true
        val items = r.optJSONArray("items") ?: return false
        for (i in 0 until items.length()) {
            val n = items.optJSONObject(i)?.optString("name", "") ?: ""
            if (n.trim().lowercase() == want) return true
        }
        return false
    }

    private fun jsonInts(arr: JSONArray?, def: List<Int>): List<Int> {
        if (arr == null) return def
        val out = mutableListOf<Int>()
        for (i in 0 until arr.length()) out.add(arr.optInt(i))
        return out
    }

    private fun fmtDate(s: String): String {
        val p = s.split("-")
        return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else s
    }

    private fun setEmailStatus(msg: String) {
        val stamp = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "PT")).format(Date())
        applicationContext.getSharedPreferences("garagem_email_status", Context.MODE_PRIVATE)
            .edit().putString("last", "$stamp — $msg").apply()
    }

    /**
     * Emails de alerta. Datas de aviso configuráveis pelo utilizador:
     *  - dias antes (lista geral, ou lista própria de cada aviso),
     *  - km/horas que faltam (lista geral),
     *  - repetição quando vencido (de X em X dias),
     *  - datas extra em que se quer um aviso (por aviso).
     * Cada limiar só é enviado uma vez por aviso e por data limite; se a data mudar, recomeça.
     * Todos os avisos que disparam na mesma execução vão num só email, para todos os destinatários.
     */
    private fun processEmails(e: JSONObject?, smtp: SmtpCfg?, nowHour: Int, today: Calendar, todayStr: String) {
        if (e == null || !e.optBoolean("on", false)) return
        if (nowHour < e.optInt("hour", 8).coerceIn(0, 23)) return
        val ctx = applicationContext
        val toArr = e.optJSONArray("to")
        val to = mutableListOf<String>()
        if (toArr != null) for (i in 0 until toArr.length()) { val a = toArr.optString(i).trim(); if (a.contains("@")) to.add(a) }
        if (to.isEmpty() || smtp == null || !smtp.ok()) return

        val gDays = jsonInts(e.optJSONArray("days"), listOf(30, 14, 7, 3, 1, 0))
        val gKm = jsonInts(e.optJSONArray("km"), listOf(1000, 500, 100, 0))
        val overdueEvery = e.optInt("overdueEvery", 7).coerceAtLeast(0)

        val sent = ctx.getSharedPreferences("garagem_emailed", Context.MODE_PRIVATE)
        val lines = mutableListOf<String>()
        val marks = mutableSetOf<String>()
        val bases = mutableSetOf<String>()

        for (c in cands) {
            val pref = c.pref
            if (pref != null && pref.optBoolean("noEmail", false)) continue
            val base = "${c.key}|${c.dateStr}|${c.kmTarget.toLong()}"
            bases.add(base)
            val newMarks = mutableListOf<String>()
            var fire = false

            val dl = c.days
            if (dl != null) {
                val list = jsonInts(pref?.optJSONArray("emailDays"), gDays)
                val trig = list.filter { dl <= it }
                if (trig.isNotEmpty()) {
                    if (!sent.contains("$base|d${trig.min()}")) fire = true
                    trig.forEach { newMarks.add("$base|d$it") }
                }
                if (dl < 0 && overdueEvery > 0) {
                    val kk = "$base|o${(-dl) / overdueEvery}"
                    if (!sent.contains(kk)) fire = true
                    newMarks.add(kk)
                }
            }
            val kl = c.kmLeft
            if (kl != null) {
                val trig = gKm.filter { kl <= it }
                if (trig.isNotEmpty()) {
                    if (!sent.contains("$base|k${trig.min()}")) fire = true
                    trig.forEach { newMarks.add("$base|k$it") }
                }
            }
            val extra = pref?.optJSONArray("emailDates")
            if (extra != null) {
                for (i in 0 until extra.length()) {
                    val ds = extra.optString(i)
                    val dd = parseDate(ds) ?: continue
                    val ago = daysBetween(dd, today)
                    if (ago >= 0 && ago <= 3) {
                        val kk = "$base|x$ds"
                        if (!sent.contains(kk)) fire = true
                        newMarks.add(kk)
                    }
                }
            }
            if (!fire) continue

            val parts = mutableListOf<String>()
            if (dl != null) {
                val st = if (dl < 0) "vencido há ${-dl} dias" else if (dl == 0L) "vence hoje" else "vence em $dl dias"
                parts.add(st + " (" + fmtDate(c.dateStr) + ")")
            }
            if (kl != null) parts.add(if (kl <= 0) "já passou ${c.kmTarget.toLong()} ${c.unitSuf}" else "faltam ${kl.toLong()} ${c.unitSuf}")
            lines.add("• ${c.vehicle} — ${c.title}: ${parts.joinToString(" · ")}")
            marks.addAll(newMarks)
        }

        // Esquece marcas de avisos que já não existem ou cuja data mudou
        val ed = sent.edit()
        for (kk in sent.all.keys) if (bases.none { kk.startsWith("$it|") }) ed.remove(kk)
        ed.apply()
        if (lines.isEmpty()) return

        val subject = if (lines.size == 1) "RLTEC Ativos: 1 aviso de manutenção" else "RLTEC Ativos: ${lines.size} avisos de manutenção"
        val body = "Avisos de manutenção:\n\n" + lines.joinToString("\n") + "\n\nEnviado automaticamente pela app RLTEC Ativos."
        try {
            Mailer.send(ctx, smtp, to, subject, body)
            val ed2 = sent.edit()
            marks.forEach { ed2.putLong(it, System.currentTimeMillis()) }
            ed2.apply()
            setEmailStatus("enviado: ${lines.size} aviso(s) para ${to.size} destinatário(s)")
        } catch (ex: Exception) {
            setEmailStatus("ERRO ao enviar: ${ex.message ?: ex.toString()}")
        }
    }

    private fun notify(items: List<String>) {
        val ctx = applicationContext
        val channelId = "garagem_avisos"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Avisos de manutenção", NotificationManager.IMPORTANCE_DEFAULT)
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = items.joinToString("\n")
        val builder = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (items.size == 1) "1 aviso de manutenção" else "${items.size} avisos de manutenção")
            .setContentText(items.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)

        if (Build.VERSION.SDK_INT < 33 ||
            ActivityCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            try { NotificationManagerCompat.from(ctx).notify(1001, builder.build()) } catch (e: SecurityException) { }
        }
    }

    private fun parseDate(s: String): Calendar? {
        val parts = s.split("-")
        if (parts.size != 3) return null
        return try {
            val y = parts[0].toInt(); val m = parts[1].toInt(); val d = parts[2].toInt()
            Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0, 0) }
        } catch (e: Exception) { null }
    }

    private fun daysBetween(from: Calendar, to: Calendar): Long =
        Math.round((to.timeInMillis - from.timeInMillis) / 86400000.0)
}
