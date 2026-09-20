package io.nekohasekai.sagernet.smart

import io.nekohasekai.sagernet.database.SmartGroupConfig
import io.nekohasekai.sagernet.database.SmartNodeMetric
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartNodeScorerTest {

    private val now = 1_000_000_000L

    private fun metric(
        id: Long,
        latency: Double,
        jitter: Double,
        mbps: Double,
        failures: Int = 0,
    ) = SmartNodeMetric(
        proxyId = id,
        groupId = 1L,
        averageLatencyMs = latency,
        jitterMs = jitter,
        downloadMbps = mbps,
        successCount = 20,
        failureCount = failures.toLong(),
        consecutiveFailures = failures,
        lastSuccessAt = now,
        lastTestAt = now,
    )

    @Test
    fun throughputCanBeatSlightlyLowerLatency() {
        val config = SmartGroupConfig(groupId = 1L)
        val lowPingSlow = metric(1L, latency = 60.0, jitter = 5.0, mbps = 8.0)
        val higherPingFast = metric(2L, latency = 85.0, jitter = 5.0, mbps = 80.0)

        val a = SmartNodeScorer.components(lowPingSlow, config, now).total
        val b = SmartNodeScorer.components(higherPingFast, config, now).total

        assertTrue("80 Mbps candidate should outrank 8 Mbps candidate", b > a)
    }

    @Test
    fun hysteresisPreventsSmallScoreSwitches() {
        val config = SmartGroupConfig(
            groupId = 1L,
            switchScoreDelta = 50.0,
            minSwitchIntervalMs = 0L,
        )
        val current = metric(1L, latency = 70.0, jitter = 4.0, mbps = 40.0)
        val candidate = metric(2L, latency = 65.0, jitter = 4.0, mbps = 45.0)

        val decision = SmartNodeScorer.decideSwitch(current, candidate, config, now)
        assertFalse(decision.shouldSwitch)
    }

    @Test
    fun failureBypassesMinimumSwitchInterval() {
        val config = SmartGroupConfig(
            groupId = 1L,
            minSwitchIntervalMs = 10_000_000L,
            lastSwitchAt = now - 1L,
            failureThreshold = 3,
        )
        val current = metric(1L, latency = 50.0, jitter = 3.0, mbps = 50.0, failures = 3)
        val candidate = metric(2L, latency = 80.0, jitter = 5.0, mbps = 30.0)

        val decision = SmartNodeScorer.decideSwitch(current, candidate, config, now)
        assertTrue(decision.shouldSwitch)
    }

    @Test
    fun lockedHealthyCurrentNodeDoesNotAutoSwitch() {
        val config = SmartGroupConfig(
            groupId = 1L,
            lockedProxyId = 1L,
            minSwitchIntervalMs = 0L,
        )
        val current = metric(1L, latency = 100.0, jitter = 10.0, mbps = 10.0)
        val candidate = metric(2L, latency = 30.0, jitter = 2.0, mbps = 100.0)

        val decision = SmartNodeScorer.decideSwitch(current, candidate, config, now)
        assertFalse(decision.shouldSwitch)
    }
}
