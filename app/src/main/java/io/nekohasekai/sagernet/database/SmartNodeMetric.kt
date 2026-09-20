package io.nekohasekai.sagernet.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(
    tableName = "smart_node_metrics",
    indices = [Index(value = ["groupId"], name = "smartNodeGroupId")]
)
data class SmartNodeMetric(
    @PrimaryKey var proxyId: Long = 0L,
    var groupId: Long = 0L,
    var currentLatencyMs: Int = -1,
    var averageLatencyMs: Double = -1.0,
    var jitterMs: Double = -1.0,
    var latencySamples: String = "",
    var ttfbMs: Long = -1L,
    var downloadBytes: Long = 0L,
    var downloadDurationMs: Long = 0L,
    var downloadMbps: Double = 0.0,
    var lastThroughputTestAt: Long = 0L,
    var successCount: Long = 0L,
    var failureCount: Long = 0L,
    var consecutiveFailures: Int = 0,
    var lastTestAt: Long = 0L,
    var lastSuccessAt: Long = 0L,
    var lastFailureAt: Long = 0L,
    var score: Double = 0.0,
    var lastError: String? = null,
) {
    @Dao
    interface Dao {
        @Query("SELECT * FROM smart_node_metrics WHERE groupId = :groupId ORDER BY score DESC")
        fun byGroup(groupId: Long): List<SmartNodeMetric>

        @Query("SELECT * FROM smart_node_metrics WHERE proxyId = :proxyId")
        fun get(proxyId: Long): SmartNodeMetric?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        fun upsert(metric: SmartNodeMetric)

        @Query("DELETE FROM smart_node_metrics WHERE proxyId = :proxyId")
        fun delete(proxyId: Long)

        @Query("DELETE FROM smart_node_metrics WHERE groupId = :groupId")
        fun deleteByGroup(groupId: Long)
    }
}
