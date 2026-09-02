package dev.sonypods.config

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * SQLite persistence for the capability probe cache, byte-for-byte the shape of
 * Sound Connect's `CapabilityStorageAndroid` (`exchanged_capabilities` table).
 *
 * One row per (identifier, store_group, command_table_number); `capabilities`
 * holds the raw RET_CAPABILITY command bytes (base64) that the capability
 * tableset was built from, so a cache hit can rebuild it exactly as a live
 * probe would — never a derived/parsed view that can lose a field.
 *
 * `store_group` distinguishes the two Tandem generations (V1=0, V2=1) exactly
 * as SC passes `m65484a(str, 0/1, ...)`; `command_table_number` is the MDR
 * command table (Table1/Table2). Writes replace the whole row for a key, and
 * nothing is ever deleted — SC never clears this table either.
 */
class CapabilityStorage(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val helper = object : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE_NAME (" +
                    "identifier TEXT, store_group INTEGER, command_table_number INTEGER, " +
                    "capability_counter INTEGER, capabilities TEXT, " +
                    "UNIQUE(identifier, store_group, command_table_number))",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    /** The capability_counter row for a key, or -1 when absent (SC: `-1`).
     *
     * Reads and writes are serialised the way SC's accessors are (all three of its
     * `CapabilityStorageAndroid` methods are `synchronized`): the probe records off
     * the transport thread while a restore may be reading. */
    @Synchronized
    fun readCounter(identifier: String, storeGroup: Int, tableNumber: Int): Int {
        val db = helper.readableDatabase
        try {
            db.query(
                TABLE_NAME,
                arrayOf(COL_CAPABILITY_COUNTER),
                "$COL_IDENTIFIER = ? AND $COL_STORE_GROUP = ? AND $COL_TABLE_NUMBER = ?",
                arrayOf(identifier, storeGroup.toString(), tableNumber.toString()),
                null, null, null,
            ).use { cursor ->
                return if (cursor.moveToNext()) cursor.getInt(0) else -1
            }
        } catch (_: SQLiteException) {
            return -1
        }
    }

    /** Raw capability command payloads for a row. Each payload begins with the
     * command byte, exactly what SC's `C15171e.encodedPayload` contains. */
    @Synchronized
    fun readCapabilities(identifier: String, storeGroup: Int, tableNumber: Int): List<ByteArray>? {
        val db = helper.readableDatabase
        try {
            db.query(
                TABLE_NAME,
                arrayOf(COL_CAPABILITIES),
                "$COL_IDENTIFIER = ? AND $COL_STORE_GROUP = ? AND $COL_TABLE_NUMBER = ?",
                arrayOf(identifier, storeGroup.toString(), tableNumber.toString()),
                null, null, null,
            ).use { cursor ->
                if (!cursor.moveToNext()) return null
                return decodeCapabilities(cursor.getString(0))
            }
        } catch (_: SQLiteException) {
            return null
        }
    }

    /** Replace the whole row for a key (SC: `INSERT OR REPLACE`). */
    @Synchronized
    fun writeRow(
        identifier: String,
        storeGroup: Int,
        tableNumber: Int,
        capabilityCounter: Int,
        capabilities: List<ByteArray>,
    ) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put(COL_IDENTIFIER, identifier)
            put(COL_STORE_GROUP, storeGroup)
            put(COL_TABLE_NUMBER, tableNumber)
            put(COL_CAPABILITY_COUNTER, capabilityCounter)
            put(COL_CAPABILITIES, encodeCapabilities(capabilities))
        }
        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun encodeCapabilities(payloads: List<ByteArray>): String =
        json.encodeToString(
            payloads.map {
                CapabilityBlob(
                    type = it.firstOrNull()?.toInt()?.and(0xFF) ?: 0,
                    encodedPayload = it,
                )
            },
        )

    private fun decodeCapabilities(encoded: String): List<ByteArray>? =
        runCatching {
            json.decodeFromString<List<CapabilityBlob>>(encoded).map { it.encodedPayload }
        }.getOrNull()

    @Serializable
    private data class CapabilityBlob(
        @SerialName("type")
        val type: Int,
        @Serializable(with = Base64ByteArraySerializer::class)
        @SerialName("encodedPayload")
        val encodedPayload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CapabilityBlob) return false
            return type == other.type && encodedPayload.contentEquals(other.encodedPayload)
        }

        override fun hashCode(): Int = type * 31 + encodedPayload.contentHashCode()
    }

    companion object {
        const val TABLE_NAME = "exchanged_capabilities"
        private const val DB_NAME = "tandem-capabilities.db"
        private const val DB_VERSION = 1
        private const val COL_IDENTIFIER = "identifier"
        private const val COL_STORE_GROUP = "store_group"
        private const val COL_TABLE_NUMBER = "command_table_number"
        private const val COL_CAPABILITY_COUNTER = "capability_counter"
        private const val COL_CAPABILITIES = "capabilities"
    }
}

/** Raw capability bytes travel as base64, the same encoding SC's row uses. */
private object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Base64ByteArray", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.encodeToString(value, Base64.NO_WRAP))
    }

    override fun deserialize(decoder: Decoder): ByteArray =
        Base64.decode(decoder.decodeString(), Base64.NO_WRAP)
}
