package mano.algo.caster.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import mano.algo.caster.server.NetworkUtils
import java.io.IOException
import java.net.Socket
import java.net.URL
import java.net.URLConnection

/**
 * The home network (Wi-Fi or Ethernet) that TVs are on. When the Wi-Fi has no internet (a router that is offline, a hotspot, a
 * guest network) and mobile data is on, Android makes mobile data the default and a plain connection to 192.168.x.x goes out
 * that way and fails. Connections to a TV are therefore tied to this network.
 */
internal object LanNetwork {
    @Volatile private var network: Network? = null
    @Volatile private var appContext: Context? = null

    /** Looks the home network up again (it can change between one Connect and the next). */
    fun refresh(context: Context) {
        appContext = context.applicationContext
        network = runCatching { NetworkUtils.lanNetwork(context.applicationContext) }.getOrNull()
    }

    /** The home network, looked up again when the saved one is gone (Wi-Fi was switched off and on: a new network object). */
    private fun current(): Network? {
        val saved = network ?: return null
        val context = appContext ?: return saved
        val alive = runCatching { context.getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(saved) != null }.getOrDefault(true)
        if (alive) return saved
        refresh(context)
        return network
    }

    /** Ties a socket that is not connected yet to the home network. Does nothing when there is none (the default route is used). */
    fun bind(socket: Socket) {
        try {
            current()?.bindSocket(socket)
        } catch (e: IOException) {
            // The network went away meanwhile: the connect that follows fails or uses the default route, as it would have.
        }
    }

    fun open(url: URL): URLConnection {
        val home = current() ?: return url.openConnection()
        return try {
            home.openConnection(url)
        } catch (e: IOException) {
            url.openConnection()
        }
    }
}
