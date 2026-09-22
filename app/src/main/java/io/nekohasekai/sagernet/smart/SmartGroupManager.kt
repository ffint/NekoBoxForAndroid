package io.nekohasekai.sagernet.smart

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SmartGroupConfig
import io.nekohasekai.sagernet.database.SmartNodeMetric
import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs

object SmartGroupManager {

    enum class TestStage {
        LATENCY,
        THROUGHPUT,
        SELECTING,
        COMPLETE,
    }

    data class TestProgress(
        val stage: TestStage,
        val completed: Int,
        val total: Int,
        val profileName: String = "",
    )

    data class GroupTestResult(
        val decision: SmartNodeScorer.SwitchDecision,
        val elapsedMs: Long,
        val testedNodeCount: Int,
        val throughputNodeCount: Int,
        val selectedProxyId: Long,
        val selectedProfileName: String,
    )

    private const val QUICK_LATENCY_TIMEOUT_MS = 4_000
    private const val QUICK_THROUGHPUT_TIMEOUT_MS = 6_000
    private const val QUICK_THROUGHPUT_CANDIDATES = 3
    private const val LATENCY_CONCURRENCY = 4
    private const val THROUGHPUT_CONCURRENCY = 2

    private val groupTestLocks = ConcurrentHashMap<Long, Mutex>()

    fun getOrCreateConfig(groupId: Long): SmartGroupConfig {
        return SagerDatabase.smartGroupDao.get(groupId)
            ?: SmartGroupConfig(groupId = groupId).also {
                SagerDatabase.smartGroupDao.upsert(it)
            }
    }

    fun setLockedProxy(
        groupId: Long,
        proxyId: Long?,
        now: Long = System.currentTimeMillis(),
    ) {
        val config = getOrCreateConfig(groupId)
        config.lockedProxyId = proxyId ?: 0L
        if (proxyId != null && proxyId > 0L) {
            config.currentProxyId = proxyId
            config.lastSwitchAt = now
        }
        SagerDatabase.smartGroupDao.upsert(config)

        if (proxyId != null && proxyId > 0L && DataStore.selectedGroup == groupId) {
            DataStore.selectedProxy = proxyId
            DataStore.currentProfile = proxyId
            if (DataStore.serviceState.canStop) {
                SagerNet.reloadService()
            }
        }
    }

    fun setAutoSelect(groupId: Long, enabled: Boolean) {
        val config = getOrCreateConfig(groupId)
        config.autoSelect = enabled
        SagerDatabase.smartGroupDao.upsert(config)
    }

    fun markManualSelection(
        groupId: Long,
        proxyId: Long,
        now: Long = System.currentTimeMillis(),
    ) {
        val config = getOrCreateConfig(groupId)
        config.currentProxyId = proxyId
        config.lastSwitchAt = now
        SagerDatabase.smartGroupDao.upsert(config)
    }

    suspend fun testNode(
        profile: ProxyEntity,
        includeThroughput: Boolean = false,
        fullThroughput: Boolean = false,
    ): SmartNodeMetric {
        val config = getOrCreateConfig(profile.groupId)
        val now = System.currentTimeMillis()
        return try {
            val latency = SmartNodeProbe.latency(profile, config.latencyTestUrl, config.timeoutMs)
            var metric = recordLatency(profile, latency, now, config)
            if (includeThroughput) {
                val bytes = if (fullThroughput) config.fullTestBytes else config.quickTestBytes
                val measurement = SmartNodeProbe.throughput(
                    profile,
                    config.throughputTestUrl,
                    config.timeoutMs,
                    bytes,
                )
                metric = recordThroughput(profile, measurement, System.currentTimeMillis(), config)
            }
            metric
        } catch (e: Exception) {
            recordFailure(profile, e, System.currentTimeMillis(), config)
        }
    }

