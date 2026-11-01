package mano.algo.caster.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    private val LAN_INTERFACE_PREFIXES = listOf("wlan", "swlan", "ap", "eth", "p2p")

    /**
     * Resolves the phone's IPv4 address on the local network (Wi-Fi, Ethernet, or its own hotspot).
     * Cellular addresses are deliberately ignored: a TV on the home network can never reach them.
     *
     * Blocking but fast (no network I/O); call from a background dispatcher anyway.
     */
    fun resolveLocalIpv4(context: Context): Inet4Address? =
        fromConnectivityManager(context) ?: fromNetworkInterfaces()

    private fun fromConnectivityManager(context: Context): Inet4Address? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        return networks.asSequence()
            .filter { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@filter false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            }
            .mapNotNull { cm.getLinkProperties(it) }
            .flatMap { it.linkAddresses.asSequence() }
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
    }

    /** Fallback that also covers the phone acting as a Wi-Fi hotspot (no "network" is registered then). */
    private fun fromNetworkInterfaces(): Inet4Address? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filter { nif -> LAN_INTERFACE_PREFIXES.any { nif.name.startsWith(it, ignoreCase = true) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
    }.getOrNull()
}
