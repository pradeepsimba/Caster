package mano.algo.caster.server

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.SocketAddress

/**
 * Minimal mDNS responder (RFC 6762) that answers A-record queries for `<hostname>.local` with this
 * phone's address, so browsers on the same network can reach `http://caster.local:8080` without knowing
 * the IP or scanning. Pure java.net; Android additionally needs a held WifiManager.MulticastLock.
 */
class MdnsResponder(
    private val address: Inet4Address,
    hostname: String = DEFAULT_HOSTNAME,
) {
    private val labels = hostname.lowercase().split('.') + "local"
    private val localName = labels.joinToString(".")

    @Volatile private var socket: MulticastSocket? = null

    @Throws(IOException::class)
    fun start() {
        check(socket == null) { "already started" }
        val nif = NetworkInterface.getByInetAddress(address) ?: throw IOException("No interface for $address")
        val group = InetAddress.getByName(GROUP)
        val s = MulticastSocket(null as SocketAddress?)
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(PORT))
            s.networkInterface = nif
            s.timeToLive = 255
            s.joinGroup(InetSocketAddress(group, PORT), nif)
        } catch (e: IOException) {
            s.close()
            throw e
        }
        socket = s
        Thread({ listen(s, group) }, "mdns-responder").apply { isDaemon = true }.start()
    }

    fun stop() {
        socket?.close()
        socket = null
    }

    private fun listen(s: MulticastSocket, group: InetAddress) {
        val buffer = ByteArray(1500)
        while (!s.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (e: IOException) {
                return // closed by stop()
            }
            if (!asksForUs(packet.data, packet.length)) continue
            try {
                val multicast = answer(id = 0)
                s.send(DatagramPacket(multicast, multicast.size, group, PORT))
                if (packet.port != PORT) { // legacy resolver: reply directly and echo its query id
                    val id = u16(packet.data, 0)
                    val unicast = answer(id)
                    s.send(DatagramPacket(unicast, unicast.size, packet.socketAddress))
                }
            } catch (e: IOException) {
                if (s.isClosed) return
            }
        }
    }

    /** True if this is a query (not a response) with a question for our A record. */
    private fun asksForUs(data: ByteArray, length: Int): Boolean = try {
        if (length < 12 || (u16(data, 2) and 0x8000) != 0) {
            false
        } else {
            var pos = 12
            var match = false
            repeat(u16(data, 4)) {
                val name = StringBuilder()
                while (true) {
                    val len = data[pos++].toInt() and 0xFF
                    if (len == 0) break
                    if (len and 0xC0 != 0) { pos++; name.append('?'); break } // compression pointer: not ours
                    if (name.isNotEmpty()) name.append('.')
                    name.append(String(data, pos, len, Charsets.UTF_8))
                    pos += len
                }
                val type = u16(data, pos)
                val cls = u16(data, pos + 2) and 0x7FFF // top bit = unicast-response request
                pos += 4
                if (name.toString().equals(localName, ignoreCase = true) && (type == TYPE_A || type == TYPE_ANY) && cls == CLASS_IN) {
                    match = true
                }
            }
            match
        }
    } catch (e: IndexOutOfBoundsException) {
        false // truncated or malformed packet
    }

    private fun answer(id: Int): ByteArray = ByteArrayOutputStream().apply {
        writeU16(id)
        writeU16(0x8400)          // response, authoritative
        writeU16(0); writeU16(1); writeU16(0); writeU16(0)
        labels.forEach { label -> write(label.length); write(label.toByteArray(Charsets.UTF_8)) }
        write(0)
        writeU16(TYPE_A)
        writeU16(0x8000 or CLASS_IN) // cache-flush + IN
        writeU16(0); writeU16(TTL_SECONDS)
        writeU16(4)
        write(address.address)
    }.toByteArray()

    private fun ByteArrayOutputStream.writeU16(v: Int) { write(v ushr 8 and 0xFF); write(v and 0xFF) }

    private fun u16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF shl 8) or (b[off + 1].toInt() and 0xFF)

    companion object {
        const val DEFAULT_HOSTNAME = "caster"

        /**
         * True if another device on this network already answers for `<hostname>.local`: ask once and listen
         * briefly (the probing idea from RFC 6762). Any failure counts as "free" so sharing never gets blocked.
         */
        fun isNameTaken(address: Inet4Address, hostname: String, waitMs: Long = 400): Boolean {
            val labels = hostname.lowercase().split('.') + "local"
            val nif = NetworkInterface.getByInetAddress(address) ?: return false
            val group = InetAddress.getByName(GROUP)
            val s = MulticastSocket(null as SocketAddress?)
            return try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(PORT))
                s.networkInterface = nif
                s.timeToLive = 255
                s.joinGroup(InetSocketAddress(group, PORT), nif)
                val query = probeQuery(labels)
                s.send(DatagramPacket(query, query.size, group, PORT))

                val name = labels.joinToString(".")
                val deadline = System.nanoTime() + waitMs * 1_000_000
                val buffer = ByteArray(1500)
                var taken = false
                while (!taken) {
                    val leftMs = (deadline - System.nanoTime()) / 1_000_000
                    if (leftMs <= 0) break
                    s.soTimeout = leftMs.toInt()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        s.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                    taken = answersWithOtherAddress(packet.data, packet.length, name, address)
                }
                taken
            } catch (e: IOException) {
                false
            } finally {
                s.close()
            }
        }

        private fun probeQuery(labels: List<String>): ByteArray = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))   // id 0, plain query, one question
            labels.forEach { label -> write(label.length); write(label.toByteArray(Charsets.UTF_8)) }
            write(0)
            write(byteArrayOf(0, TYPE_A.toByte(), 0, CLASS_IN.toByte()))
        }.toByteArray()

        private fun be16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF shl 8) or (b[off + 1].toInt() and 0xFF)

        /** Reads a possibly-compressed DNS name; returns it with the offset just past it in the original record. */
        private fun readName(data: ByteArray, start: Int): Pair<String, Int> {
            val parts = ArrayList<String>()
            var pos = start
            var end = -1
            var jumps = 0
            while (true) {
                val len = data[pos].toInt() and 0xFF
                when {
                    len == 0 -> { if (end < 0) end = pos + 1; break }
                    len and 0xC0 == 0xC0 -> {   // pointer to an earlier name
                        if (end < 0) end = pos + 2
                        if (++jumps > 10) throw IndexOutOfBoundsException("name loop")
                        pos = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    }
                    else -> { parts += String(data, pos + 1, len, Charsets.UTF_8); pos += 1 + len }
                }
            }
            return parts.joinToString(".") to end
        }

        /** True if this packet is a response giving [name] an address other than [ours]. */
        private fun answersWithOtherAddress(data: ByteArray, length: Int, name: String, ours: Inet4Address): Boolean = try {
            if (length < 12 || (be16(data, 2) and 0x8000) == 0) {
                false
            } else {
                var pos = 12
                repeat(be16(data, 4)) { pos = readName(data, pos).second + 4 }          // skip the questions
                var found = false
                repeat(be16(data, 6) + be16(data, 8) + be16(data, 10)) {
                    val (recordName, after) = readName(data, pos)
                    val type = be16(data, after)
                    val rdLength = be16(data, after + 8)
                    val rdata = after + 10
                    if (type == TYPE_A && rdLength == 4 && recordName.equals(name, ignoreCase = true) &&
                        !data.copyOfRange(rdata, rdata + 4).contentEquals(ours.address)
                    ) found = true
                    pos = rdata + rdLength
                }
                found
            }
        } catch (e: IndexOutOfBoundsException) {
            false
        }
        private const val GROUP = "224.0.0.251"
        private const val PORT = 5353
        private const val TYPE_A = 1
        private const val TYPE_ANY = 255
        private const val CLASS_IN = 1
        private const val TTL_SECONDS = 120
    }
}
