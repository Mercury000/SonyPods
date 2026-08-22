package dev.sonypods.data

import dev.sonypods.protocol.ParsedTandemResponse
import dev.sonypods.protocol.SonyTandemV2Table1Protocol
import org.junit.Assert.assertEquals
import org.junit.Test

class LeaStateTest {
    @Test
    fun disabledSettingIsNotOverwrittenByA2dpConnectionStatus() {
        val setting = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x49, 0x0C, 0x01),
        ) as ParsedTandemResponse.LeaParameterNotification
        val connection = SonyTandemV2Table1Protocol.parse(
            byteArrayOf(0x0E, 0x43, 0x00, 0x00, 0x02, 0x02),
        ) as ParsedTandemResponse.LeaStatus

        val state = LeaState()
            .withSettingNotification(setting)
            .withConnectionStatus(connection)

        assertEquals("DISABLE", state.enabled)
        assertEquals("ENABLE", state.connectionEnabled)
        assertEquals("VIA_A2DP", state.streamingStatusL)
        assertEquals("VIA_A2DP", state.streamingStatusR)
    }
}
