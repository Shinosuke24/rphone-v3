package com.rphone.v3.util

import android.content.Context
import android.util.Log
import androidx.work.*
import com.rphone.v3.waveid.database.WaveIDDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SupabaseUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val profilId = inputData.getLong(KEY_PROFIL_ID, -1L)
        if (profilId == -1L) return Result.failure()

        return withContext(Dispatchers.IO) {
            try {
                val profil = WaveIDDatabase
                    .getInstance(applicationContext)
                    .profilArusDao()
                    .getById(profilId)

                if (profil == null) {
                    Log.e(TAG, "Profil id=$profilId tidak ditemukan di DB")
                    return@withContext Result.failure()
                }

                val ok = SupabaseUploader.uploadProfil(profil)
                if (ok) Result.success() else Result.retry()

            } catch (e: Exception) {
                Log.e(TAG, "doWork error: ${e.message}", e)
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG           = "SupabaseUploadWorker"
        private const val KEY_PROFIL_ID = "profil_id"

        fun enqueue(context: Context, profilId: Long) {
            val data = workDataOf(KEY_PROFIL_ID to profilId)
            val request = OneTimeWorkRequestBuilder<SupabaseUploadWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30_000L,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueue(request)

            Log.d(TAG, "Enqueued upload untuk profilId=$profilId")
        }
    }
}
