package ru.vdl.catalog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.activation.DataHandler
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

object Archive {

    fun dir(ctx: Context): File = File(ctx.filesDir, "archives").apply { mkdirs() }

    fun json(items: List<Item>): String {
        val arr = JSONArray()
        for (i in items) {
            arr.put(JSONObject().apply {
                put("code", i.code)
                put("type", i.type)
                put("group", i.group)
                put("subgroup", i.subgroup)
                put("name", i.name)
                put("comment", i.comment)
                put("created_at", Fmt.iso(i.createdAt))
                put("updated_at", Fmt.iso(i.updatedAt))
                put("created_at_ms", i.createdAt)
                put("updated_at_ms", i.updatedAt)
            })
        }
        return JSONObject().apply {
            put("format", "catalog-v2")
            put("exported_at", Fmt.iso(System.currentTimeMillis()))
            put("count", items.size)
            put("records", arr)
        }.toString(2)
    }

    /** CSV: разделитель «;», UTF-8 с BOM, кавычки удваиваются. */
    fun csv(items: List<Item>): String {
        fun q(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""
        val sb = StringBuilder("\uFEFF")
        sb.append(listOf("Код", "Тип", "Группа", "Подгруппа", "Название", "Комментарий", "Создано", "Изменено")
            .joinToString(";") { q(it) }).append("\r\n")
        for (i in items) {
            sb.append(listOf(i.code, i.type, i.group, i.subgroup, i.name, i.comment,
                Fmt.human(i.createdAt), Fmt.human(i.updatedAt)).joinToString(";") { q(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /** Собирает zip-архив (JSON + CSV) в внутреннюю папку archives. */
    fun build(ctx: Context): File {
        val items = Db.get(ctx).all()
        val stamp = Fmt.stamp()
        val file = File(dir(ctx), "catalog-$stamp.zip")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("catalog-$stamp.json"))
            zip.write(json(items).toByteArray(Charsets.UTF_8)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("catalog-$stamp.csv"))
            zip.write(csv(items).toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
        prune(ctx)
        return file
    }

    fun prune(ctx: Context) {
        val keep = Prefs(ctx).keepArchives.coerceAtLeast(1)
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".zip") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(keep).forEach { it.delete() }
    }

    fun parse(text: String): List<Item> {
        val root = JSONTokenerSafe(text) ?: throw IllegalArgumentException("файл не является JSON")
        val arr: JSONArray = when {
            root is JSONArray -> root
            root is JSONObject && root.has("records") -> root.getJSONArray("records")
            root is JSONObject && root.has("items") -> root.getJSONArray("items")
            else -> throw IllegalArgumentException("не найден список записей")
        }
        val out = ArrayList<Item>()
        for (n in 0 until arr.length()) {
            val o = arr.optJSONObject(n) ?: continue
            val code = (o.optString("code").ifBlank { o.optString("barcode") }).trim()
            if (code.isBlank()) continue
            // Старый формат: единственное поле «group» переносим в Подгруппу.
            val hasNew = o.has("subgroup") || o.has("type")
            val g = o.optString("group").trim()
            out.add(Item(
                code = code,
                type = o.optString("type").trim(),
                group = if (hasNew) g else "",
                subgroup = if (hasNew) o.optString("subgroup").trim() else g,
                name = o.optString("name").trim(),
                comment = o.optString("comment").trim(),
                createdAt = ms(o, "created_at_ms", "created_at", "date"),
                updatedAt = ms(o, "updated_at_ms", "updated_at", "date")
            ))
        }
        return out
    }

    private fun ms(o: JSONObject, msKey: String, vararg isoKeys: String): Long {
        if (o.has(msKey)) { val v = o.optLong(msKey, 0); if (v > 0) return v }
        for (k in isoKeys) {
            val s = o.optString(k, "").trim()
            if (s.isBlank()) continue
            val t = parseIso(s)
            if (t > 0) return t
        }
        return 0
    }

    private fun parseIso(s: String): Long {
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")
        for (p in patterns) {
            try {
                val f = java.text.SimpleDateFormat(p, java.util.Locale.US)
                f.isLenient = false
                return f.parse(s.take(p.length + 2))?.time ?: continue
            } catch (_: Exception) { }
        }
        return 0
    }

    private fun JSONTokenerSafe(text: String): Any? = try {
        org.json.JSONTokener(text).nextValue()
    } catch (_: Exception) { null }

    /** Отправка архива по SMTP. Бросает исключение с понятным текстом при ошибке. */
    fun sendByEmail(ctx: Context, file: File) {
        val p = Prefs(ctx)
        if (!p.smtpConfigured) throw IllegalStateException("не заполнены настройки SMTP или адрес получателя")
        val props = Properties().apply {
            put("mail.smtp.host", p.smtpHost)
            put("mail.smtp.port", p.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "30000")
            put("mail.smtp.writetimeout", "30000")
            if (p.smtpSsl) {
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                put("mail.smtp.socketFactory.port", p.smtpPort.toString())
            } else {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(p.smtpUser, p.smtpPass)
        })
        val from = p.mailFrom.ifBlank { p.smtpUser }
        val count = Db.get(ctx).count()
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(from))
            p.mailTo.split(Regex("[,;\\s]+")).filter { it.contains("@") }.forEach {
                addRecipient(Message.RecipientType.TO, InternetAddress(it))
            }
            subject = MimeUtilitySafe("Архив каталога предметов — ${Fmt.human(System.currentTimeMillis())}")
            val body = MimeBodyPart().apply {
                setText(
                    "Автоматический архив базы каталога предметов.\n\n" +
                    "Записей: $count\nФайл: ${file.name}\nРазмер: ${file.length() / 1024} КБ\n" +
                    "Дата: ${Fmt.human(System.currentTimeMillis())}\n\n" +
                    "Внутри архива: JSON (для восстановления в приложении) и CSV с разделителем «;» в UTF-8 с BOM.",
                    "UTF-8"
                )
            }
            val att = MimeBodyPart().apply {
                dataHandler = DataHandler(ByteArrayDataSource(file.readBytes(), "application/zip"))
                fileName = file.name
            }
            setContent(MimeMultipart().apply { addBodyPart(body); addBodyPart(att) })
        }
        Transport.send(msg)
    }

    private fun MimeUtilitySafe(s: String): String = try {
        javax.mail.internet.MimeUtility.encodeText(s, "UTF-8", "B")
    } catch (_: Exception) { s }
}
