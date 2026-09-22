package io.nekohasekai.sagernet.smart

import io.nekohasekai.sagernet.bg.proto.TestInstance
import io.nekohasekai.sagernet.database.ProxyEntity
import moe.matsuri.nb4a.utils.JavaUtil

data class ThroughputMeasurement(
    var ttfbMillis: Long = 0L,
    var bytes: Long = 0L,
    var durationMillis: Long = 0L,
    var mbps: Double = 0.0,
)

object SmartNodeProbe {

    suspend fun latency(profile: ProxyEntity, url: String, timeoutMs: Int): Int {
        return TestInstance(profile, url, timeoutMs).doTest()
    }

    suspend fun throughput(
        profile: ProxyEntity,
        url: String,
        timeoutMs: Int,
        maxBytes: Long,
    ): ThroughputMeasurement {
        val json = TestInstance(profile, url, timeoutMs).doThroughputTest(maxBytes)
        return JavaUtil.gson.fromJson(json, ThroughputMeasurement::class.java)
    }
}
