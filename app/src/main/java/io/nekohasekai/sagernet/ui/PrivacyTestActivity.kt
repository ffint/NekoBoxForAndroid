package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutPrivacyTestBinding
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import libcore.Libcore
import moe.matsuri.nb4a.utils.JavaUtil

class PrivacyTestActivity : ThemedActivity(), SagerConnection.Callback {

    data class PrivacyProbeResult(
        var ipv4: String = "",
        var ipv4Colo: String = "",
        var ipv4Error: String = "",
        var ipv6: String = "",
        var ipv6Colo: String = "",
        var ipv6Error: String = "",
        var dnsResolverIp: String = "",
        var dnsResolverAsn: String = "",
        var dnsResolverCountry: String = "",
        var dnsRaw: String = "",
        var dnsError: String = "",
    )

    private lateinit var binding: LayoutPrivacyTestBinding
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_PRIVACY_TEST)
    private var pendingTest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutPrivacyTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.privacy_test)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }

        binding.retest.setOnClickListener {
            pendingTest = true
            runTest()
        }
        binding.stunTest.setOnClickListener {
            startActivity(Intent(this, StunActivity::class.java))
        }

        updateLocalStatus()
        pendingTest = true
        connection.connect(this, this)
    }

    override fun onServiceConnected(service: ISagerNetService) {
        DataStore.serviceState = runCatching {
            BaseService.State.values()[service.state]
        }.getOrDefault(BaseService.State.Idle)
        updateLocalStatus()
        if (pendingTest) runTest()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        DataStore.serviceState = state
        updateLocalStatus()
        if (state.connected && pendingTest) runTest()
    }

    override fun onServiceDisconnected() {
        DataStore.serviceState = BaseService.State.Idle
        updateLocalStatus()
    }

    override fun onBinderDied() {
        runCatching { connection.disconnect(this) }
        connection.connect(this, this)
    }

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
    }

    private fun updateLocalStatus() {
        val connected = DataStore.serviceState.connected
        binding.vpnStatusValue.text = getString(
            if (connected) R.string.privacy_status_connected else R.string.privacy_status_not_connected
        )
        binding.strictPrivacyValue.text = getString(
            if (DataStore.strictPrivacyMode) R.string.privacy_status_enabled else R.string.privacy_status_disabled
        )

        val profileId = DataStore.currentProfile.takeIf { it > 0L } ?: DataStore.selectedProxy
        val profile = profileId.takeIf { it > 0L }?.let(SagerDatabase.proxyDao::getById)
        val group = profile?.let { SagerDatabase.groupDao.getById(it.groupId) }

        binding.currentNodeValue.text = profile?.displayName()
            ?: getString(R.string.privacy_status_unavailable)

        if (group?.type == GroupType.SMART && profile != null) {
            val metric = SagerDatabase.smartNodeDao.get(profile.id)
            binding.smartGroupValue.text = buildString {
                append(group.displayName())
                if (metric != null && metric.lastTestAt > 0L) {
                    append(" · ")
                    append(getString(R.string.privacy_score_value, metric.score))
                }
            }
        } else {
            binding.smartGroupValue.text = group?.displayName()
                ?: getString(R.string.privacy_status_not_smart_group)
        }

        binding.coreValue.text = runCatching { Libcore.versionBox() }
            .getOrDefault(getString(R.string.privacy_status_unavailable))
    }

    private fun runTest() {
        updateLocalStatus()

        if (!DataStore.serviceState.connected) {
            pendingTest = false
            binding.waitLayout.isVisible = false
            binding.retest.isEnabled = true
            binding.testStatus.text = getString(R.string.privacy_test_requires_proxy)
            clearNetworkResults()
            return
        }

        val service = connection.service
        if (service == null) {
            pendingTest = true
            binding.waitLayout.isVisible = true
            binding.retest.isEnabled = false
            binding.testStatus.text = getString(R.string.privacy_test_connecting_core)
            return
        }

        pendingTest = false
        binding.waitLayout.isVisible = true
        binding.retest.isEnabled = false

        runOnDefaultDispatcher {
            val result = runCatching {
                val json = service.privacyProbe(8000)
                JavaUtil.gson.fromJson(json, PrivacyProbeResult::class.java)
            }
            onMainDispatcher {
                if (isFinishing || isDestroyed) return@onMainDispatcher
                binding.waitLayout.isVisible = false
                binding.retest.isEnabled = true
                result.onSuccess {
                    renderResult(it)
                }.onFailure {
                    clearNetworkResults()
                    binding.testStatus.text = getString(
                        R.string.privacy_test_failed,
                        it.readableMessage,
                    )
                }
            }
        }
    }

    private fun renderResult(result: PrivacyProbeResult) {
        binding.testStatus.text = getString(R.string.privacy_test_proxy_path_note)

        binding.ipv4Value.text = formatProbeValue(
            result.ipv4,
            result.ipv4Colo,
            result.ipv4Error,
        )
        binding.ipv6Value.text = if (result.ipv6.isNotBlank()) {
            formatProbeValue(result.ipv6, result.ipv6Colo, "")
        } else {
            getString(
                R.string.privacy_blocked_or_unavailable,
                result.ipv6Error.ifBlank { getString(R.string.privacy_status_unavailable) },
            )
        }

        binding.dnsResolverValue.text = when {
            result.dnsResolverIp.isNotBlank() -> buildString {
                append(result.dnsResolverIp)
                if (result.dnsResolverAsn.isNotBlank()) {
                    append(" · ")
                    append(result.dnsResolverAsn)
                }
                if (result.dnsResolverCountry.isNotBlank()) {
                    append(" · ")
                    append(result.dnsResolverCountry)
                }
            }

            result.dnsError.isNotBlank() -> getString(
                R.string.privacy_blocked_or_unavailable,
                result.dnsError,
            )

            else -> getString(R.string.privacy_status_unavailable)
        }

        binding.dnsRawValue.text = result.dnsRaw.ifBlank {
            getString(R.string.privacy_status_unavailable)
        }
    }

    private fun formatProbeValue(ip: String, colo: String, error: String): String {
        if (ip.isBlank()) {
            return getString(
                R.string.privacy_blocked_or_unavailable,
                error.ifBlank { getString(R.string.privacy_status_unavailable) },
            )
        }
        return if (colo.isBlank()) ip else "$ip · CF $colo"
    }

    private fun clearNetworkResults() {
        val value = getString(R.string.privacy_status_unavailable)
        binding.ipv4Value.text = value
        binding.ipv6Value.text = value
        binding.dnsResolverValue.text = value
        binding.dnsRawValue.text = value
    }
}
