package ru.vdl.catalog

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import ru.vdl.catalog.databinding.ActivitySettingsBinding
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var p: Prefs

    private val periods = listOf(
        "Выключено" to 0,
        "Каждый час" to 1,
        "Каждые 6 часов" to 6,
        "Каждые 12 часов" to 12,
        "Раз в сутки" to 24,
        "Раз в 3 дня" to 72,
        "Раз в неделю" to 168,
        "Раз в 2 недели" to 336,
        "Раз в месяц (30 дней)" to 720
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        p.applyTheme()
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.periodInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, periods.map { it.first }))
        b.periodInput.setOnItemClickListener { _, _, pos, _ -> b.customHours.setText(periods[pos].second.toString()) }

        when (p.theme) {
            "light" -> b.themeLight.isChecked = true
            "dark" -> b.themeDark.isChecked = true
            else -> b.themeSystem.isChecked = true
        }
        b.themeGroup.setOnCheckedChangeListener { _, id ->
            p.theme = when (id) {
                R.id.themeLight -> "light"
                R.id.themeDark -> "dark"
                else -> "system"
            }
            p.applyTheme()
        }

        b.customHours.setText(p.periodHours.toString())
        b.periodInput.setText(periods.firstOrNull { it.second == p.periodHours }?.first ?: "Свой период", false)
        b.keepInput.setText(p.keepArchives.toString())
        b.emailSwitch.isChecked = p.emailEnabled
        b.hostInput.setText(p.smtpHost)
        b.portInput.setText(p.smtpPort.toString())
        b.sslSwitch.isChecked = p.smtpSsl
        b.userInput.setText(p.smtpUser)
        b.passInput.setText(p.smtpPass)
        b.fromInput.setText(p.mailFrom)
        b.toInput.setText(p.mailTo)
        b.sslSwitch.setOnCheckedChangeListener { _, on ->
            b.sslSwitch.text = if (on) "SSL (465)" else "STARTTLS (587)"
            val port = b.portInput.text.toString().toIntOrNull()
            if (port == null || port == 465 || port == 587) b.portInput.setText(if (on) "465" else "587")
        }
        b.sslSwitch.text = if (p.smtpSsl) "SSL (465)" else "STARTTLS (587)"

        b.saveBtn.setOnClickListener { save(true) }
        b.testBtn.setOnClickListener { save(false); sendNow() }
        b.shareBtn.setOnClickListener { share() }
        updateStatus()
    }

    private fun save(notify: Boolean) {
        p.periodHours = (b.customHours.text.toString().toIntOrNull() ?: 0).coerceIn(0, 8760)
        p.keepArchives = (b.keepInput.text.toString().toIntOrNull() ?: 10).coerceIn(1, 200)
        p.emailEnabled = b.emailSwitch.isChecked
        p.smtpHost = b.hostInput.text.toString()
        p.smtpPort = b.portInput.text.toString().toIntOrNull() ?: 465
        p.smtpSsl = b.sslSwitch.isChecked
        p.smtpUser = b.userInput.text.toString()
        p.smtpPass = b.passInput.text.toString()
        p.mailFrom = b.fromInput.text.toString()
        p.mailTo = b.toInput.text.toString()
        b.customHours.setText(p.periodHours.toString())
        b.periodInput.setText(periods.firstOrNull { it.second == p.periodHours }?.first ?: "Свой период: ${p.periodHours} ч", false)
        ArchiveWorker.reschedule(this)
        Archive.prune(this)
        if (notify) Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun updateStatus() {
        val n = Db.get(this).count()
        val period = if (p.periodHours <= 0) "плановая архивация выключена" else "период: каждые ${p.periodHours} ч"
        val last = if (p.lastArchiveAt > 0) "\nПоследний архив: ${Fmt.human(p.lastArchiveAt)}\n${p.lastArchiveInfo}" else "\nАрхивов ещё не было"
        val files = Archive.dir(this).listFiles()?.size ?: 0
        b.statusLine.text = "Записей в каталоге: $n · $period · архивов на устройстве: $files$last"
    }

    private fun sendNow() {
        Toast.makeText(this, "Создаю архив…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val f = Archive.build(this)
                var msg = "Архив создан: ${f.name}"
                if (p.smtpConfigured) { Archive.sendByEmail(this, f); msg += "\nОтправлен на ${p.mailTo}" }
                else msg += "\nSMTP не настроен — письмо не отправлено"
                p.lastArchiveAt = System.currentTimeMillis()
                p.lastArchiveInfo = msg.replace("\n", ", ")
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); updateStatus() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun share() {
        thread {
            try {
                val f = Archive.build(this)
                val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Архив каталога предметов ${Fmt.human(System.currentTimeMillis())}")
                    if (p.mailTo.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(p.mailTo))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runOnUiThread { startActivity(Intent.createChooser(i, "Отправить архив")) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
