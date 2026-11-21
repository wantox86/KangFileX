package com.wantox86.kangfilex.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object Formatters {

    private val dateFormatter: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(units.size)
        val value = bytes / 1024.0.pow(exp.toDouble())
        return if (value >= 100.0) {
            String.format(Locale.US, "%.0f %s", value, units[exp - 1])
        } else {
            String.format(Locale.US, "%.1f %s", value, units[exp - 1])
        }
    }

    fun formatTimestamp(millis: Long): String {
        if (millis <= 0L) return ""
        return dateFormatter.get()?.format(Date(millis)).orEmpty()
    }

    fun formatFolderCounts(dirCount: Int?, fileCount: Int?): String {
        if (dirCount == null && fileCount == null) return "Folder"
        val dirs = dirCount ?: 0
        val files = fileCount ?: 0
        if (dirs == 0 && files == 0) return "Kosong"
        return buildString {
            if (dirs > 0) append("$dirs folder")
            if (dirs > 0 && files > 0) append(", ")
            if (files > 0) append("$files file")
        }
    }
}
