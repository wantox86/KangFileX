package com.wantox86.kangfilex.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wantox86.kangfilex.MainActivity
import com.wantox86.kangfilex.data.transfer.TransferOutcome
import com.wantox86.kangfilex.data.transfer.TransferQueueManager
import com.wantox86.kangfilex.data.transfer.TransferUiState
import com.wantox86.kangfilex.domain.transfer.TransferOperation
import com.wantox86.kangfilex.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class TransferForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var outcomeCollectorStarted = false
    private var stateCollectorStarted = false
    private var lastProgressNotifyMs = 0L
    private var lastNotifiedJobId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannels()

        val manager = TransferQueueManager.getInstance()
        startForegroundCompat(buildProgressNotification(manager.state.value))

        if (!outcomeCollectorStarted) {
            outcomeCollectorStarted = true
            startOutcomeObserver(manager)
        }

        if (intent?.action == ACTION_CANCEL) {
            manager.cancelActive()
            if (!manager.state.value.isBusy) {
                drainOutcomesAndStop(manager)
                return START_NOT_STICKY
            }
        }

        if (!manager.state.value.isBusy) {
            drainOutcomesAndStop(manager)
            return START_NOT_STICKY
        }

        if (!stateCollectorStarted) {
            stateCollectorStarted = true
            startStateObserver(manager)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startOutcomeObserver(manager: TransferQueueManager) {
        serviceScope.launch {
            manager.notificationOutcomes.collect { outcome ->
                val notification = buildResultNotification(outcome)
                notifySafe(nextResultNotificationId(), notification)
            }
        }
    }

    private fun startStateObserver(manager: TransferQueueManager) {
        serviceScope.launch {
            manager.state.collect { uiState ->
                if (!uiState.isBusy) {
                    drainOutcomesAndStop(manager)
                    return@collect
                }

                val now = SystemClock.elapsedRealtime()
                val currentJobId = uiState.activeJob?.jobId
                val jobChanged = currentJobId != null && currentJobId != lastNotifiedJobId
                if (jobChanged || (now - lastProgressNotifyMs) >= NOTIFICATION_THROTTLE_MS) {
                    lastProgressNotifyMs = now
                    lastNotifiedJobId = currentJobId
                    notifySafe(NOTIFICATION_ID_PROGRESS, buildProgressNotification(uiState))
                }
            }
        }
    }

    private fun drainOutcomesAndStop(manager: TransferQueueManager) {
        while (true) {
            val outcome = manager.pollNotificationOutcome() ?: break
            notifySafe(nextResultNotificationId(), buildResultNotification(outcome))
        }
        stopForegroundCompat()
        stopSelf()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        val progressChannel = NotificationChannel(
            CHANNEL_ID_PROGRESS,
            "Progres Transfer",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Menampilkan progres penyalinan dan pemindahan file"
            setShowBadge(false)
        }

        val resultChannel = NotificationChannel(
            CHANNEL_ID_RESULT,
            "Hasil Transfer",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ringkasan hasil transfer file yang selesai, gagal, atau dibatalkan"
        }

        nm.createNotificationChannel(progressChannel)
        nm.createNotificationChannel(resultChannel)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID_PROGRESS, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildProgressNotification(state: TransferUiState): Notification {
        val activeJob = state.activeJob
        val progress = activeJob?.progress
        val verb = when (activeJob?.operation) {
            TransferOperation.MOVE -> "Memindahkan"
            else -> "Menyalin"
        }

        val title = when {
            progress != null && progress.totalFiles > 0 ->
                "$verb ${progress.filesCompleted}/${progress.totalFiles}"
            activeJob != null ->
                "$verb file..."
            else ->
                "Menyiapkan transfer..."
        }

        val contentText = when {
            progress != null -> {
                val speed = formatSpeed(progress.speedBytesPerSec)
                val fileName = progress.currentFileName
                if (fileName.isNotBlank()) "$fileName • $speed" else speed
            }
            state.queuedCount > 0 ->
                "${state.queuedCount} tugas dalam antrean"
            else ->
                "Menghitung file..."
        }

        val cancelIntent = Intent(this, TransferForegroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_CANCEL,
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(createMainActivityPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Batal",
                cancelPendingIntent
            )

        when {
            progress != null && progress.totalBytes > 0L -> {
                val percent = ((progress.bytesTransferred.coerceIn(0L, progress.totalBytes) * 100L) /
                    progress.totalBytes).toInt()
                builder.setProgress(100, percent, false)
            }
            progress != null && progress.totalFiles > 0 -> {
                val percent = (progress.filesCompleted.coerceIn(0, progress.totalFiles) * 100) /
                    progress.totalFiles
                builder.setProgress(100, percent, false)
            }
            else -> {
                builder.setProgress(0, 0, true)
            }
        }

        if (state.queuedCount > 0) {
            builder.setSubText("+${state.queuedCount} antrean")
        }

        return builder.build()
    }

    private fun buildResultNotification(outcome: TransferOutcome): Notification {
        val (icon, title, text) = when (outcome) {
            is TransferOutcome.Completed -> {
                val actionLabel = if (outcome.operation == TransferOperation.MOVE) {
                    "Pemindahan"
                } else {
                    "Penyalinan"
                }
                val result = outcome.result
                val summary = when {
                    result.errors.isNotEmpty() ->
                        "${result.filesTransferred} file selesai, ${result.errors.size} gagal"
                    result.filesSkipped > 0 ->
                        "${result.filesTransferred} file selesai, ${result.filesSkipped} dilewati (${Formatters.formatFileSize(result.bytesTransferred)})"
                    else ->
                        "${result.filesTransferred} file (${Formatters.formatFileSize(result.bytesTransferred)})"
                }
                Triple(android.R.drawable.stat_sys_download_done, "$actionLabel selesai", summary)
            }
            is TransferOutcome.Failed -> {
                Triple(android.R.drawable.stat_notify_error, "Transfer gagal", outcome.message)
            }
            is TransferOutcome.Cancelled -> {
                Triple(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Transfer dibatalkan",
                    "Operasi transfer dihentikan oleh pengguna"
                )
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID_RESULT)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(createMainActivityPendingIntent())
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createMainActivityPendingIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_APP,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    @SuppressLint("MissingPermission")
    private fun notifySafe(id: Int, notification: Notification) {
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        nm.notify(id, notification)
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val safe = bytesPerSec.coerceAtLeast(0L)
        return when {
            safe >= 1024L * 1024L ->
                String.format(Locale.US, "%.1f MB/s", safe.toDouble() / (1024.0 * 1024.0))
            safe >= 1024L ->
                String.format(Locale.US, "%.0f KB/s", safe.toDouble() / 1024.0)
            else ->
                "$safe B/s"
        }
    }

    companion object {
        const val ACTION_CANCEL = "com.wantox86.kangfilex.action.CANCEL_TRANSFER"
        const val CHANNEL_ID_PROGRESS = "kangfilex_transfer_progress"
        const val CHANNEL_ID_RESULT = "kangfilex_transfer_result"
        const val NOTIFICATION_ID_PROGRESS = 1001

        private const val REQUEST_CODE_CANCEL = 101
        private const val REQUEST_CODE_OPEN_APP = 102
        private const val NOTIFICATION_THROTTLE_MS = 500L

        private val resultNotificationCounter = AtomicInteger(2000)

        private fun nextResultNotificationId(): Int = resultNotificationCounter.incrementAndGet()

        fun start(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
