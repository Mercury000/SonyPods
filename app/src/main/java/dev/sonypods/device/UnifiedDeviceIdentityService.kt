package dev.sonypods.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified device identity service for LE/Classic judgment.
 *
 * This service provides a single source of truth for determining whether a Bluetooth device
 * address represents an LE Audio identity, a Classic identity, or both.
 *
 * ## Priority
 * 1. Remote Preferences (module-written during pairing)
 * 2. bt_config.conf analysis (fallback for pre-paired devices)
 *
 * ## Data Flow
 * - **Pairing time**: `BluetoothDevice.type` is reliable → write to Remote Preferences
 * - **Pre-paired devices**: bt_config.conf analysis → engine-side cache → sync to Remote Preferences when module foreground
 *
 * ## Architecture
 * - Remote Preferences: writable only by module app (foreground), readable by all processes
 * - Engine-side cache: local storage in bluetooth process, synced to Remote Preferences
 */
@SuppressLint("MissingPermission")
object UnifiedDeviceIdentityService {
    private const val TAG = "SonyPods-Engine"

    /** Remote Preferences key for device identities JSON. */
    private const val STORE_FILE = "sonypods_device_identities.txt"

    /** Local cache for engine-side storage (bluetooth process). */
    private val localCache = ConcurrentHashMap<String, DeviceIdentity>()

    /** In-memory cache loaded from Remote Preferences. */
    @Volatile
    private var remoteIdentities: Map<String, DeviceIdentity> = emptyMap()

    /**
     * Where the classifier's own process persists what it resolved.
     *
     * Only the Bluetooth process can classify — pairing states the direction, and bt_config key
     * material is readable nowhere else — so it is also the only process that has anything worth
     * storing. It writes into its own data directory; every other process is handed the result
     * through [dev.sonypods.bridge.SonyStateSnapshot.identityPairs] and never persists.
     */
    @Volatile
    private var store: java.io.File? = null

    // ---- Initialization ----

    /**
     * Bind the classifier's persistent store. Only the process that classifies passes a
     * context; consumers call this with null and live off the snapshot.
     */
    fun initializeForEngine(engineContext: Context? = null) {
        store = engineContext?.let { java.io.File(it.filesDir, STORE_FILE) }
        load()
        log("initialized with ${remoteIdentities.size} identities")
    }

    // ---- Identity Query API ----

    /**
     * Get the identity type for a given address.
     * Returns [IdentityType.UNKNOWN] if no information is available.
     */
    fun getIdentityType(address: String): IdentityType {
        val normalized = normalizeAddress(address) ?: return IdentityType.UNKNOWN

        // 1. Check Remote Preferences
        remoteIdentities[normalized]?.let { return it.type }

        // 2. Check engine-side cache
        localCache[normalized]?.let { return it.type }

        // 3. Fallback: analyze bt_config.conf
        analyzeBtConfig(normalized)?.let { identity ->
            localCache[normalized] = identity
            return identity.type
        }

        return IdentityType.UNKNOWN
    }

    /**
     * Get the full identity for a given address.
     */
    fun getIdentity(address: String): DeviceIdentity? {
        val normalized = normalizeAddress(address) ?: return null

        // 1. Check Remote Preferences
        remoteIdentities[normalized]?.let { return it }

        // 2. Check engine-side cache
        localCache[normalized]?.let { return it }

        // 3. Fallback: analyze bt_config.conf
        analyzeBtConfig(normalized)?.let { identity ->
            localCache[normalized] = identity
            return identity
        }

        return null
    }

    /**
     * Check if an address is the LE Audio identity.
     */
    fun isLeAudioIdentity(address: String): Boolean =
        getIdentityType(address) == IdentityType.LE

    /**
     * Check if an address is the Classic identity.
     */
    fun isClassicIdentity(address: String): Boolean =
        getIdentityType(address) == IdentityType.CLASSIC

    /**
     * Check if an address belongs to a dual-mode device.
     */
    fun isDualModeDevice(address: String): Boolean {
        val identity = getIdentity(address) ?: return false
        return identity.pairedAddress != null
    }

