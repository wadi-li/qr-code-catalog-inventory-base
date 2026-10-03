package ru.vdl.catalog

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ArchiveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val p = Prefs(ctx)
        try {
            val file = Archive.build(ctx)
            var info = "Архив создан: ${file.name} (${file.length() / 1024} КБ)"
            if (p.emailEnabled && p.smtpConfigured) {
                try {
                    Archive.sendByEmail(ctx, file)
                    info += ", отправлен на ${p.mailTo}"
                } catch (e: Exception) {
                    info += ", ошибка отправки: ${e.message}"
                    p.lastArchiveAt = System.currentTimeMillis()
                    p.lastArchiveInfo = info
                    notify(ctx, "Архив создан, письмо не ушло", e.message ?: "ошибка SMTP")
                    return@withContext Result.retry()
                }
            }
            p.lastArchiveAt = System.currentTimeMillis()
            p.lastArchiveInfo = info
            notify(ctx, "Плановый архив каталога", info)
            Result.success()
        } catch (e: Exception) {
            p.lastArchiveInfo = "Ошибка архивации: ${e.message}"
            notify(ctx, "Ошибка архивации", e.message ?: "неизвестная ошибка")
            Result.retry()
        }
    }

    companion object {
        private const val WORK = "catalog_archive"
        private const val CH = "catalog_archive_ch"

        fun reschedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            val hours = Prefs(ctx).periodHours
            if (hours <= 0) { wm.cancelUniqueWork(WORK); return }
            val req = PeriodicWorkRequestBuilder<ArchiveWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (Prefs(ctx).emailEnabled) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED
                        ).build()
                )
                .setInitialDelay(minOf(hours.toLong(), 1L), TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun notify(ctx: Context, title: String, text: String) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CH, "Архивация каталога", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val n = NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            try { nm.notify(1001, n) } catch (_: SecurityException) { }
        }
    }
}
