package ru.vdl.catalog

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("catalog_prefs", Context.MODE_PRIVATE)

    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) { sp.edit().putString("theme", v).apply() }

    /** Период плановой архивации в часах; 0 — выключено. */
    var periodHours: Int
        get() = sp.getInt("period_hours", 0)
        set(v) { sp.edit().putInt("period_hours", v).apply() }

    var emailEnabled: Boolean
        get() = sp.getBoolean("email_enabled", false)
        set(v) { sp.edit().putBoolean("email_enabled", v).apply() }

    var smtpHost: String
        get() = sp.getString("smtp_host", "") ?: ""
        set(v) { sp.edit().putString("smtp_host", v.trim()).apply() }

    var smtpPort: Int
        get() = sp.getInt("smtp_port", 465)
        set(v) { sp.edit().putInt("smtp_port", v).apply() }

    /** true — SSL/TLS сразу (обычно 465), false — STARTTLS (обычно 587). */
    var smtpSsl: Boolean
        get() = sp.getBoolean("smtp_ssl", true)
        set(v) { sp.edit().putBoolean("smtp_ssl", v).apply() }

    var smtpUser: String
        get() = sp.getString("smtp_user", "") ?: ""
        set(v) { sp.edit().putString("smtp_user", v.trim()).apply() }

    var smtpPass: String
        get() = sp.getString("smtp_pass", "") ?: ""
        set(v) { sp.edit().putString("smtp_pass", v).apply() }

    var mailFrom: String
        get() = sp.getString("mail_from", "") ?: ""
        set(v) { sp.edit().putString("mail_from", v.trim()).apply() }

    var mailTo: String
        get() = sp.getString("mail_to", "") ?: ""
        set(v) { sp.edit().putString("mail_to", v.trim()).apply() }

    var keepArchives: Int
        get() = sp.getInt("keep_archives", 10)
        set(v) { sp.edit().putInt("keep_archives", v).apply() }

    var lastArchiveAt: Long
        get() = sp.getLong("last_archive_at", 0)
        set(v) { sp.edit().putLong("last_archive_at", v).apply() }

    var lastArchiveInfo: String
        get() = sp.getString("last_archive_info", "") ?: ""
        set(v) { sp.edit().putString("last_archive_info", v).apply() }

    var sortMode: String
        get() = sp.getString("sort", "date") ?: "date"
        set(v) { sp.edit().putString("sort", v).apply() }

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    val smtpConfigured: Boolean
        get() = smtpHost.isNotBlank() && smtpUser.isNotBlank() && smtpPass.isNotBlank() && mailTo.isNotBlank()
}
