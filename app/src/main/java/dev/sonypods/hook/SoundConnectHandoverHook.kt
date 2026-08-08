package dev.sonypods.hook

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import dev.sonypods.bridge.SonyBridge
import java.util.UUID

/**
 * Gives the official Sound Connect app exclusive ownership of the Sony Tandem
 * control session while one of its activities is visible.
 *
 * The hand-over is fully event driven. A Binder token accompanies each lease so
 * the bluetooth-process engine is also notified immediately if Sound Connect is
 * killed or crashes before lifecycle callbacks can release the lease.
 */
object SoundConnectHandoverHook : HookContext() {
    private const val TAG = "SonyPods-SoundConnect"

    @Volatile
    private var installed = false

    override fun onHook() {
        runCatching {
            hookBefore(
                findMethod(
                    "android.app.Instrumentation",
                    "callApplicationOnCreate",
                    Application::class.java,
                )
            ) {
                val application = args.firstOrNull() as? Application ?: return@hookBefore
                install(application)
            }
        }.onFailure { Log.w(TAG, "failed to install Sound Connect lifecycle hook", it) }
    }

    @Synchronized
    private fun install(application: Application) {
        if (installed) return
        val processName = runCatching { Application.getProcessName() }.getOrNull()
        if (processName != SonyBridge.OFFICIAL_APP_PACKAGE) {
            Log.d(TAG, "ignoring non-main Sound Connect process=$processName")
            return
        }

        val callbacks = ForegroundLeaseCallbacks(application)
        application.registerActivityLifecycleCallbacks(callbacks)
        application.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == SonyBridge.ACTION_ENGINE_READY) {
                        callbacks.reassertLease("bluetooth-engine-ready")
                    }
                }
            },
            IntentFilter(SonyBridge.ACTION_ENGINE_READY),
            Context.RECEIVER_EXPORTED,
        )
        installed = true
        Log.d(TAG, "foreground handover callbacks registered process=$processName")
    }

    private class ForegroundLeaseCallbacks(
        private val application: Application,
    ) : Application.ActivityLifecycleCallbacks {
        private var startedActivityCount = 0
        private var leaseId: String? = null
        private var leaseToken: IBinder? = null

        /** Acquire before Activity.onCreate so Sound Connect never races our UI lifecycle. */
        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
            acquire("activity-pre-created:${activity.javaClass.name}")
        }

        override fun onActivityStarted(activity: Activity) {
            startedActivityCount += 1
            acquire("activity-started:${activity.javaClass.name}")
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
            if (startedActivityCount == 0 && !activity.isChangingConfigurations) {
                release("last-activity-stopped:${activity.javaClass.name}")
            }
        }

        override fun onActivityDestroyed(activity: Activity) {
            // Covers an Activity that was created but failed/finished before onStart.
            if (startedActivityCount == 0 && !activity.isChangingConfigurations) {
                release("last-activity-destroyed:${activity.javaClass.name}")
            }
        }

        private fun acquire(reason: String) {
            if (leaseToken != null) return
            val token = Binder()
            val id = "${Process.myPid()}:${UUID.randomUUID()}"
            if (SonyBridge.acquireOfficialAppLease(application, id, token)) {
                leaseId = id
                leaseToken = token
                Log.d(TAG, "official app lease acquired id=$id reason=$reason")
            } else {
                Log.w(TAG, "official app lease acquire broadcast failed reason=$reason")
            }
        }

        private fun release(reason: String) {
            val id = leaseId ?: return
            val token = leaseToken ?: return
            if (SonyBridge.releaseOfficialAppLease(application, id, token)) {
                leaseId = null
                leaseToken = null
                Log.d(TAG, "official app lease released id=$id reason=$reason")
            } else {
                Log.w(TAG, "official app lease release broadcast failed id=$id reason=$reason")
            }
        }

        fun reassertLease(reason: String) {
            val id = leaseId ?: return
            val token = leaseToken ?: return
            if (SonyBridge.acquireOfficialAppLease(application, id, token)) {
                Log.d(TAG, "official app lease reasserted id=$id reason=$reason")
            } else {
                Log.w(TAG, "official app lease reassert broadcast failed id=$id reason=$reason")
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}
