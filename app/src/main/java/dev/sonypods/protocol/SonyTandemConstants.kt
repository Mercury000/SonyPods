package dev.sonypods.protocol

object SonyTandemConstants {
    const val DATA_MDR: Byte = 0x0E
    const val DATA_MDR_NO2: Byte = 0x0F

    /**
     * Known V1 protocol versions, byte-for-byte from Sound Connect
     * 13.2.1 `p573uv.C29903d.f85968b` (the V1 Table-1 initializer). Every
     * device is asked for its 2-byte BE protocol version at connection time
     * (CONNECT_GET_PROTOCOL_INFO). This list is used for diagnostics only;
     * unknown versions still proceed with support-function capability probing.
     */
    val PROTOCOL_VERSIONS: IntArray = intArrayOf(
        0x1000, 0x2000, 0x3000, 0x4000, 0x4010, 0x5000, 0x6000, 0x7000, 0x7010,
    )

    /**
     * Known V2 Table-1 protocol versions, byte-for-byte from Sound Connect
     * 13.3.0 `p602vv.C31621e.f89900b` (the V2 Table-1 initializer). This adds
     * 0x03003033 (WF-1000XM6 firmware 2.0.5, issue #34) and 0x03003037 to
     * the 13.2.1 list. V2 reports the version as a 4-byte BE value
     * (SC 13.2.1 `ff0.C16477k.m69436c()` reads
     * body[2..5]). As with V1, list membership is diagnostic and never gates
     * capability probing; the parsers skip unknown support-function codes.
     */
    val PROTOCOL_VERSIONS_V2: IntArray = intArrayOf(
        0x01000000,
        0x02000000, 0x02000001, 0x02001000,
        0x03000001, 0x03000000, 0x03000002, 0x03000004, 0x03000005, 0x03000003,
        0x03000006, 0x03000007, 0x03000008, 0x03000009,
        0x03000010, 0x03000011, 0x03000012, 0x03000013, 0x03000014, 0x03000015,
        0x03000016, 0x03000017, 0x03000018, 0x03000019, 0x03000020,
        0x03001001, 0x03001002, 0x03001003, 0x03001004,
        0x03002000, 0x03002001, 0x03002002, 0x03002003, 0x03002004, 0x03002005,
        0x03002006, 0x03002007, 0x03002008, 0x03002009,
        0x03002012, 0x03002013, 0x03002014, 0x03002015, 0x03002016, 0x03002017,
        0x03002018, 0x03002019,
        0x03003000, 0x03003001, 0x03003002, 0x03003003, 0x03003004, 0x03003005,
        0x03003006, 0x03003007, 0x03003008, 0x03003009,
        0x03003010, 0x03003011, 0x03003012, 0x03003013, 0x03003014, 0x03003015,
        0x03003016, 0x03003017, 0x03003018, 0x03003019,
        0x03003020, 0x03003021, 0x03003023, 0x03003024, 0x03003025, 0x03003026,
        0x03003032, 0x03003033, 0x03003037,
    )
}