    suspend fun testGroup(
        groupId: Long,
        includeThroughput: Boolean = false,
        fullThroughput: Boolean = false,
        forceSwitch: Boolean = false,
        onProgress: ((TestProgress) -> Unit)? = null,
    ): GroupTestResult {
        val lock = groupTestLocks.computeIfAbsent(groupId) { Mutex() }
        return lock.withLock {
            val startedAt = System.currentTimeMillis()
            val profiles = SagerDatabase.proxyDao.getByGroup(groupId)
            val config = getOrCreateConfig(groupId)
            if (profiles.isEmpty()) {
                val decision = evaluateAndSwitch(groupId, forceSwitch = forceSwitch)
                onProgress?.invoke(TestProgress(TestStage.COMPLETE, 0, 0))
                return@withLock GroupTestResult(decision, 0L, 0, 0, 0L, "")
            }

            val latencyTimeout = if (fullThroughput) {
                config.timeoutMs
            } else {
                minOf(config.timeoutMs, QUICK_LATENCY_TIMEOUT_MS)
            }
            onProgress?.invoke(TestProgress(TestStage.LATENCY, 0, profiles.size))
            runLatencyPhase(profiles, config, latencyTimeout, onProgress)

            val throughputProfiles = when {
                !includeThroughput -> emptyList()
                fullThroughput -> profiles.filter { profile ->
                    val metric = SagerDatabase.smartNodeDao.get(profile.id)
                    metric != null && metric.lastSuccessAt >= startedAt && metric.currentLatencyMs > 0
                }
                else -> quickThroughputCandidates(profiles, config, startedAt)
            }

            if (throughputProfiles.isNotEmpty()) {
                onProgress?.invoke(TestProgress(TestStage.THROUGHPUT, 0, throughputProfiles.size))
                val throughputTimeout = if (fullThroughput) {
                    config.timeoutMs
                } else {
                    minOf(config.timeoutMs, QUICK_THROUGHPUT_TIMEOUT_MS)
                }
                runThroughputPhase(
                    throughputProfiles,
                    config,
                    throughputTimeout,
                    fullThroughput,
                    onProgress,
                )
            }

            onProgress?.invoke(TestProgress(TestStage.SELECTING, 0, 1))
            val decision = evaluateAndSwitch(groupId, forceSwitch = forceSwitch)
            val elapsed = System.currentTimeMillis() - startedAt
            val selectedProxyId = getOrCreateConfig(groupId).currentProxyId
                .takeIf { it > 0L }
                ?: decision.toProxyId.takeIf { it > 0L }
                ?: decision.fromProxyId.takeIf { it > 0L }
                ?: 0L
            val selectedProfileName = profiles.firstOrNull { it.id == selectedProxyId }
                ?.displayName()
                .orEmpty()
            onProgress?.invoke(TestProgress(TestStage.COMPLETE, 1, 1, selectedProfileName))
            GroupTestResult(
                decision = decision,
                elapsedMs = elapsed,
                testedNodeCount = profiles.size,
                throughputNodeCount = throughputProfiles.size,
                selectedProxyId = selectedProxyId,
                selectedProfileName = selectedProfileName,
            )
        }
    }

    private suspend fun runLatencyPhase(
        profiles: List<ProxyEntity>,
        config: SmartGroupConfig,
        timeoutMs: Int,
        onProgress: ((TestProgress) -> Unit)?,
    ) = coroutineScope {
        val completed = AtomicInteger(0)
        val semaphore = Semaphore(LATENCY_CONCURRENCY)
        profiles.map { profile ->
            async {
                semaphore.withPermit {
                    val now = System.currentTimeMillis()
                    try {
                        val latency = SmartNodeProbe.latency(profile, config.latencyTestUrl, timeoutMs)
                        recordLatency(profile, latency, now, config)
                    } catch (e: Exception) {
                        recordFailure(profile, e, System.currentTimeMillis(), config)
                    } finally {
                        onProgress?.invoke(
                            TestProgress(
                                stage = TestStage.LATENCY,
                                completed = completed.incrementAndGet(),
                                total = profiles.size,
                                profileName = profile.displayName(),
                            )
                        )
                    }
                }
            }
        }.awaitAll()
    }

