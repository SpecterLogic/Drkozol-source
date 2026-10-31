package com.v2ray.ang.handler

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.workDataOf
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.SubscriptionUpdateMessage
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.TimeUnit

object SubscriptionUpdater {
    /** A first URL import occurs after startup's empty-index sync; register its future work now. */
    internal fun afterSubscriptionImport(result: Pair<Int, Int>, schedule: () -> Unit): Pair<Int, Int> {
        // AngConfigManager counts parsed URLs separately from direct profile lines.
        if (result.second > 0) schedule()
        return result
    }

    fun enqueuePendingProbes(context: Context, replace: Boolean = false) {
        MmkvManager.decodeSettingsStringSet("dr_pending_probes").orEmpty().forEach { subId ->
            val request = androidx.work.OneTimeWorkRequestBuilder<UpdateTask>()
                .setInputData(workDataOf(KEY_SUB_ID to subId, KEY_TEST_ONLY to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresCharging(true).setRequiresDeviceIdle(true).build())
                .setInitialDelay(5, TimeUnit.SECONDS)
                .build()
            RemoteWorkManager.getInstance(context).enqueueUniqueWork("dr_pending_test_" + subId,
                if (replace) androidx.work.ExistingWorkPolicy.REPLACE else androidx.work.ExistingWorkPolicy.KEEP, listOf(request))
        }
    }

    // -------------------------------------------------------------------------
    // Public API — the only methods external callers should ever use
    // -------------------------------------------------------------------------

    /**
     * Sync all subscription tasks with current settings.
     *
     * Startup/boot callers should use the default mode so existing periodic work is kept.
     * Use forceReschedule=true only when the next run time needs to be recalculated from
     * the latest persisted subscription state (for example after a manual refresh).
     * Call from: MainActivity.onCreate(), BootReceiver.onReceive().
     */
    fun sync(
        context: Context = AngApplication.application,
        forceReschedule: Boolean = false
    ) {
        HappRoutingStore.syncAutoUpdates(context)
        // Replace preceding unconstrained test jobs once; subsequent syncs keep queued work.
        val schedulingUpgrade = !MmkvManager.decodeSettingsBool("dr_quiet_tests_145_migrated", false)
        if (schedulingUpgrade) {
            enqueuePendingProbes(context, replace = true)
            check(MmkvManager.encodeSettings("dr_quiet_tests_145_migrated", true))
        }
        val existingWorkPolicy =
            if (forceReschedule || schedulingUpgrade) {
                ExistingPeriodicWorkPolicy.REPLACE
            } else {
                ExistingPeriodicWorkPolicy.KEEP
            }

        MmkvManager.decodeSubscriptions()
            .forEach { sub ->
                scheduleOne(
                    context = context,
                    subId = sub.guid,
                    existingWorkPolicy = existingWorkPolicy
                )
            }
        LogUtil.i(
            AppConfig.TAG,
            "SubscriptionUpdater: sync complete forceReschedule=$forceReschedule"
        )
    }

    /**
     * Sync a single subscription's task.
     * Call from: SubEditActivity after saving, after a manual update (to reset the timer).
     */
    fun syncOne(context: Context = AngApplication.application, subId: String) {
        scheduleOne(
            context = context,
            subId = subId,
            existingWorkPolicy = ExistingPeriodicWorkPolicy.REPLACE
        )
    }

    /**
     * Cancel the auto-update task for a single subscription.
     * Call from: when a subscription is deleted.
     */
    fun cancelOne(context: Context = AngApplication.application, subId: String) {
        RemoteWorkManager.getInstance(context)
            .cancelUniqueWork(taskName(subId))
    }

    /**
     * Update the last updated timestamp and reschedule the task.
     * This is used to reset the periodic timer and prevent rapid rescheduling loops.
     */
    fun updateLastUpdatedAndReschedule(context: Context = AngApplication.application, subId: String) {
        val subItem = MmkvManager.decodeSubscription(subId) ?: return
        subItem.lastUpdated = System.currentTimeMillis()
        MmkvManager.encodeSubscription(subId, subItem)
        syncOne(context, subId)
    }

    // -------------------------------------------------------------------------
    // Internal scheduling logic
    // -------------------------------------------------------------------------

    private fun taskName(subId: String) = "${AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME}_$subId"

    private fun scheduleOne(
        context: Context,
        subId: String,
        existingWorkPolicy: ExistingPeriodicWorkPolicy
    ) {
        val subItem = MmkvManager.decodeSubscription(subId) ?: return
        val rw = RemoteWorkManager.getInstance(context)
        if (!subItem.autoUpdate || !SettingsManager.clientPreferences().enabled(ClientOption.AUTO_UPDATE)) {
            cancelOne(context, subId)
            LogUtil.d(AppConfig.TAG, "SubscriptionUpdater: cancelled task for ${subItem.remarks}")
            return
        }

        if (subItem.url.isEmpty()) {
            LogUtil.i(AppConfig.TAG, "SubscriptionUpdater: url isEmpty for ${subItem.remarks}, skip")
            return
        }

        val intervalMinutes = effectiveUpdateMinutes(subItem.customUpdateInterval,
            subItem.updateInterval, SettingsManager.clientPreferences().updateMinutes)

        // Base initial delay on the last successful update time persisted in subscription.
        val lastUpdated = subItem.lastUpdated
        val intervalMillis = intervalMinutes * 60 * 1000L
        val now = System.currentTimeMillis()
        var initialDelayMillis = if (lastUpdated <= 0L) {
            0L
        } else {
            maxOf(0L, lastUpdated + intervalMillis - now)
        }

        // Add a small floor to initial delay to prevent rapid rescheduling loops.
        if (existingWorkPolicy == ExistingPeriodicWorkPolicy.REPLACE && initialDelayMillis < 5000L) {
            initialDelayMillis = 5000L
        }

        val request = PeriodicWorkRequestBuilder<UpdateTask>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf(KEY_SUB_ID to subId))
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .addTag(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
            .build()

        rw.enqueueUniquePeriodicWork(
            taskName(subId),
            existingWorkPolicy,
            request
        )

        LogUtil.i(
            AppConfig.TAG,
            "SubscriptionUpdater: scheduled [${subItem.remarks}] interval=${intervalMinutes}min " +
                    "initialDelay=${initialDelayMillis / 1000}s policy=$existingWorkPolicy"
        )
    }

    // -------------------------------------------------------------------------
    // Worker
    // -------------------------------------------------------------------------

    private const val KEY_SUB_ID = "subId"
    private const val KEY_TEST_ONLY = "testOnly"

    class UpdateTask(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

        override suspend fun doWork(): Result {
            val subId = inputData.getString(KEY_SUB_ID)
            LogUtil.i(AppConfig.TAG, "SubscriptionUpdater update starting via Service: $subId")

            if (subId.isNullOrEmpty()) {
                LogUtil.w(AppConfig.TAG, "SubscriptionUpdater: missing subId in worker input")
                return Result.success()
            }

            val subscription = MmkvManager.decodeSubscription(subId)
            val testOnly = inputData.getBoolean(KEY_TEST_ONLY, false)
            if (subscription == null || !subscription.enabled || subscription.url.isBlank()) return Result.success()
            if (testOnly) {
                if (subId !in MmkvManager.decodeSettingsStringSet("dr_pending_probes").orEmpty()) return Result.success()
                if (!SettingsManager.clientPreferences().enabled(ClientOption.PING_UPDATE)) return Result.success()
                if (!canRunAutomaticProbe(
                        applicationContext.getSystemService(android.os.PowerManager::class.java).isInteractive,
                        applicationContext.getSystemService(android.os.BatteryManager::class.java).isCharging,
                        SettingsManager.hasActiveVpn(applicationContext)) || SettingsManager.backgroundProbeActive(applicationContext)) return Result.retry()
            } else if (!subscription.autoUpdate || !SettingsManager.clientPreferences().enabled(ClientOption.AUTO_UPDATE)) return Result.success()

            setForeground(com.v2ray.ang.helper.NotificationHelper.subscriptionForegroundInfo(applicationContext))

            MessageHelper.sendMsg2SubscriptionService(
                applicationContext,
                SubscriptionUpdateMessage(AppConfig.MSG_SUB_UPDATE_START, !testOnly, listOf(subId), testOnly)
            )

            return if (testOnly) Result.retry() else Result.success()
        }
    }
}
