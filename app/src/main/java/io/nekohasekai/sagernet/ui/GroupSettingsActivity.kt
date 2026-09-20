package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.preference.*
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.bg.SmartGroupUpdater
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.OutboundPreference
import kotlinx.parcelize.Parcelize
import moe.matsuri.nb4a.ui.SimpleMenuPreference

@Suppress("UNCHECKED_CAST")
class GroupSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ThemedActivity(resId),
    OnPreferenceDataStoreChangeListener {

    private lateinit var frontProxyPreference: OutboundPreference
    private lateinit var landingProxyPreference: OutboundPreference

    fun ProxyGroup.init() {
        DataStore.groupName = name ?: ""
        DataStore.groupType = type
        DataStore.groupOrder = order
        DataStore.groupIsSelector = isSelector

        val smart = if (id > 0L) {
            SagerDatabase.smartGroupDao.get(id) ?: SmartGroupConfig(groupId = id)
        } else {
            SmartGroupConfig(groupId = id)
        }
        DataStore.groupSmartAutoSelect = smart.autoSelect
        DataStore.groupSmartLatencyUrl = smart.latencyTestUrl
        DataStore.groupSmartThroughputUrl = smart.throughputTestUrl
        DataStore.groupSmartHealthInterval = smart.healthIntervalMinutes
        DataStore.groupSmartThroughputInterval = smart.throughputIntervalMinutes
        DataStore.groupSmartSwitchDelta = smart.switchScoreDelta.toString()
        DataStore.groupSmartMinSwitchInterval = (smart.minSwitchIntervalMs / 1000L).toInt()
        DataStore.groupSmartFailureThreshold = smart.failureThreshold
        DataStore.groupSmartLatencyWeight = smart.latencyWeight.toString()
        DataStore.groupSmartJitterWeight = smart.jitterWeight.toString()
        DataStore.groupSmartThroughputWeight = smart.throughputWeight.toString()
        DataStore.groupSmartSuccessWeight = smart.successWeight.toString()
        DataStore.groupSmartStabilityWeight = smart.stabilityWeight.toString()
        DataStore.groupSmartFailureWeight = smart.failureWeight.toString()

        DataStore.frontProxy = frontProxy
        DataStore.landingProxy = landingProxy
        DataStore.frontProxyTmp = if (frontProxy >= 0) 3 else 0
        DataStore.landingProxyTmp = if (landingProxy >= 0) 3 else 0

        val subscription = subscription ?: SubscriptionBean().applyDefaultValues()
        DataStore.subscriptionLink = subscription.link
        DataStore.subscriptionForceResolve = subscription.forceResolve
        DataStore.subscriptionDeduplication = subscription.deduplication
        DataStore.subscriptionUpdateWhenConnectedOnly = subscription.updateWhenConnectedOnly
        DataStore.subscriptionUserAgent = subscription.customUserAgent
        DataStore.subscriptionAutoUpdate = subscription.autoUpdate
        DataStore.subscriptionAutoUpdateDelay = subscription.autoUpdateDelay
    }

    fun ProxyGroup.serialize() {
        name = DataStore.groupName.takeIf { it.isNotBlank() } ?: "My group"
        type = DataStore.groupType
        order = DataStore.groupOrder
        isSelector = if (type == GroupType.SMART) true else DataStore.groupIsSelector

        frontProxy = if (DataStore.frontProxyTmp == 3) DataStore.frontProxy else -1
        landingProxy = if (DataStore.landingProxyTmp == 3) DataStore.landingProxy else -1

        val isSubscription = type == GroupType.SUBSCRIPTION
        if (isSubscription) {
            subscription = (subscription ?: SubscriptionBean().applyDefaultValues()).apply {
                link = DataStore.subscriptionLink
                forceResolve = DataStore.subscriptionForceResolve
                deduplication = DataStore.subscriptionDeduplication
                updateWhenConnectedOnly = DataStore.subscriptionUpdateWhenConnectedOnly
                customUserAgent = DataStore.subscriptionUserAgent
                autoUpdate = DataStore.subscriptionAutoUpdate
                autoUpdateDelay = DataStore.subscriptionAutoUpdateDelay
            }
        } else {
            subscription = null
        }
    }

    private fun saveSmartConfig(group: ProxyGroup) {
        if (group.type != GroupType.SMART) {
            SagerDatabase.smartGroupDao.delete(group.id)
            SagerDatabase.smartNodeDao.deleteByGroup(group.id)
            return
        }

        val previous = SagerDatabase.smartGroupDao.get(group.id)
            ?: SmartGroupConfig(groupId = group.id)
        SagerDatabase.smartGroupDao.upsert(
            previous.copy(
                groupId = group.id,
                enabled = true,
                autoSelect = DataStore.groupSmartAutoSelect,
                latencyTestUrl = DataStore.groupSmartLatencyUrl.trim(),
                throughputTestUrl = DataStore.groupSmartThroughputUrl.trim(),
                healthIntervalMinutes = DataStore.groupSmartHealthInterval,
                throughputIntervalMinutes = DataStore.groupSmartThroughputInterval,
                switchScoreDelta = DataStore.groupSmartSwitchDelta.toDoubleOrNull() ?: 8.0,
                minSwitchIntervalMs =
                    DataStore.groupSmartMinSwitchInterval.toLong().coerceAtLeast(0L) * 1000L,
                failureThreshold = DataStore.groupSmartFailureThreshold,
                latencyWeight = DataStore.groupSmartLatencyWeight.toDoubleOrNull() ?: 0.08,
                jitterWeight = DataStore.groupSmartJitterWeight.toDoubleOrNull() ?: 0.08,
                throughputWeight = DataStore.groupSmartThroughputWeight.toDoubleOrNull() ?: 0.28,
                successWeight = DataStore.groupSmartSuccessWeight.toDoubleOrNull() ?: 0.20,
                stabilityWeight = DataStore.groupSmartStabilityWeight.toDoubleOrNull() ?: 0.30,
                failureWeight = DataStore.groupSmartFailureWeight.toDoubleOrNull() ?: 0.06,
            )
        )
    }

    fun needSave(): Boolean {
        if (!DataStore.dirty) return false
        return true
    }

    fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.group_preferences)

        frontProxyPreference = findPreference(Key.GROUP_FRONT_PROXY)!!
        frontProxyPreference.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == "3") {
                    selectProfileForAddFront.launch(
                        Intent(this@GroupSettingsActivity, ProfileSelectActivity::class.java)
                    )
                    false
                } else {
                    true
                }
            }
        }
        landingProxyPreference = findPreference(Key.GROUP_LANDING_PROXY)!!
        landingProxyPreference.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == "3") {
                    selectProfileForAddLanding.launch(
                        Intent(this@GroupSettingsActivity, ProfileSelectActivity::class.java)
                    )
                    false
                } else {
                    true
                }
            }
        }

        val groupType = findPreference<SimpleMenuPreference>(Key.GROUP_TYPE)!!
        val groupIsSelector = findPreference<SwitchPreference>(Key.GROUP_IS_SELECTOR)!!
        val groupSmart = findPreference<PreferenceCategory>(Key.GROUP_SMART)!!
        val groupSubscription = findPreference<PreferenceCategory>(Key.GROUP_SUBSCRIPTION)!!
        val subscriptionUpdate = findPreference<PreferenceCategory>(Key.SUBSCRIPTION_UPDATE)!!

        fun updateGroupType(groupType: Int = DataStore.groupType) {
            val isSubscription = groupType == GroupType.SUBSCRIPTION
            val isSmart = groupType == GroupType.SMART
            groupSmart.isVisible = isSmart
            groupSubscription.isVisible = isSubscription
            subscriptionUpdate.isVisible = isSubscription
            groupIsSelector.isEnabled = !isSmart
            if (isSmart) groupIsSelector.isChecked = true
        }
        updateGroupType()
        groupType.setOnPreferenceChangeListener { _, newValue ->
            updateGroupType((newValue as String).toInt())
            true
        }

        fun requireInt(key: String, min: Int, max: Int) {
            findPreference<EditTextPreference>(key)!!.setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).toIntOrNull()
                value != null && value in min..max
            }
        }
        fun requireDouble(key: String, min: Double, max: Double) {
            findPreference<EditTextPreference>(key)!!.setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).toDoubleOrNull()
                value != null && value in min..max
            }
        }
        fun requireHttpUrl(key: String) {
            findPreference<EditTextPreference>(key)!!.setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).trim()
                value.startsWith("https://") || value.startsWith("http://")
            }
        }

        requireHttpUrl(Key.GROUP_SMART_LATENCY_URL)
        requireHttpUrl(Key.GROUP_SMART_THROUGHPUT_URL)
        requireInt(Key.GROUP_SMART_HEALTH_INTERVAL, 15, 1440)
        requireInt(Key.GROUP_SMART_THROUGHPUT_INTERVAL, 5, 10080)
        requireDouble(Key.GROUP_SMART_SWITCH_DELTA, 0.0, 100.0)
        requireInt(Key.GROUP_SMART_MIN_SWITCH_INTERVAL, 0, 86400)
        requireInt(Key.GROUP_SMART_FAILURE_THRESHOLD, 1, 20)
        requireDouble(Key.GROUP_SMART_LATENCY_WEIGHT, 0.0, 1.0)
        requireDouble(Key.GROUP_SMART_JITTER_WEIGHT, 0.0, 1.0)
        requireDouble(Key.GROUP_SMART_THROUGHPUT_WEIGHT, 0.0, 1.0)
        requireDouble(Key.GROUP_SMART_SUCCESS_WEIGHT, 0.0, 1.0)
        requireDouble(Key.GROUP_SMART_STABILITY_WEIGHT, 0.0, 1.0)
        requireDouble(Key.GROUP_SMART_FAILURE_WEIGHT, 0.0, 1.0)

        val subscriptionAutoUpdate =
            findPreference<SwitchPreference>(Key.SUBSCRIPTION_AUTO_UPDATE)!!
        val subscriptionAutoUpdateDelay =
            findPreference<EditTextPreference>(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY)!!

        subscriptionAutoUpdateDelay.isEnabled = subscriptionAutoUpdate.isChecked
        subscriptionAutoUpdateDelay.setOnPreferenceChangeListener { _, newValue ->
            val delay = (newValue as String).toIntOrNull()
            if (delay == null) {
                false
            } else {
                delay >= 15
            }
        }
        subscriptionAutoUpdate.setOnPreferenceChangeListener { _, newValue ->
            subscriptionAutoUpdateDelay.isEnabled = (newValue as Boolean)
            true
        }
    }

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    (requireActivity() as GroupSettingsActivity).saveAndExit()
                }
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class GroupIdArg(val groupId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<GroupIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_group_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    GroupManager.deleteGroup(arg.groupId)
                }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "id"
    }

    @SuppressLint("CommitTransaction")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.group_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        if (savedInstanceState == null) {
            val editingId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
            DataStore.editingId = editingId
            runOnDefaultDispatcher {
                if (editingId == 0L) {
                    ProxyGroup().init()
                } else {
                    val entity = SagerDatabase.groupDao.getById(editingId)
                    if (entity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    entity.init()
                }

                onMainDispatcher {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()

                    DataStore.dirty = false
                    DataStore.profileCacheStore.registerChangeListener(this@GroupSettingsActivity)
                }
            }

        }

    }

    suspend fun saveAndExit() {

        val editingId = DataStore.editingId
        if (editingId == 0L) {
            val group = GroupManager.createGroup(ProxyGroup().apply { serialize() })
            saveSmartConfig(group)
            SmartGroupUpdater.reconfigureUpdater()
        } else if (needSave()) {
            val entity = SagerDatabase.groupDao.getById(DataStore.editingId)
            if (entity == null) {
                finish()
                return
            }
            val keepUserInfo = (entity.type == GroupType.SUBSCRIPTION &&
                    DataStore.groupType == GroupType.SUBSCRIPTION &&
                    entity.subscription?.link == DataStore.subscriptionLink)
            if (!keepUserInfo) {
                entity.subscription?.subscriptionUserinfo = "";
            }
            entity.serialize()
            GroupManager.updateGroup(entity)
            saveSmartConfig(entity)
            SmartGroupUpdater.reconfigureUpdater()
        }

        finish()

    }

    val child by lazy { supportFragmentManager.findFragmentById(R.id.settings) as MyPreferenceFragmentCompat }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = child.onOptionsItemSelected(item)

    override fun onBackPressed() {
        if (needSave()) {
            UnsavedChangesDialogFragment().apply { key() }.show(supportFragmentManager, null)
        } else super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) finish()
        return true
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        var activity: GroupSettingsActivity? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity = (requireActivity() as GroupSettingsActivity).apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "Error on createPreferences, please try again.",
                    Toast.LENGTH_SHORT
                ).show()
                Logs.e(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)
        }

        override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    requireActivity().finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(GroupIdArg(DataStore.editingId))
                        key()
                    }.show(parentFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                runOnDefaultDispatcher {
                    activity?.saveAndExit()
                }
                true
            }

            else -> false
        }

    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "\u2022".repeat(text.length)
            }
        }

    }

    val selectProfileForAddFront = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            DataStore.frontProxy = profile.id
            onMainDispatcher {
                frontProxyPreference.value = "3"
            }
        }
    }

    val selectProfileForAddLanding = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            DataStore.landingProxy = profile.id
            onMainDispatcher {
                landingProxyPreference.value = "3"
            }
        }
    }

}
