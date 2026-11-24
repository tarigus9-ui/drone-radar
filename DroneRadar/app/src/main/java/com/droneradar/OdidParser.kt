package com.droneradar

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decoder for ASTM F3411 / ASD-STAN EN 4709-002 "Open Drone ID" messages.
 * Every message is 25 bytes. Byte 0 = (type << 4) | protocolVersion.
 *
 * Transports:
 *  - Bluetooth 4/5 : Service Data, UUID 0xFFFA, payload = [0x0D app code][counter][25-byte msg]
 *  - Wi-Fi Beacon  : Vendor IE (id 221), OUI FA:0B:BC, type 0x0D, payload = [counter][message pack]
 *  - Wi-Fi NaN     : service "org.opendroneid.remoteid", serviceInfo = [counter][message pack]
 */
object OdidParser {

    const val TYPE_BASIC_ID = 0x0
    const val TYPE_LOCATION = 0x1
    const val TYPE_AUTH = 0x2
    const val TYPE_SELF_ID = 0x3
    const val TYPE_SYSTEM = 0x4
    const val TYPE_OPERATOR_ID = 0x5
    const val TYPE_PACK = 0xF
    const val MSG_SIZE = 25

    val WIFI_OUI = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte())
    const val WIFI_OUI_TYPE: Byte = 0x0D

    /** Apply one or more messages to a drone record. Returns true if anything was decoded. */
    fun decodeInto(d: DroneInfo, msg: ByteArray, offset: Int = 0): Boolean {
        if (msg.size - offset < MSG_SIZE) return false
        val type = (msg[offset].toInt() ushr 4) and 0x0F
        val b = ByteBuffer.wrap(msg, offset, MSG_SIZE).order(ByteOrder.LITTLE_ENDIAN).slice()
            .order(ByteOrder.LITTLE_ENDIAN)
        when (type) {
            TYPE_BASIC_ID -> {
                val idType = (u8(b, 1) ushr 4) and 0x0F
                d.uaType = u8(b, 1) and 0x0F
                val id = ascii(b, 2, 20)
                if (id.isNotBlank()) {
                    // idType 1 = serial number, 2 = CAA registration, 3 = UTM UUID, 4 = session
                    if (idType == 1 || d.serial == null) { d.serial = id; d.idType = idType }
                }
            }
            TYPE_LOCATION -> {
                val flags = u8(b, 1)
                d.status = (flags ushr 4) and 0x0F
                val ewBit = (flags ushr 1) and 1
                val speedMult = flags and 1
                val dir = u8(b, 2)
                d.heading = if (dir <= 180) (dir + if (ewBit == 1) 180 else 0).toFloat() else null
                val sp = u8(b, 3)
                d.speed = when {
                    sp == 255 -> null
                    speedMult == 0 -> sp * 0.25f
                    else -> sp * 0.75f + 63.75f
                }
                val lat = b.getInt(5) * 1e-7
                val lon = b.getInt(9) * 1e-7
                if (validLatLon(lat, lon)) { d.lat = lat; d.lon = lon }
                d.altGeo = alt(b, 15)
                d.height = alt(b, 17)
            }
            TYPE_SELF_ID -> {
                val t = ascii(b, 2, 23)
                if (t.isNotBlank()) d.description = t
            }
            TYPE_SYSTEM -> {
                val lat = b.getInt(2) * 1e-7
                val lon = b.getInt(6) * 1e-7
                if (validLatLon(lat, lon)) { d.opLat = lat; d.opLon = lon }
                d.opAlt = alt(b, 18)
            }
            TYPE_OPERATOR_ID -> {
                val t = ascii(b, 2, 20)
                if (t.isNotBlank()) d.operatorId = t
            }
            TYPE_PACK -> return decodePack(d, msg, offset)
            else -> return false
        }
        return true
    }

    /** Message pack: [0xF?][msgSize=25][count][count * 25 bytes] */
    fun decodePack(d: DroneInfo, data: ByteArray, offset: Int = 0): Boolean {
        if (data.size - offset < 3) return false
        val size = data[offset + 1].toInt() and 0xFF
        val count = data[offset + 2].toInt() and 0xFF
        if (size != MSG_SIZE || count == 0 || count > 9) return false
        var any = false
        for (i in 0 until count) {
            val o = offset + 3 + i * MSG_SIZE
            if (o + MSG_SIZE > data.size) break
            any = decodeInto(d, data, o) or any
        }
        return any
    }

    /** BLE service-data payload (bytes after UUID 0xFFFA). */
    fun decodeBle(d: DroneInfo, serviceData: ByteArray): Boolean {
        if (serviceData.size < 2 + MSG_SIZE || serviceData[0] != 0x0D.toByte()) return false
        return decodeInto(d, serviceData, 2)
    }

    /** Wi-Fi Beacon vendor IE body (starts with OUI). */
    fun decodeWifiVendorIe(d: DroneInfo, ie: ByteArray): Boolean {
        if (ie.size < 5 + 3) return false
        if (ie[0] != WIFI_OUI[0] || ie[1] != WIFI_OUI[1] || ie[2] != WIFI_OUI[2]) return false
        if (ie[3] != WIFI_OUI_TYPE) return false
        return decodePack(d, ie, 5) // skip OUI(3) + type(1) + counter(1)
    }

    /** Wi-Fi NaN serviceSpecificInfo: [counter][pack]. */
    fun decodeNan(d: DroneInfo, info: ByteArray): Boolean =
        info.size > 3 && decodePack(d, info, 1)

    private fun u8(b: ByteBuffer, i: Int) = b.get(i).toInt() and 0xFF
    private fun alt(b: ByteBuffer, i: Int): Float? {
        val raw = b.getShort(i).toInt() and 0xFFFF
        return if (raw == 0) null else raw * 0.5f - 1000f
    }
    private fun ascii(b: ByteBuffer, start: Int, len: Int): String {
        val sb = StringBuilder()
        for (i in start until start + len) {
            val c = u8(b, i)
            if (c == 0) break
            if (c in 32..126) sb.append(c.toChar())
        }
        return sb.toString().trim()
    }
    private fun validLatLon(lat: Double, lon: Double) =
        lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
}