    private fun quickThroughputCandidates(
        profiles: List<ProxyEntity>,
        config: SmartGroupConfig,
        startedAt: Long,
    ): List<ProxyEntity> {
        val profileById = profiles.associateBy { it.id }
        val fresh = profiles.mapNotNull { profile ->
            SagerDatabase.smartNodeDao.get(profile.id)?.takeIf {
                it.lastSuccessAt >= startedAt && it.currentLatencyMs > 0
            }
        }
        if (fresh.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val selectedIds = fresh
            .sortedByDescending { SmartNodeScorer.components(it, config, now).total }
            .take(QUICK_THROUGHPUT_CANDIDATES)
            .mapTo(linkedSetOf()) { it.proxyId }

        val currentId = config.currentProxyId.takeIf { it > 0L }
            ?: DataStore.selectedProxy.takeIf { DataStore.selectedGroup == config.groupId && it > 0L }
        if (currentId != null && fresh.any { it.proxyId == currentId }) {
            selectedIds += currentId
        }
        if (config.lockedProxyId > 0L && fresh.any { it.proxyId == config.lockedProxyId }) {
            selectedIds += config.lockedProxyId
        }
        return selectedIds.mapNotNull(profileById::get)
    }

    private suspend fun runThroughputPhase(
        profiles: List<ProxyEntity>,
        config: SmartGroupConfig,
        timeoutMs: Int,
        fullThroughput: Boolean,
        onProgress: ((TestProgress) -> Unit)?,
    ) = coroutineScope {
        val completed = AtomicInteger(0)
        val semaphore = Semaphore(THROUGHPUT_CONCURRENCY)
        val bytes = if (fullThroughput) config.fullTestBytes else config.quickTestBytes
        profiles.map { profile ->
            async {
                semaphore.withPermit {
                    try {
                        val measurement = SmartNodeProbe.throughput(
                            profile,
                            config.throughputTestUrl,
                            timeoutMs,
                            bytes,
                        )
                        recordThroughput(profile, measurement, System.currentTimeMillis(), config)
                    } catch (e: Exception) {
                        recordFailure(profile, e, System.currentTimeMillis(), config)
                    } finally {
                        onProgress?.invoke(
                            TestProgress(
                                stage = TestStage.THROUGHPUT,
                                completed = completed.incrementAndGet(),
                                total = profiles.size,
                                profileName = profile.displayName(),
                            )
                        )
                    }
                }
            }
        }.awaitAll()
    }

    suspend fun onNetworkChanged() {
        val profileId = DataStore.currentProfile
        if (profileId <= 0L || !DataStore.serviceState.canStop) return

        val profile = SagerDatabase.proxyDao.getById(profileId) ?: return
        val group = SagerDatabase.groupDao.getById(profile.groupId) ?: return
        if (group.type != io.nekohasekai.sagernet.GroupType.SMART) return

        val config = getOrCreateConfig(group.id)
        if (!config.enabled) return

        Logs.i("Smart Group network change check: groupId=" + group.id)
        // Network transitions can invalidate a previously good CF path.
        // Re-check all candidates with lightweight RTT only; throughput
        // remains on the low-frequency background cadence.
        testGroup(
            group.id,
            includeThroughput = false,
            fullThroughput = false,
        )
    }

    fun evaluateAndSwitch(
        groupId: Long,
        now: Long = System.currentTimeMillis(),
        forceSwitch: Boolean = false,
    ): SmartNodeScorer.SwitchDecision {
        val config = getOrCreateConfig(groupId)
        if (!config.enabled) {
            return SmartNodeScorer.SwitchDecision(false, config.currentProxyId, 0L, "smart group disabled")
        }

        val metrics = SagerDatabase.smartNodeDao.byGroup(groupId).map { metric ->
            metric.copy(score = SmartNodeScorer.components(metric, config, now).total).also {
                SagerDatabase.smartNodeDao.upsert(it)
            }
        }
        val runningProfile = DataStore.currentProfile.takeIf { it > 0L }?.let {
            SagerDatabase.proxyDao.getById(it)
        }
        val runningThisGroup =
            DataStore.serviceState.canStop && runningProfile?.groupId == groupId
        val currentId = when {
            runningThisGroup -> runningProfile!!.id
            config.currentProxyId > 0L -> config.currentProxyId
            !DataStore.serviceState.canStop &&
                DataStore.selectedGroup == groupId &&
                DataStore.selectedProxy > 0L -> DataStore.selectedProxy
            else -> 0L
        }
        val current = metrics.firstOrNull { it.proxyId == currentId }
        val candidate = SmartNodeScorer.bestCandidate(metrics, config, now)
        val decision = SmartNodeScorer.decideSwitch(
            current,
            candidate,
            config,
            now,
            forceBest = forceSwitch,
        )

        if (decision.shouldSwitch && decision.toProxyId > 0L) {
            config.currentProxyId = decision.toProxyId
            config.lastSwitchAt = now
            SagerDatabase.smartGroupDao.upsert(config)

            if (runningThisGroup) {
                DataStore.selectedProxy = decision.toProxyId
                DataStore.currentProfile = decision.toProxyId
                // Smart groups are always selector-backed. Existing
                // NekoBox reload() hot-switches the selector when the running
                // selector belongs to this group.
                SagerNet.reloadService()
            } else if (!DataStore.serviceState.canStop && DataStore.selectedGroup == groupId) {
                // When stopped, keep the UI selection aligned with the
                // algorithm's current winner without affecting any live VPN.
                DataStore.selectedProxy = decision.toProxyId
                DataStore.currentProfile = decision.toProxyId
            }
            Logs.i(
                "Smart Group switch: " + decision.fromProxyId + " -> " + decision.toProxyId +
                    "; reason=" + decision.reason
            )
        }
        return decision
    }

    private fun recordLatency(
        profile: ProxyEntity,
        latencyMs: Int,
        now: Long,
        config: SmartGroupConfig,
    ): SmartNodeMetric {
        val metric = SagerDatabase.smartNodeDao.get(profile.id)
            ?: SmartNodeMetric(proxyId = profile.id, groupId = profile.groupId)
        val samples = parseSamples(metric.latencySamples).toMutableList()
        samples += latencyMs
        while (samples.size > MAX_LATENCY_SAMPLES) samples.removeAt(0)

        metric.groupId = profile.groupId
        metric.currentLatencyMs = latencyMs
        metric.averageLatencyMs = samples.average()
        metric.jitterMs = averageJitter(samples)
        metric.latencySamples = samples.joinToString(",")
        metric.successCount += 1L
        metric.consecutiveFailures = 0
        metric.lastTestAt = now
        metric.lastSuccessAt = now
        metric.lastError = null
        metric.score = SmartNodeScorer.components(metric, config, now).total
        SagerDatabase.smartNodeDao.upsert(metric)
        return metric
    }

    private fun recordThroughput(
        profile: ProxyEntity,
        measurement: ThroughputMeasurement,
        now: Long,
        config: SmartGroupConfig,
    ): SmartNodeMetric {
        val metric = SagerDatabase.smartNodeDao.get(profile.id)
            ?: SmartNodeMetric(proxyId = profile.id, groupId = profile.groupId)
        metric.groupId = profile.groupId
        metric.ttfbMs = measurement.ttfbMillis
        metric.downloadBytes = measurement.bytes
        metric.downloadDurationMs = measurement.durationMillis
        metric.downloadMbps = measurement.mbps
        metric.lastThroughputTestAt = now
        metric.successCount += 1L
        metric.consecutiveFailures = 0
        metric.lastTestAt = now
        metric.lastSuccessAt = now
        metric.lastError = null
        metric.score = SmartNodeScorer.components(metric, config, now).total
        SagerDatabase.smartNodeDao.upsert(metric)
        return metric
    }

    private fun recordFailure(
        profile: ProxyEntity,
        error: Throwable,
        now: Long,
        config: SmartGroupConfig,
    ): SmartNodeMetric {
        val metric = SagerDatabase.smartNodeDao.get(profile.id)
            ?: SmartNodeMetric(proxyId = profile.id, groupId = profile.groupId)
        metric.groupId = profile.groupId
        metric.failureCount += 1L
        metric.consecutiveFailures += 1
        metric.lastTestAt = now
        metric.lastFailureAt = now
        metric.lastError = sanitizeError(error)
        metric.score = SmartNodeScorer.components(metric, config, now).total
        SagerDatabase.smartNodeDao.upsert(metric)
        Logs.w(
            "Smart Group node check failed: proxyId=" + profile.id +
                "; failures=" + metric.consecutiveFailures +
                "; error=" + metric.lastError
        )
        return metric
    }

    private fun parseSamples(value: String): List<Int> {
        return value.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it >= 0 }
    }

    private fun averageJitter(samples: List<Int>): Double {
        if (samples.size < 2) return 0.0
        return samples.zipWithNext { a, b -> abs(a - b).toDouble() }.average()
    }

    private fun sanitizeError(error: Throwable): String {
        var text = (error.message ?: error.javaClass.simpleName).replace(Regex("[\\r\\n]+"), " ")
        text = text.replace(
            Regex("(?i)(password|passwd|uuid|token|authorization|private[_ -]?key)\\s*[:=]\\s*[^\\s,;&]+"),
            "$1=<redacted>",
        )
        text = text.replace(Regex("([?&](?:token|key|auth|password)=[^&\\s]+)", RegexOption.IGNORE_CASE), "?<redacted>")
        return text.take(MAX_ERROR_LENGTH)
    }

    private const val MAX_LATENCY_SAMPLES = 8
    private const val MAX_ERROR_LENGTH = 180
}
