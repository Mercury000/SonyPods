package dev.sonypods.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiuiHeadsetSupportTest {
    @Test
    fun encodeUsesRealAddressAndAncCapability() {
        val address = "AA:BB:CC:DD:EE:FF"

        assertEquals(
            "$address,000000000000000010000000",
            MiuiHeadsetSupport.encode(address),
        )
    }

    @Test
    fun addressOfAcceptsOnlyModuleCapabilityPayload() {
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            MiuiHeadsetSupport.addressOf("AA:BB:CC:DD:EE:FF,000000000000000010000000"),
        )
        assertNull(MiuiHeadsetSupport.addressOf("AA:BB:CC:DD:EE:FF,000000000000000000000000"))
        assertNull(MiuiHeadsetSupport.addressOf("AA:BB:CC:DD:EE:FF"))
    }
}
