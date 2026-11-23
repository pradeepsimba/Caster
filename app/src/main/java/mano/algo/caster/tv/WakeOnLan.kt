package mano.algo.caster.tv

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.io.File

/**
 * "Wake on LAN": a TV in standby can be switched on by a small broadcast packet that carries its MAC address, if the TV allows
 * it (many have a setting such as "Turn on with mobile / Wi-Fi"). Only the home network is ever written to.
 */
object WakeOnLan {
    /** A MAC address typed any common way (aa:bb:cc:dd:ee:ff, aa-bb-…, aabbccddeeff), as lower-case with colons; null if it isn't one. */
    fun normalize(input: String): String? {
        val text = input.trim()
        val digits = text.replace(":", "").replace("-", "")
        if (digits.length != 12 || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        // The separators, when there are any, are all the same one and in the right places.
        if (text.length != 12) {
            if (text.length != 17) return null
            val separator = text[2]
            if (separator != ':' && separator != '-') return null
            if ((2..14 step 3).any { text[it] != separator }) return null
        }
        if (digits.all { it == '0' } || digits.all { it == 'f' || it == 'F' }) return null // not a real device
        return digits.lowercase().chunked(2).joinToString(":")
    }

    /** The magic packet: six 0xFF bytes, then the MAC address sixteen times. */
    fun packet(mac: String): ByteArray {
        val normal = normalize(mac) ?: throw IllegalArgumentException("Not a MAC address")
        val bytes = normal.split(':').map { it.toInt(16).toByte() }.toByteArray()
        val out = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) out[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(bytes, 0, out, 6 + i * 6, 6)
        return out
    }

    /** The address that reaches everyone on a /24 home network that [host] is on (192.168.0.50 -> 192.168.0.255). */
    fun subnetBroadcast(host: String): String? {
        val parts = host.split('.')
        if (parts.size != 4 || !TvCommands.isLocalIpv4(host)) return null
        return parts.take(3).joinToString(".") + ".255"
    }

    /** Sends the packet to the whole home network (and to the TV's own subnet), on the usual ports 9 and 7. Blocks briefly. */
    fun send(mac: String, host: String) {
        val data = packet(mac)
        val targets = listOfNotNull("255.255.255.255", subnetBroadcast(host))
        DatagramSocket().use { socket ->
            socket.broadcast = true
            LanNetwork.bind(socket)
            for (target in targets) {
                val address = InetAddress.getByName(target) // a number: nothing is looked up
                for (port in intArrayOf(9, 7)) {
                    runCatching { socket.send(DatagramPacket(data, data.size, address, port)) }
                }
            }
        }
    }

    /**
     * The MAC address of [host] from the phone's neighbour table, when Android lets an app read it (Android 9 and older do; newer
     * versions hide it, and the address is then typed once instead).
     */
    fun fromNeighbours(host: String): String? = runCatching {
        File("/proc/net/arp").readLines().drop(1).firstNotNullOfOrNull { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size >= 4 && cols[0] == host && cols[2] != "0x0") normalize(cols[3]) else null
        }
    }.getOrNull()
}
