package mx.grupostr.driver

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.TimeUnit

data class GpsSyncResult(val reachable: Boolean, val tracking: Boolean, val sessionValid: Boolean)

object GpsSync {
    private const val TAG = "STR-GPS-SYNC"
    private const val PERIODIC_WORK = "str-gps-offline-sync"

    fun scheduleOfflineRetries(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<GpsSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    // Never called from the Android main thread.
    fun flush(context: Context, session: GpsSession): GpsSyncResult {
        val store = GpsStore(context)
        try {
            var tracking = true
            repeat(4) {
                val batch = store.pending(session.routeId)
                val body = JSONObject().put("action", if (batch.isEmpty()) "status" else "sync")
                    .put("route_id", session.routeId)
                    .put("token", session.token)
                if (batch.isNotEmpty()) {
                    val points = JSONArray()
                    batch.forEach { point ->
                        points.put(JSONObject()
                            .put("client_point_id", point.id)
                            .put("latitude", point.latitude)
                            .put("longitude", point.longitude)
                            .put("accuracy_m", point.accuracyMeters)
                            .put("recorded_at", Instant.ofEpochMilli(point.recordedAt).toString()))
                    }
                    body.put("points", points)
                }
                val response = post(session.endpoint, body)
                if (response.first == 401 || response.first == 403) {
                    Log.w(TAG, "GPS session rejected; reopen route from Base44")
                    return GpsSyncResult(true, false, false)
                }
                if (response.first == 409) return GpsSyncResult(true, false, true)
                if (response.first !in 200..299) return GpsSyncResult(false, tracking, true)

                val payload = JSONObject(response.second)
                tracking = payload.optBoolean("tracking", false)
                if (batch.isNotEmpty()) {
                    val acceptedIds = mutableListOf<String>()
                    for (key in arrayOf("accepted", "rejected")) {
                        val ack = payload.optJSONArray(key) ?: continue
                        for (i in 0 until ack.length()) {
                            val id = ack.optString(i)
                            if (id.isNotBlank()) acceptedIds.add(id)
                        }
                    }
                    // Only mark IDs the server explicitly acknowledged.
                    store.acknowledge(acceptedIds)
                    if (acceptedIds.isEmpty()) return GpsSyncResult(false, tracking, true)
                }
                if (batch.isEmpty() || batch.size < 50) return GpsSyncResult(true, tracking, true)
            }
            return GpsSyncResult(true, tracking, true)
        } catch (error: Exception) {
            Log.w(TAG, "Offline or unable to sync; points remain in SQLite", error)
            return GpsSyncResult(false, true, true)
        } finally {
            store.close()
        }
    }

    private fun post(endpoint: String, body: JSONObject): Pair<Int, String> {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Cache-Control", "no-store")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            return Pair(status, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }
}

class GpsSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val session = GpsSession.load(applicationContext) ?: return Result.success()
        val result = GpsSync.flush(applicationContext, session)
        return if (!result.sessionValid) Result.failure()
               else if (!result.reachable) Result.retry()
               else Result.success()
    }
}
