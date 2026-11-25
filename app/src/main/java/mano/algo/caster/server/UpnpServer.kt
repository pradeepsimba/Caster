package mano.algo.caster.server

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress

/**
 * Lightweight SSDP responder for DLNA / UPnP MediaServer discovery.
 * Allows Smart TVs and media centers on the Wi-Fi network to discover Caster
 * without needing a web browser.
 */
class UpnpServer(
    private val address: Inet4Address,
    private val port: Int,
    private val friendlyName: String,
    private val serverUuid: String,
) {
    @Volatile private var socket: MulticastSocket? = null

    fun start() {
        check(socket == null) { "UPnP server already started" }
        val nif = NetworkInterface.getByInetAddress(address) ?: throw IOException("No interface for $address")
        val group = InetAddress.getByName(MULTICAST_GROUP)
        val s = MulticastSocket(null as SocketAddress?)
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(SSDP_PORT))
            s.networkInterface = nif
            s.timeToLive = 4
            s.joinGroup(InetSocketAddress(group, SSDP_PORT), nif)
        } catch (e: IOException) {
            s.close()
            throw e
        }
        socket = s
        Thread({ listen(s, group) }, "upnp-ssdp").apply { isDaemon = true }.start()
        sendAlive(s, group)
    }

    fun stop() {
        val s = socket ?: return
        socket = null
        Thread({
            try {
                val group = InetAddress.getByName(MULTICAST_GROUP)
                sendByebye(s, group)
            } catch (_: Exception) {}
            s.close()
        }, "upnp-stop").apply { isDaemon = true }.start()
    }

    private fun listen(s: MulticastSocket, group: InetAddress) {
        val buffer = ByteArray(2048)
        while (!s.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (e: IOException) {
                return // socket closed
            }
            try {
                handlePacket(s, packet)
            } catch (e: Exception) {
                Log.d(TAG, "Error handling SSDP packet", e)
            }
        }
    }

    private fun handlePacket(s: MulticastSocket, packet: DatagramPacket) {
        val message = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
        if (!message.startsWith("M-SEARCH", ignoreCase = true)) return
        val lines = message.split("\r\n")
        var isDiscover = false
        var target: String? = null
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val header = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (header.equals("MAN", ignoreCase = true) && value.contains("ssdp:discover", ignoreCase = true)) {
                isDiscover = true
            } else if (header.equals("ST", ignoreCase = true)) {
                target = value
            }
        }
        if (!isDiscover || target == null) return

        val matchingTargets = TARGETS.filter { target.equals("ssdp:all", ignoreCase = true) || target.equals(it, ignoreCase = true) }
        for (st in matchingTargets) {
            val responseText = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("CACHE-CONTROL: max-age=1800\r\n")
                append("EXT:\r\n")
                append("LOCATION: http://${address.hostAddress}:$port/upnp/desc.xml\r\n")
                append("SERVER: Android/1.0 UPnP/1.0 DLNADOC/1.50 Caster/1.0\r\n")
                append("ST: $st\r\n")
                append("USN: uuid:$serverUuid::$st\r\n")
                append("\r\n")
            }
            val bytes = responseText.toByteArray(Charsets.US_ASCII)
            val replyPacket = DatagramPacket(bytes, bytes.size, packet.socketAddress)
            s.send(replyPacket)
        }
    }

    private fun sendAlive(s: MulticastSocket, group: InetAddress) {
        for (nt in TARGETS) {
            val msg = buildString {
                append("NOTIFY * HTTP/1.1\r\n")
                append("HOST: $MULTICAST_GROUP:$SSDP_PORT\r\n")
                append("CACHE-CONTROL: max-age=1800\r\n")
                append("LOCATION: http://${address.hostAddress}:$port/upnp/desc.xml\r\n")
                append("NT: $nt\r\n")
                append("NTS: ssdp:alive\r\n")
                append("SERVER: Android/1.0 UPnP/1.0 DLNADOC/1.50 Caster/1.0\r\n")
                append("USN: uuid:$serverUuid::$nt\r\n")
                append("\r\n")
            }
            val bytes = msg.toByteArray(Charsets.US_ASCII)
            s.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))
        }
    }

    private fun sendByebye(s: MulticastSocket, group: InetAddress) {
        for (nt in TARGETS) {
            val msg = buildString {
                append("NOTIFY * HTTP/1.1\r\n")
                append("HOST: $MULTICAST_GROUP:$SSDP_PORT\r\n")
                append("NT: $nt\r\n")
                append("NTS: ssdp:byebye\r\n")
                append("USN: uuid:$serverUuid::$nt\r\n")
                append("\r\n")
            }
            val bytes = msg.toByteArray(Charsets.US_ASCII)
            s.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))
        }
    }

    companion object {
        private const val TAG = "UpnpServer"
        private const val MULTICAST_GROUP = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private val TARGETS = listOf(
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaServer:1",
            "urn:schemas-upnp-org:service:ContentDirectory:1",
            "urn:schemas-upnp-org:service:ConnectionManager:1",
        )
    }
}
