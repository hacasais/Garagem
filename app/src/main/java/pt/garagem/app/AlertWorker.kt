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
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * Corre em segundo plano (agendado pelo WorkManager), mesmo com a app fechada.
 * Lê o mesmo ficheiro de dados que a app usa, calcula os avisos do plano de
 * manutenção e dos avisos manuais, e envia uma notificação se algo estiver
 * a vencer em breve ou já passado.
 */
class AlertWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

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
        if (!cfg.on) return Result.success()

        val now = Calendar.getInstance()
        if (now.get(Calendar.HOUR_OF_DAY) < cfg.hour) return Result.success()

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
            val aPrefs = v.optJSONObject("alertPrefs")

            v.optJSONArray("alerts")?.let { alerts ->
                for (j in 0 until alerts.length()) {
                    val a = alerts.getJSONObject(j)
                    val aid = a.optString("id", "a$j")
                    checkAndAdd(due, "$vid|$aid", name, a.optString("title"), a.optString("date", ""), a.optDouble("km", 0.0), curKm, today, todayStr, aPrefs?.optJSONObject(aid), cfg)
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
                            if (r.optString("type").trim().lowercase() == type.trim().lowercase()) {
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

                    // Nova data / novo valor definidos manualmente na app (valem até haver novo registo)
                    val pp = aPrefs?.optJSONObject(pid)
                    val ovDate = pp?.optString("date", "") ?: ""
                    val ovKm = pp?.optDouble("km", 0.0) ?: 0.0
                    if (ovDate.isNotEmpty()) dateDue = ovDate
                    if (ovKm > 0) kmDue = ovKm

                    checkAndAdd(due, "$vid|$pid", name, type, dateDue, kmDue, curKm, today, todayStr, pp, cfg)
                }
            }
        }

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
            return Result.success()
        }

        val needNotify = due.any { epochDay - nPrefs.getLong(it.first, -100000L) >= cfg.repeatDays }
        if (needNotify) {
            notify(due.map { it.second })
            for (k in keys) editor.putLong(k, epochDay)
        }
        editor.apply()
        return Result.success()
    }

    // Avisa quando está vencido ou dentro da antecedência definida (geral ou do próprio aviso).
    // Ignora avisos adiados (snoozeUntil no futuro) e silenciados (mute).
    private fun checkAndAdd(
        due: MutableList<Pair<String, String>>, key: String, vehicle: String, title: String,
        dateStr: String, kmTarget: Double, curKm: Double, today: Calendar, todayStr: String,
        pref: JSONObject?, cfg: Cfg
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
        if (kmTarget > 0) kmLeft = kmTarget - curKm

        val d = days
        val k = kmLeft
        val active = (d != null && d <= leadDays) || (k != null && k <= leadKm)
        if (!active) return

        val parts = mutableListOf<String>()
        if (d != null) parts.add(if (d < 0) "há ${-d} dias" else if (d == 0L) "hoje" else "em $d dias")
        if (k != null) parts.add(if (k <= 0) "já passou" else "faltam ${k.toInt()} km")
        due.add(Pair(key, "$vehicle — $title (${parts.joinToString(" · ")})"))
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
