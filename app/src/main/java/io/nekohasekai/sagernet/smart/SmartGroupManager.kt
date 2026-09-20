package io.nekohasekai.sagernet.smart

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SmartGroupConfig
import io.nekohasekai.sagernet.database.SmartNodeMetric
import io.nekohasekai.sagernet.ktx.Logs
import kotlin.math.abs

object SmartGroupManager {

    fun getOrCreateConfig(groupId: Long): SmartGroupConfig {
        return SagerDatabase.smartGroupDao.get(groupId)
            ?: SmartGroupConfig(groupId = groupId).also {
                SagerDatabase.smartGroupDao.upsert(it)
            }
    }

    fun setLockedProxy(groupId: Long, proxyId: Long?) {
        val config = getOrCreateConfig(groupId)
        config.lockedProxyId = proxyId ?: 0L
        SagerDatabase.smartGroupDao.upsert(config)
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
    ): List<SmartNodeMetric> {
        val profiles = SagerDatabase.proxyDao.getByGroup(groupId)
        val results = ArrayList<SmartNodeMetric>(profiles.size)
        for (profile in profiles) {
            results += testNode(profile, includeThroughput, fullThroughput)
        }
        evaluateAndSwitch(groupId)
        return results
    }

    fun evaluateAndSwitch(
        groupId: Long,
        now: Long = System.currentTimeMillis(),
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
        val currentId = when {
            config.currentProxyId > 0L -> config.currentProxyId
            DataStore.selectedGroup == groupId && DataStore.selectedProxy > 0L -> DataStore.selectedProxy
            else -> 0L
        }
        val current = metrics.firstOrNull { it.proxyId == currentId }
        val candidate = SmartNodeScorer.bestCandidate(metrics, config, now)
        val decision = SmartNodeScorer.decideSwitch(current, candidate, config, now)

        if (decision.shouldSwitch && decision.toProxyId > 0L) {
            config.currentProxyId = decision.toProxyId
            config.lastSwitchAt = now
            SagerDatabase.smartGroupDao.upsert(config)

            if (DataStore.selectedGroup == groupId) {
                DataStore.selectedProxy = decision.toProxyId
                DataStore.currentProfile = decision.toProxyId
                if (DataStore.serviceState.canStop) {
                    // Smart groups are always selector-backed. Existing
                    // NekoBox reload() will hot-switch the selector whenever
                    // the current running selector belongs to this group.
                    SagerNet.reloadService()
                }
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