    /**
     * Resolve the control address for a given address.
     * Returns the address unchanged if no mapping exists.
     */
    fun resolveControlAddress(address: String): String {
        val normalized = normalizeAddress(address) ?: return address
        val identity = getIdentity(normalized) ?: return address
        return identity.controlAddress ?: address
    }

    /**
     * Resolve the LE address for a given address.
     * Returns the address unchanged if no mapping exists.
     */
    fun leAudioAddressFor(address: String): String? {
        val normalized = normalizeAddress(address) ?: return null
        val identity = getIdentity(normalized) ?: return null
        return identity.leAddress
    }

    /**
     * Get both identities (LE + Control) for a given address.
     */
    fun identityPairFor(address: String): Pair<String, String>? {
        val normalized = normalizeAddress(address) ?: return null
        val identity = getIdentity(normalized) ?: return null
        val paired = identity.pairedAddress ?: return null
        val le = identity.leAddress ?: return null
        val control = identity.controlAddress ?: return null
        return le to control
    }

    /**
     * Whether the stack holds LE keys for [address].
     *
     * Reads directly from bt_config.conf (bluetooth process only).
     * This is used by LeAudioDevicePairer for CTKD re-pair decisions.
     */
    fun hasLeKeys(address: String): Boolean {
        val normalized = normalizeAddress(address) ?: return true
        return try {
            val configPath = "/data/misc/bluedroid/bt_config.conf"
            val file = java.io.File(configPath)
            if (!file.exists()) return true

            var hasLeKeys = false
            var inSection = false

            file.useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                        inSection = trimmed.equals("[${normalized.trim()}]", ignoreCase = true)
                        continue
                    }
                    if (inSection && trimmed.startsWith("LE_KEY_")) {
                        hasLeKeys = true
                        break
                    }
                }
            }

            hasLeKeys
        } catch (e: Exception) {
            // Unknown reads as "present" so an unreadable key store never triggers a
            // destructive re-pair; the worst case is the same rejection the user already sees.
            true
        }
    }

    // ---- Identity Recording API ----

    /**
     * Record identity from BluetoothDevice.type during pairing.
     * This is the most reliable source and writes directly to Remote Preferences.
     */
    @SuppressLint("MissingPermission")
    fun recordFromPairing(device: BluetoothDevice, pairedDevice: BluetoothDevice? = null) {
        val address = normalizeAddress(device.address) ?: return
        val transport = runCatching { device.type }.getOrDefault(0)
        val name = runCatching { device.name }.getOrNull()

        val type = when (transport) {
            2 -> IdentityType.LE           // DEVICE_TYPE_LE
            1 -> IdentityType.CLASSIC      // DEVICE_TYPE_CLASSIC
            3 -> IdentityType.DUAL         // DEVICE_TYPE_DUAL
            else -> IdentityType.UNKNOWN
        }

        if (type == IdentityType.UNKNOWN) {
            log("unknown transport type for $address: $transport")
            return
        }

        val pairedAddress = pairedDevice?.let { normalizeAddress(it.address) }

        val identity = DeviceIdentity(
            address = address,
            type = type,
            pairedAddress = pairedAddress,
            name = name,
            source = IdentitySource.PAIRING,
        )

        recordIdentity(identity)
    }

    /**
     * Record identity from bt_config.conf analysis.
     * Used for pre-paired devices that weren't paired through the module.
     */
    fun recordFromBtConfig(address: String, hasLeKeys: Boolean, hasClassicKey: Boolean, name: String? = null) {
        val normalized = normalizeAddress(address) ?: return

        val type = when {
            hasLeKeys && hasClassicKey -> IdentityType.DUAL
            hasLeKeys -> IdentityType.LE
            hasClassicKey -> IdentityType.CLASSIC
            else -> IdentityType.UNKNOWN
        }

        if (type == IdentityType.UNKNOWN) {
            log("bt_config analysis inconclusive for $address (leKeys=$hasLeKeys, classicKey=$hasClassicKey)")
            return
        }

        val identity = DeviceIdentity(
            address = normalized,
            type = type,
            name = name,
            source = IdentitySource.BT_CONFIG,
        )

        recordIdentity(identity)
    }

    /**
     * Record identity pairing (LE address ↔ Control address mapping).
     */
    fun recordIdentityPair(leAddress: String, controlAddress: String) {
        val le = normalizeAddress(leAddress) ?: return
        val control = normalizeAddress(controlAddress) ?: return
        if (le == control) return

        // Record LE identity (type=LE, pairedAddress points to control)
        val leIdentity = DeviceIdentity(
            address = le,
            type = IdentityType.LE,
            pairedAddress = control,
            source = IdentitySource.PAIRING,
        )
        recordIdentity(leIdentity)

        // Record Control identity (type=CLASSIC, pairedAddress points to LE)
        val controlIdentity = DeviceIdentity(
            address = control,
            type = IdentityType.CLASSIC,
            pairedAddress = le,
            source = IdentitySource.PAIRING,
        )
        recordIdentity(controlIdentity)
    }

    // ---- Internal Recording ----

    private fun recordIdentity(identity: DeviceIdentity) {
        // PAIRING outranks BT_CONFIG. Pairing states the direction outright — it is the only
        // moment the two identities are both in hand — while bt_config is read per address and
        // cannot see the pairing. Letting the weaker source overwrite is what allowed a later
        // pass to invert a pair the pairing flow had already recorded correctly.
        val existing = localCache[identity.address] ?: remoteIdentities[identity.address]
        if (existing != null && existing.source == IdentitySource.PAIRING &&
            identity.source != IdentitySource.PAIRING
        ) {
            return
        }
        // Update local cache
        localCache[identity.address] = identity

        // Update in-memory cache
        remoteIdentities = remoteIdentities + (identity.address to identity)

        // If module is in foreground, write to Remote Preferences immediately
        persist()

        log("recorded identity: ${identity.address} -> ${identity.type} (source=${identity.source})")
    }

    // ---- bt_config.conf Analysis ----

    /**
     * Analyze bt_config.conf for identity information.
     * This is the fallback for pre-paired devices.
     */
    private fun analyzeBtConfig(address: String): DeviceIdentity? {
        val configPath = "/data/misc/bluedroid/bt_config.conf"
        return try {
            val file = java.io.File(configPath)
            if (!file.exists()) return null

            var hasLeKeys = false
            var hasClassicKey = false
            var deviceName: String? = null
            var inSection = false

            file.useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                        inSection = trimmed.equals("[${address.trim()}]", ignoreCase = true)
                        continue
                    }
                    if (inSection) {
                        when {
                            trimmed.startsWith("LE_KEY_") -> hasLeKeys = true
                            trimmed.startsWith("LinkKey") -> hasClassicKey = true
                            trimmed.startsWith("Name = ") -> {
                                deviceName = trimmed.removePrefix("Name = ").trim()
                            }
                        }
                    }
                }
            }

            if (!hasLeKeys && !hasClassicKey) return null

            val type = when {
                hasLeKeys && hasClassicKey -> IdentityType.DUAL
                hasLeKeys -> IdentityType.LE
                hasClassicKey -> IdentityType.CLASSIC
                else -> return null
            }

            DeviceIdentity(
                address = address,
                type = type,
                name = deviceName,
                source = IdentitySource.BT_CONFIG,
            )
        } catch (e: Exception) {
            log("failed to analyze bt_config.conf for $address: ${e.message}")
            null
        }
    }

    // ---- UUID Analysis Helpers ----

    private val ASCS_SERVICE = java.util.UUID.fromString("0000184E-0000-1000-8000-00805F9B34FB")

    private val sonyServiceUuids: Set<java.util.UUID> = setOf(
        java.util.UUID.fromString("00001108-0000-1000-8000-00805F9B34FB"), // IAP
        java.util.UUID.fromString("0000110B-0000-1000-8000-00805F9B34FB"), // A2DP
        java.util.UUID.fromString("0000110E-0000-1000-8000-00805F9B34FB"), // AVRCP
        java.util.UUID.fromString("0000111E-0000-1000-8000-00805F9B34FB"), // HFP
        java.util.UUID.fromString("443cce33-e85d-4b85-8d53-6e319ede53ae"), // Sony Tandem
        java.util.UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2"), // Sony private
    )

    private fun isLeAudioIdentityFromUuids(serviceUuids: List<java.util.UUID>): Boolean {
        if (serviceUuids.isEmpty()) return false
        return ASCS_SERVICE in serviceUuids && serviceUuids.none { it in sonyServiceUuids }
    }

    private fun isClassicIdentityFromUuids(serviceUuids: List<java.util.UUID>): Boolean {
        if (serviceUuids.isEmpty()) return false
        return serviceUuids.any { it in sonyServiceUuids }
    }

    // ---- Persistence (classifier process only) ----

    private fun load() {
        val file = store ?: return
        val serialized = runCatching { if (file.isFile) file.readText() else null }.getOrNull() ?: return
        val identities = mutableMapOf<String, DeviceIdentity>()
        serialized.lines().forEach { line ->
            if (line.isBlank()) return@forEach
            DeviceIdentity.deserialize(line)?.let { identities[it.address] = it }
        }
        remoteIdentities = identities
    }

    private fun persist() {
        val file = store ?: return
        val serialized = buildString {
            remoteIdentities.values.forEach { identity -> appendLine(identity.serialize()) }
        }
        runCatching { file.writeText(serialized) }
            .onFailure { log("persist failed: ${it.javaClass.simpleName}") }
    }


    // ---- Snapshot API ----

    /**
     * Get a snapshot of all known identities.
     */
    fun identitySnapshot(): Map<String, DeviceIdentity> =
        remoteIdentities + localCache

    /**
     * Get all known address pairs (LE ↔ Control).
     */
    /**
     * Every known pair as `LE>control`, for [dev.sonypods.bridge.SonyStateSnapshot].
     *
     * Only the Bluetooth process can classify (pairing flow, or bt_config key material), so
     * this is how the answer reaches every other process.
     */
    fun leToControlPairs(): List<String> =
        (remoteIdentities + localCache).values
            .mapNotNull { identity ->
                val paired = identity.pairedAddress ?: return@mapNotNull null
                when (identity.type) {
                    IdentityType.LE -> "${identity.address}>$paired"
                    IdentityType.CLASSIC, IdentityType.DUAL -> "$paired>${identity.address}"
                    else -> null
                }
            }
            .distinct()

    /** Adopt the pairs a snapshot carried. Recorded as PAIRING: the engine already classified. */
    fun ingestPairs(pairs: List<String>) {
        pairs.forEach { entry ->
            val le = normalizeAddress(entry.substringBefore('>')) ?: return@forEach
            val control = normalizeAddress(entry.substringAfter('>', "")) ?: return@forEach
            if (le == control) return@forEach
            if (getIdentity(le)?.pairedAddress == control) return@forEach
            recordIdentityPair(le, control)
        }
    }

    fun addressPairs(): List<Pair<String, String>> {
        val seen = mutableSetOf<String>()
        val pairs = mutableListOf<Pair<String, String>>()

        (remoteIdentities + localCache).values.forEach { identity ->
            if (identity.pairedAddress != null) {
                val key = listOf(identity.address, identity.pairedAddress).sorted().joinToString("|")
                if (key !in seen) {
                    seen.add(key)
                    pairs.add(identity.address to identity.pairedAddress)
                }
            }
        }

        return pairs
    }

    // ---- Utility ----

    private fun normalizeAddress(address: String?): String? {
        val normalized = address?.trim()?.uppercase(Locale.ROOT) ?: return null
        return normalized.takeIf { it.matches(BLUETOOTH_ADDRESS) }
    }

    private val BLUETOOTH_ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

    private fun log(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    /**
     * Reset all state. Only for unit tests.
     */
    internal fun resetForTesting() {
        localCache.clear()
        remoteIdentities = emptyMap()
        store = null
    }
}
