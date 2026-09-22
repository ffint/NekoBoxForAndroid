package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.smart.SmartGroupManager
import java.util.concurrent.TimeUnit

/**
 * Persistent background health loop for Smart Groups.
 *
 * WorkManager enforces a 15 minute minimum periodic interval. Per-group
 * health/throughput intervals are still respected inside each run, so a group
 * configured for a longer interval is not probed unnecessarily.
 */
object SmartGroupUpdater {

    private const val WORK_NAME = "SmartGroupUpdater"
    private const val WORK_INTERVAL_MINUTES = 15L

    suspend fun reconfigureUpdater() {
        val remote = RemoteWorkManager.getInstance(app)
        val smartGroups = SagerDatabase.groupDao.smartGroups()
        if (smartGroups.isEmpty()) {
            remote.cancelUniqueWork(WORK_NAME)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        remote.enqueueUniquePeriodicWork(
            WORK_NAME,
            UPDATE,
            PeriodicWorkRequest.Builder(
                UpdateTask::class.java,
                WORK_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .build()
        )
    }

    class UpdateTask(
        appContext: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {

        override suspend fun doWork(): Result {
            val now = System.currentTimeMillis()
            return try {
                for (group in SagerDatabase.groupDao.smartGroups()) {
                    val config = SmartGroupManager.getOrCreateConfig(group.id)
                    if (!config.enabled) continue

                    val profileCount = SagerDatabase.proxyDao.countByGroup(group.id).toInt()
                    if (profileCount == 0) continue

                    val metrics = SagerDatabase.smartNodeDao.byGroup(group.id)
                    val healthIntervalMs =
                        config.healthIntervalMinutes.coerceAtLeast(15).toLong() * 60_000L
                    val throughputIntervalMs =
                        config.throughputIntervalMinutes.coerceAtLeast(15).toLong() * 60_000L

                    val healthDue = metrics.size != profileCount || metrics.any {
                        it.lastTestAt <= 0L || now - it.lastTestAt >= healthIntervalMs
                    }
                    val throughputDue = metrics.size != profileCount || metrics.any {
                        it.lastThroughputTestAt <= 0L ||
                            now - it.lastThroughputTestAt >= throughputIntervalMs
                    }

                    if (healthDue || throughputDue) {
                        SmartGroupManager.testGroup(
                            group.id,
                            includeThroughput = throughputDue,
                            fullThroughput = throughputDue,
                        )
                    } else {
                        SmartGroupManager.evaluateAndSwitch(group.id, now)
                    }
                }
                Result.success()
            } catch (e: Exception) {
                Logs.w("Smart Group background check failed: " + (e.message ?: e.javaClass.simpleName))
                Result.retry()
            }
        }
    }
}
