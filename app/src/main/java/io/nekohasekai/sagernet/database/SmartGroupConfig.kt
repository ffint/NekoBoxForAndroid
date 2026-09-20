package io.nekohasekai.sagernet.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import io.nekohasekai.sagernet.CONNECTION_TEST_URL

@Entity(tableName = "smart_group_config")
data class SmartGroupConfig(
    @PrimaryKey var groupId: Long = 0L,
    var enabled: Boolean = true,
    var autoSelect: Boolean = true,
    var lockedProxyId: Long = 0L,
    var currentProxyId: Long = 0L,
    var lastSwitchAt: Long = 0L,
    var latencyTestUrl: String = CONNECTION_TEST_URL,
    var throughputTestUrl: String = "https://speed.cloudflare.com/__down?bytes=2097152",
    var quickTestBytes: Long = 256L * 1024L,
    var fullTestBytes: Long = 512L * 1024L,
    var timeoutMs: Int = 10_000,
    var healthIntervalMinutes: Int = 15,
    var throughputIntervalMinutes: Int = 120,
    var switchScoreDelta: Double = 8.0,
    var minSwitchIntervalMs: Long = 120_000L,
    var failureThreshold: Int = 3,
    var latencyWeight: Double = 0.08,
    var jitterWeight: Double = 0.08,
    var throughputWeight: Double = 0.28,
    var successWeight: Double = 0.20,
    var stabilityWeight: Double = 0.30,
    var failureWeight: Double = 0.06,
) {
    @Dao
    interface Dao {
        @Query("SELECT * FROM smart_group_config WHERE groupId = :groupId")
        fun get(groupId: Long): SmartGroupConfig?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        fun upsert(config: SmartGroupConfig)

        @Query("DELETE FROM smart_group_config WHERE groupId = :groupId")
        fun delete(groupId: Long)

        @Query(
            "UPDATE smart_group_config SET " +
                "currentProxyId = CASE WHEN currentProxyId = :proxyId THEN 0 ELSE currentProxyId END, " +
                "lockedProxyId = CASE WHEN lockedProxyId = :proxyId THEN 0 ELSE lockedProxyId END " +
                "WHERE groupId = :groupId"
        )
        fun clearProxyReference(groupId: Long, proxyId: Long)

        @Query(
            "UPDATE smart_group_config SET currentProxyId = 0, lockedProxyId = 0, lastSwitchAt = 0 " +
                "WHERE groupId = :groupId"
        )
        fun resetSelection(groupId: Long)
    }
}
