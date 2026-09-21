package io.nekohasekai.sagernet.smart

import io.nekohasekai.sagernet.database.SmartGroupConfig
import io.nekohasekai.sagernet.database.SmartNodeMetric
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

object SmartNodeScorer {

    data class Components(
        val latency: Double,
        val jitter: Double,
        val throughput: Double,
        val success: Double,
        val stability: Double,
        val failure: Double,
        val total: Double,
    )

    data class SwitchDecision(
        val shouldSwitch: Boolean,
        val fromProxyId: Long,
        val toProxyId: Long,
        val reason: String,
    )

    fun components(
        metric: SmartNodeMetric,
        config: SmartGroupConfig,
        now: Long = System.currentTimeMillis(),
    ): Components {
        val latency = if (metric.averageLatencyMs > 0.0) {
            100.0 / (1.0 + metric.averageLatencyMs / 120.0)
        } else 0.0
        val jitter = if (metric.jitterMs >= 0.0) {
            100.0 / (1.0 + metric.jitterMs / 35.0)
        } else 0.0
        val throughput = if (metric.downloadMbps > 0.0) {
            100.0 * (1.0 - exp(-metric.downloadMbps / 35.0))
        } else 0.0

        val attempts = metric.successCount + metric.failureCount
        val successRate = if (attempts > 0L) {
            metric.successCount.toDouble() / attempts.toDouble()
        } else 0.0
        val success = successRate * 100.0

        val ageMs = if (metric.lastSuccessAt > 0L) {
            max(0L, now - metric.lastSuccessAt)
        } else Long.MAX_VALUE
        val recency = if (ageMs == Long.MAX_VALUE) {
            0.0
        } else {
            100.0 * exp(-ageMs.toDouble() / (6.0 * 60.0 * 60.0 * 1000.0))
        }
        val jitterStability = if (metric.jitterMs >= 0.0) jitter else 50.0
        val stability = clamp(
            successRate * 60.0 +
                recency * 0.25 +
                jitterStability * 0.15 -
                metric.consecutiveFailures * 18.0
        )
        val failure = clamp(100.0 - metric.consecutiveFailures * 28.0)

        val totalWeight = listOf(
            config.latencyWeight,
            config.jitterWeight,
            config.throughputWeight,
            config.successWeight,
            config.stabilityWeight,
            config.failureWeight,
        ).sum().takeIf { it > 0.0 } ?: 1.0

        val total = (
            latency * config.latencyWeight +
                jitter * config.jitterWeight +
                throughput * config.throughputWeight +
                success * config.successWeight +
                stability * config.stabilityWeight +
                failure * config.failureWeight
            ) / totalWeight

        return Components(
            latency = clamp(latency),
            jitter = clamp(jitter),
            throughput = clamp(throughput),
            success = clamp(success),
            stability = clamp(stability),
            failure = clamp(failure),
            total = clamp(total),
        )
    }

    fun bestCandidate(
        metrics: List<SmartNodeMetric>,
        config: SmartGroupConfig,
        now: Long = System.currentTimeMillis(),
    ): SmartNodeMetric? {
        if (metrics.isEmpty()) return null

        val healthy = metrics.filter {
            it.lastSuccessAt > 0L && it.consecutiveFailures < config.failureThreshold
        }
        val candidates = if (healthy.isNotEmpty()) healthy else metrics

        val locked = config.lockedProxyId.takeIf { it > 0L }?.let { lockedId ->
            candidates.firstOrNull { it.proxyId == lockedId }
        }
        if (locked != null && locked.consecutiveFailures < config.failureThreshold) {
            return locked.copy(score = components(locked, config, now).total)
        }

        return candidates
            .map { it.copy(score = components(it, config, now).total) }
            .maxByOrNull { it.score }
    }

    fun decideSwitch(
        current: SmartNodeMetric?,
        candidate: SmartNodeMetric?,
        config: SmartGroupConfig,
        now: Long = System.currentTimeMillis(),
        forceBest: Boolean = false,
    ): SwitchDecision {
        if (candidate == null) {
            return SwitchDecision(false, current?.proxyId ?: 0L, 0L, "no candidate")
        }
        if (current == null) {
            return SwitchDecision(true, 0L, candidate.proxyId, "no current node")
        }
        if (candidate.proxyId == current.proxyId) {
            return SwitchDecision(false, current.proxyId, current.proxyId, "current node remains best")
        }

        val currentScore = components(current, config, now).total
        val candidateScore = components(candidate, config, now).total
        if (current.consecutiveFailures >= config.failureThreshold) {
            return SwitchDecision(
                true,
                current.proxyId,
                candidate.proxyId,
                "failover after " + current.consecutiveFailures + " consecutive failures",
            )
        }
        if (config.lockedProxyId == current.proxyId) {
            return SwitchDecision(false, current.proxyId, candidate.proxyId, "current node is locked")
        }
        if (!config.autoSelect) {
            return SwitchDecision(false, current.proxyId, candidate.proxyId, "automatic selection disabled")
        }
        if (forceBest) {
            return SwitchDecision(
                true,
                current.proxyId,
                candidate.proxyId,
                "manual Smart Test selected best candidate",
            )
        }
        val elapsed = now - config.lastSwitchAt
        if (config.lastSwitchAt > 0L && elapsed < config.minSwitchIntervalMs) {
            return SwitchDecision(false, current.proxyId, candidate.proxyId, "minimum switch interval")
        }
        val delta = candidateScore - currentScore
        if (delta < config.switchScoreDelta) {
            return SwitchDecision(false, current.proxyId, candidate.proxyId, "score delta below hysteresis threshold")
        }
        return SwitchDecision(
            true,
            current.proxyId,
            candidate.proxyId,
            "candidate score improved by " + "%.1f".format(delta),
        )
    }

    private fun clamp(value: Double): Double = min(100.0, max(0.0, value))
}
