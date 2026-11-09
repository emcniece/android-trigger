package com.emcniece.trigger.engine

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.emcniece.trigger.Graph
import com.emcniece.trigger.model.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** A fully rendered HTTP request, ready to send (or to hand to the retry worker). */
@Serializable
data class HttpCall(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

object Http {
    private const val TIMEOUT_MS = 15_000

    fun parseHeaders(text: String): Map<String, String> = text.lines()
        .mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
        .filter { it.first.isNotEmpty() }
        .toMap()

    fun validate(call: HttpCall) {
        val url = try {
            URL(call.url)
        } catch (e: Exception) {
            throw ActionException("invalid URL \"${call.url}\"")
        }
        if (url.protocol != "http" && url.protocol != "https") throw ActionException("URL must be http or https")
    }

    fun isRetryable(code: Int) = code == 408 || code == 429 || code in 500..599

    /** Returns the HTTP status code. Throws [IOException] on network failure. */
    suspend fun execute(call: HttpCall): Int = withContext(Dispatchers.IO) {
        val conn = URL(call.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = call.method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Trigger-Android")
            call.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val sendsBody = call.body.isNotEmpty() && call.method !in setOf("GET", "HEAD")
            if (sendsBody) {
                if (call.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                    conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
                conn.doOutput = true
                conn.outputStream.use { it.write(call.body.encodeToByteArray()) }
            }
            val code = conn.responseCode
            (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() }
            code
        } finally {
            conn.disconnect()
        }
    }
}

/** Retries a failed HTTP action with exponential backoff once the network is available. */
class HttpRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val macroName = inputData.getString(KEY_MACRO) ?: "?"
        val call = inputData.getString(KEY_CALL)?.let {
            runCatching { AppJson.decodeFromString(HttpCall.serializer(), it) }.getOrNull()
        } ?: return Result.failure()
        val attempt = runAttemptCount + 1
        val outcome = try {
            Http.execute(call)
        } catch (e: IOException) {
            e
        }
        val ok = outcome is Int && outcome in 200..399
        val retryable = outcome is IOException || (outcome is Int && Http.isRetryable(outcome))
        val label = "${call.method} ${call.url}"
        val detail = if (outcome is Int) "→ $outcome" else ": ${(outcome as Exception).message}"
        return when {
            ok -> {
                Graph.log.add(macroName, "HTTP retry #$attempt succeeded: $label $detail", true)
                Result.success()
            }
            retryable && attempt < MAX_ATTEMPTS -> Result.retry()
            else -> {
                Graph.log.add(macroName, "HTTP retry gave up after $attempt attempts: $label $detail", false)
                Result.failure()
            }
        }
    }

    companion object {
        private const val KEY_CALL = "call"
        private const val KEY_MACRO = "macro"
        private const val MAX_ATTEMPTS = 6

        fun enqueue(context: Context, call: HttpCall, macroName: String) {
            val request = OneTimeWorkRequestBuilder<HttpRetryWorker>()
                .setInputData(
                    workDataOf(
                        KEY_CALL to AppJson.encodeToString(HttpCall.serializer(), call),
                        KEY_MACRO to macroName,
                    ),
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInitialDelay(15, TimeUnit.SECONDS)
                .addTag("http-retry")
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
