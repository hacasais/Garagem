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

/**
 * Corre em segundo plano (agendado pelo WorkManager), mesmo com a app fechada.
 * Lê o mesmo ficheiro de dados que a app usa, calcula os avisos do plano de
 * manutenção e dos avisos manuais, e envia uma notificação se algo estiver
 * a vencer em breve ou já passado.
 */
class AlertWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

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

        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val due = mutableListOf<String>()

        for (i in 0 until vehicles.length()) {
            val v = vehicles.getJSONObject(i)
            val name = v.optString("name", "Viatura")
            val curKm = v.optDouble("km", 0.0)

            v.optJSONArray("alerts")?.let { alerts ->
                for (j in 0 until alerts.length()) {
                    val a = alerts.getJSONObject(j)
                    checkAndAdd(due, name, a.optString("title"), a.optString("date", ""), a.optDouble("km", 0.0), curKm, today)
                }
            }

            val records = v.optJSONArray("records")
            v.optJSONArray("plan")?.let { plan ->
                for (j in 0 until plan.length()) {
                    val p = plan.getJSONObject(j)
                    val type = p.optString("type")
                    var lastDate = p.optString("lastDate", "")
                    var lastKm = p.optDouble("lastKm", 0.0)

                    if (records != null) {
                        var bestDate = ""
                        var bestKm = 0.0
                        var found = false
                        for (k in 0 until records.length()) {
                            val r = records.getJSONObject(k)
                            if (r.optString("type") == type) {
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

                    checkAndAdd(due, name, type, dateDue, kmDue, curKm, today)
                }
            }
        }

        if (due.isNotEmpty()) notify(due)
        return Result.success()
    }

    // Só avisa quando está mesmo perto: vencido, a menos de 14 dias, ou a menos de 500 km.
    private fun checkAndAdd(due: MutableList<String>, vehicle: String, title: String, dateStr: String, kmTarget: Double, curKm: Double, today: Calendar) {
        var days: Long? = null
        var kmLeft: Double? = null
        if (dateStr.isNotEmpty()) parseDate(dateStr)?.let { days = daysBetween(today, it) }
        if (kmTarget > 0) kmLeft = kmTarget - curKm

        val overdue = (days != null && days!! < 0) || (kmLeft != null && kmLeft!! <= 0)
        val soon = (days != null && days!! in 0L..14L) || (kmLeft != null && kmLeft!! in 0.0..500.0)
        if (!overdue && !soon) return

        val parts = mutableListOf<String>()
        days?.let { d -> parts.add(if (d < 0) "há ${-d} dias" else if (d == 0L) "hoje" else "em $d dias") }
        kmLeft?.let { k -> parts.add(if (k <= 0) "já passou" else "faltam ${k.toInt()} km") }
        due.add("$vehicle — $title (${parts.joinToString(" · ")})")
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
