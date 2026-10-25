package pt.garagem.app

import org.json.JSONObject
import java.util.Date
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/** Dados do servidor SMTP. Vêm do ficheiro JSON (settings.smtp), para funcionarem em qualquer telemóvel. */
class SmtpCfg(
    val host: String, val port: Int, val security: String,
    val user: String, val pass: String, val from: String
) {
    fun ok(): Boolean = host.isNotBlank() && user.isNotBlank() && pass.isNotEmpty()

    companion object {
        fun fromJson(o: JSONObject?): SmtpCfg? {
            if (o == null) return null
            return SmtpCfg(
                o.optString("host", "").trim(), o.optInt("port", 465), o.optString("security", "ssl"),
                o.optString("user", "").trim(), o.optString("pass", ""), o.optString("from", "").trim()
            )
        }
    }
}

/** Envio de emails por SMTP directamente a partir do telemóvel (conta do próprio utilizador). */
object Mailer {
    /** Envia uma mensagem de texto para todos os destinatários de uma só vez. Lança excepção se falhar. */
    fun send(cfg: SmtpCfg, to: List<String>, subject: String, body: String) {
        if (!cfg.ok()) throw Exception("Servidor de email por configurar")
        if (to.isEmpty()) throw Exception("Sem destinatários")
        val from = cfg.from.ifBlank { cfg.user }

        Thread.currentThread().contextClassLoader = Mailer::class.java.classLoader

        val props = Properties()
        props["mail.smtp.host"] = cfg.host
        props["mail.smtp.port"] = cfg.port.toString()
        props["mail.smtp.auth"] = "true"
        props["mail.smtp.connectiontimeout"] = "15000"
        props["mail.smtp.timeout"] = "20000"
        props["mail.smtp.writetimeout"] = "20000"
        if (cfg.security == "ssl") {
            props["mail.smtp.ssl.enable"] = "true"
        } else {
            props["mail.smtp.starttls.enable"] = "true"
            props["mail.smtp.starttls.required"] = "true"
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(cfg.user, cfg.pass)
        })
        val msg = MimeMessage(session)
        msg.setFrom(InternetAddress(from, "RLTEC Ativos", "UTF-8"))
        msg.setRecipients(Message.RecipientType.TO, to.map { InternetAddress(it.trim()) }.toTypedArray())
        msg.setSubject(subject, "UTF-8")
        msg.setText(body, "UTF-8")
        msg.sentDate = Date()
        Transport.send(msg)
    }
}
