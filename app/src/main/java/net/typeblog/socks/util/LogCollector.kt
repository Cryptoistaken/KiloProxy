package net.typeblog.socks.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogCollector {

    fun collectLogs(context: Context): String {
        val pid = android.os.Process.myPid()
        val process = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "time", "-t", "2000", "--pid=$pid")
        )
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()

        val header = buildString {
            appendLine("=== KiloProxy Debug Logs ===")
            appendLine("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Package: ${context.packageName}")
            appendLine("PID: $pid")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("============================")
            appendLine()
        }

        return header + output
    }

    fun shareLogs(context: Context, logs: String) {
        val file = File(context.cacheDir, "kiloproxy_logs_${System.currentTimeMillis()}.txt")
        file.writeText(logs)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "KiloProxy Debug Logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share logs"))
    }
}
