package dev.sonypods.hook

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import dev.sonypods.bridge.SonyBridge
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Gives the official Sound Connect app exclusive ownership of the Sony Tandem
 * control session while its UI, keep-connection service, or an actual MDR
 * connection is active.
 *
 * The hand-over is fully event driven. A Binder token accompanies each lease so
 * the bluetooth-process engine is also notified immediately if Sound Connect is
 * killed or crashes before lifecycle callbacks can release the lease.
 */
object SoundConnectHandoverHook : HookContext() {
    private const val TAG = "SonyPods-SoundConnect"
    private const val KEEP_CONNECTION_SERVICE =
        "com.sony.songpal.mdr.service.KeepConnectionForegroundService"
    private const val MDR_CONNECTION_CONTROLLER =
        "com.sony.songpal.mdr.platform.connection.connection.p0"
    private const val RELEASE_GRACE_MS = 2_000L

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
                runCatching { install(application) }
                    .onFailure { Log.w(TAG, "failed to initialize Sound Connect handover", it) }
            }
        }.onFailure { Log.w(TAG, "failed to install Sound Connect handover hook", it) }
    }

    @Synchronized
    private fun install(application: Application) {
        if (installed) return
        val processName = runCatching { Application.getProcessName() }.getOrNull()
        if (processName != SonyBridge.OFFICIAL_APP_PACKAGE) {
            Log.d(TAG, "ignoring non-main Sound Connect process=$processName")
            return
        }

        val coordinator = LeaseCoordinator(application)
        application.registerActivityLifecycleCallbacks(coordinator)
        application.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == SonyBridge.ACTION_ENGINE_READY) {
                        coordinator.onEngineReady("bluetooth-engine-ready")
                    }
                }
            },
            IntentFilter(SonyBridge.ACTION_ENGINE_READY),
            Context.RECEIVER_EXPORTED,
        )
        installKeepConnectionServiceHooks(coordinator)
        installMdrSessionHooks(coordinator)
        installed = true
        Log.d(TAG, "handover hooks registered process=$processName")
    }

    /** Stable fallback for versions whose obfuscated connection-controller names change. */
    private fun installKeepConnectionServiceHooks(coordinator: LeaseCoordinator) {
        runCatching {
            val onBind = findMethod(KEEP_CONNECTION_SERVICE, "onBind", Intent::class.java)
            val onStartCommand = findMethod(
                KEEP_CONNECTION_SERVICE,
                "onStartCommand",
                Intent::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
            val onDestroy = findMethod(KEEP_CONNECTION_SERVICE, "onDestroy")
            hookBefore(onBind) {
                coordinator.setKeepConnectionServiceActive(
                    instance,
                    active = true,
                    reason = "keep-connection-service-bound",
                )
            }
            hookBefore(onStartCommand) {
                coordinator.setKeepConnectionServiceActive(
                    instance,
                    active = true,
                    reason = "keep-connection-service-started",
                )
            }
            hookAfter(onDestroy) {
                coordinator.setKeepConnectionServiceActive(
                    instance,
                    active = false,
                    reason = "keep-connection-service-destroyed",
                )
            }
            Log.d(TAG, "KeepConnectionForegroundService hooks installed")
        }.onFailure { Log.w(TAG, "KeepConnectionForegroundService hooks unavailable", it) }
    }

    /**
     * Sound Connect 13.2.1 stores live MDR sessions in p0's holder map. These
     * obfuscated hooks are best-effort: the stable foreground-service hook above
     * continues to protect background ownership if a future release renames them.
     */
    private fun installMdrSessionHooks(coordinator: LeaseCoordinator) {
        runCatching {
            // Resolve the complete set before installing any one hook. A partial set
            // could observe an add but miss every removal and hold the lease forever.
            val addSession = findMethodByParamCount(MDR_CONNECTION_CONTROLLER, "r1", 2)
            val removeSession = findMethodByParamCount(MDR_CONNECTION_CONTROLLER, "u1", 1)
            val clearSessions = findMethodByParamCount(MDR_CONNECTION_CONTROLLER, "V0", 0)
            findMethodByParamCount(MDR_CONNECTION_CONTROLLER, "z0", 0)
            hookAfter(addSession) {
                coordinator.refreshMdrSession(instance, "mdr-session-added")
            }
            hookAfter(removeSession) {
                coordinator.refreshMdrSession(instance, "mdr-session-removed")
            }
            hookAfter(clearSessions) {
                coordinator.refreshMdrSession(instance, "all-mdr-sessions-removed")
            }
            Log.d(TAG, "MDR session hooks installed controller=$MDR_CONNECTION_CONTROLLER")
        }.onFailure { Log.w(TAG, "MDR session hooks unavailable; using lifecycle/service fallback", it) }
    }

    private class LeaseCoordinator(
        private val application: Application,
    ) : Application.ActivityLifecycleCallbacks {
        private val handler = Handler(Looper.getMainLooper())
        private val creatingActivities = identitySet<Activity>()
        private val startedActivities = identitySet<Activity>()
        private val activeKeepConnectionServices = identitySet<Any>()
        private val activeMdrControllers = identitySet<Any>()

        private var pendingRelease: Runnable? = null
        private var leaseId: String? = null
        private var leaseToken: IBinder? = null

        /** Acquire before Activity.onCreate so Sound Connect cannot race our UI lifecycle. */
        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
            synchronized(this) {
                creatingActivities += activity
                reconcileLocked("activity-pre-created:${activity.javaClass.name}")
            }
        }

        override fun onActivityStarted(activity: Activity) {
            synchronized(this) {
                creatingActivities -= activity
                startedActivities += activity
                reconcileLocked("activity-started:${activity.javaClass.name}")
            }
        }

        override fun onActivityStopped(activity: Activity) {
            synchronized(this) {
                startedActivities -= activity
                reconcileLocked("activity-stopped:${activity.javaClass.name}")
            }
        }

        override fun onActivityDestroyed(activity: Activity) {
            synchronized(this) {
                // Covers an Activity that was created but failed/finished before onStart.
                creatingActivities -= activity
                startedActivities -= activity
                reconcileLocked("activity-destroyed:${activity.javaClass.name}")
            }
        }

        fun setKeepConnectionServiceActive(instance: Any?, active: Boolean, reason: String) {
            if (instance == null) return
            synchronized(this) {
                if (active) activeKeepConnectionServices += instance
                else activeKeepConnectionServices -= instance
                reconcileLocked(reason)
            }
        }

        fun refreshMdrSession(instance: Any?, reason: String) {
            if (instance == null) return
            val active = runCatching { callMethod(instance, "z0") != null }
                .onFailure { Log.w(TAG, "failed to inspect Sound Connect MDR sessions", it) }
                .getOrNull() ?: return
            synchronized(this) {
                if (active) activeMdrControllers += instance else activeMdrControllers -= instance
                reconcileLocked("$reason active=$active")
            }
        }

        fun onEngineReady(reason: String) {
            synchronized(this) {
                // A pending release is deliberately still protected during its grace period.
                if (hasActiveHoldLocked() || pendingRelease != null) {
                    reassertLocked(reason)
                } else {
                    releaseLocked("$reason-without-active-hold")
                }
            }
        }

        private fun reconcileLocked(reason: String) {
            if (hasActiveHoldLocked()) {
                cancelPendingReleaseLocked()
                acquireLocked(reason)
                return
            }
            scheduleReleaseLocked(reason)
        }

        private fun hasActiveHoldLocked(): Boolean =
            creatingActivities.isNotEmpty() ||
                startedActivities.isNotEmpty() ||
                activeKeepConnectionServices.isNotEmpty() ||
                activeMdrControllers.isNotEmpty()

        private fun holdSummaryLocked(): String =
            "creating=${creatingActivities.size} started=${startedActivities.size} " +
                "service=${activeKeepConnectionServices.size} session=${activeMdrControllers.size}"

        private fun scheduleReleaseLocked(reason: String) {
            if (leaseToken == null || pendingRelease != null) return
            val release = object : Runnable {
                override fun run() {
                    synchronized(this@LeaseCoordinator) {
                        if (pendingRelease !== this || hasActiveHoldLocked()) return
                        pendingRelease = null
                        releaseLocked("grace-expired:$reason")
                    }
                }
            }
            pendingRelease = release
            handler.postDelayed(release, RELEASE_GRACE_MS)
            Log.d(TAG, "official app lease release scheduled reason=$reason ${holdSummaryLocked()}")
        }

        private fun cancelPendingReleaseLocked() {
            val release = pendingRelease ?: return
            handler.removeCallbacks(release)
            pendingRelease = null
            Log.d(TAG, "official app lease release cancelled ${holdSummaryLocked()}")
        }

        private fun acquireLocked(reason: String) {
            if (leaseId != null && leaseToken != null) return

            val token = Binder()
            val id = "${Process.myPid()}:${UUID.randomUUID()}"
            if (SonyBridge.acquireOfficialAppLease(application, id, token)) {
                leaseId = id
                leaseToken = token
                Log.d(TAG, "official app lease acquired id=$id reason=$reason ${holdSummaryLocked()}")
            } else {
                Log.w(TAG, "official app lease acquire broadcast failed reason=$reason")
            }
        }

        private fun reassertLocked(reason: String) {
            val id = leaseId
            val token = leaseToken
            if (id == null || token == null) {
                acquireLocked(reason)
                return
            }
            if (SonyBridge.acquireOfficialAppLease(application, id, token)) {
                Log.d(TAG, "official app lease reasserted id=$id reason=$reason ${holdSummaryLocked()}")
            } else {
                Log.w(TAG, "official app lease reassert broadcast failed id=$id reason=$reason")
            }
        }

        private fun releaseLocked(reason: String) {
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

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        companion object {
            private fun <T : Any> identitySet(): MutableSet<T> =
                Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
        }
    }
}
