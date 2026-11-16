package mano.algo.caster.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
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

    /**
     * True when the address belongs to the phone's own hotspot. Android 16 registers it as a Wi-Fi network
     * flagged LOCAL_NETWORK; older versions register nothing, so only the interface fallback finds it.
     * A hotspot's chip drops name-lookup (mDNS) packets while the screen is off, so the IP address is the
     * one to rely on there.
     */
    fun isHotspot(context: Context): Boolean {
        val found = lanNetworkAddress(context) ?: return fromNetworkInterfaces() != null
        return found.second
    }

    private fun fromConnectivityManager(context: Context): Inet4Address? = lanNetworkAddress(context)?.first

    /** The first LAN IPv4 address and whether it sits on a hotspot (local-only) network. */
    private fun lanNetworkAddress(context: Context): Pair<Inet4Address, Boolean>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            // A VPN that runs over Wi-Fi reports Wi-Fi too, but its address isn't one a TV on the Wi-Fi can reach.
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) continue
            val address = cm.getLinkProperties(network)?.linkAddresses.orEmpty()
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress } ?: continue
            val local = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
            return address to local
        }
        return null
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
