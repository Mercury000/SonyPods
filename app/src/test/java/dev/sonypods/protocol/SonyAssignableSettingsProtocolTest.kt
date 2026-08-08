package dev.sonypods.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SonyAssignableSettingsProtocolTest {
    @Test
    fun buildsOfficialAssignableSettingsFrames() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0xF0.toByte(), 0x03),
            SonyTandemV2Table1Protocol.buildGetAssignableSettingsCapability(),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0xF8.toByte(), 0x03, 0x01, 0x20),
            SonyTandemV2Table1Protocol.buildSetAssignableSettingsPresets(
                listOf(AssignableSettingsPreset.PLAYBACK_CONTROL),
            ),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0xFC.toByte(), 0x03, 0x01, 0x20, 0x01, 0x00, 0x20),
            SonyTandemV2Table1Protocol.buildSetAssignableSettingsExtendedParam(
                listOf(
                    AssignableSettingsMapping(
                        AssignableSettingsPreset.PLAYBACK_CONTROL,
                        listOf(
                            AssignableSettingsActionFunction(
                                AssignableSettingsAction.SINGLE_TAP,
                                AssignableSettingsFunction.PLAY_PAUSE,
                            )
                        ),
                    )
                ),
            ),
        )
    }

    @Test
    fun parsesCapabilityAndCurrentMapping() {
        val capability = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(
                0x0E, 0xF1.toByte(),
                0x03, 0x01,
                0x00, 0x00, 0x20, 0x01,
                0x20, 0x01, 0x00, 0x00, 0x20,
            )
        ) as ParsedTandemResponse.AssignableSettingsCapability
        assertEquals(1, capability.keys.size)
        assertEquals(AssignableSettingsKey.LEFT_SIDE, capability.keys.single().key)
        assertEquals(AssignableSettingsPreset.PLAYBACK_CONTROL, capability.keys.single().defaultPreset)
        assertEquals(AssignableSettingsAction.SINGLE_TAP, capability.keys.single().presets.single()
            .let { capability.keys.single().actionsByPreset[it].orEmpty().single().action })

        val mapping = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0xFB.toByte(), 0x03, 0x01, 0x20, 0x01, 0x00, 0x20)
        ) as ParsedTandemResponse.AssignableSettingsExtendedParam
        assertEquals(AssignableSettingsFunction.PLAY_PAUSE, mapping.mappings.single().mappings.single().function)
    }

    @Test
    fun supportsAssignableSettingsWithLeAudioLimitation() {
        assertArrayEquals(
            byteArrayOf(0x0E, 0xF0.toByte(), 0x0E),
            SonyTandemV2Table1Protocol.buildGetAssignableSettingsCapability(
                SystemInquiredType.ASSIGNABLE_SETTINGS_WITH_LIMITATION,
            ),
        )
        assertArrayEquals(
            byteArrayOf(0x0E, 0xF8.toByte(), 0x0E, 0x01, 0x20),
            SonyTandemV2Table1Protocol.buildSetAssignableSettingsPresets(
                SystemInquiredType.ASSIGNABLE_SETTINGS_WITH_LIMITATION,
                listOf(AssignableSettingsPreset.PLAYBACK_CONTROL),
            ),
        )

        val capability = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(
                0x0E, 0xF1.toByte(),
                0x0E, 0x01, 0x01,
                0x00, 0x00, 0x20, 0x01,
                0x20, 0x01, 0x00, 0x00, 0x20,
            )
        ) as ParsedTandemResponse.AssignableSettingsCapability
        assertEquals(AssignableSettingsKey.LEFT_SIDE, capability.keys.single().key)
        assertEquals(AssignableSettingsPreset.PLAYBACK_CONTROL, capability.keys.single().defaultPreset)
    }
}
