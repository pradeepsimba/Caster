package mano.algo.caster.tv

import java.net.InetAddress

/** Android key codes the remote sends. */
object TvKey {
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val OK = 23
    const val BACK = 4
    const val HOME = 3
    const val MENU = 82
    const val PLAY_PAUSE = 85
    const val REWIND = 89
    const val FAST_FORWARD = 90
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val MUTE = 164
    const val DELETE = 67 // backspace
    const val ENTER = 66
}

/**
 * Builds the shell commands that are sent to the TV. Everything that ends up inside a command goes through here,
 * so what the owner types (or the address Caster shares) can never turn into extra commands.
 */
object TvCommands {
    fun key(code: Int): String = "input keyevent $code"

    /** The key pressed [count] times in one command (a short burst, for deleting several letters at once). */
    fun keys(code: Int, count: Int): String = "input keyevent " + List(count.coerceIn(1, 200)) { code }.joinToString(" ")

    /**
     * The commands that turn the text field on the phone from [old] into [new] on the TV: delete what changed at the
     * end, then type the new ending. Typing one letter sends one letter; a backspace sends one delete.
     */
    fun edit(old: String, new: String): List<String> {
        var same = 0
        while (same < old.length && same < new.length && old[same] == new[same]) same++
        val commands = ArrayList<String>(2)
        // Only letters that were really typed on the TV are deleted there: the others never arrived.
        val removed = old.substring(same).count(::typeable)
        if (removed > 0) commands += keys(TvKey.DELETE, removed)
        val added = new.substring(same)
        if (added.any(::typeable)) commands += text(added)
        return commands
    }

    /**
     * Types [text] into whatever field has focus on the TV. `input text` only handles plain ASCII and writes a space as
     * `%s`, so anything else is dropped. The result is wrapped in single quotes, the one quoting that no character can break out of.
     */
    fun text(text: String): String {
        val safe = text.filter(::typeable).replace(" ", "%s")
        return "input text " + quote(safe)
    }

    /** Opens [url] in the TV's browser (Silk on a Fire TV). */
    fun openUrl(url: String): String = "am start -a android.intent.action.VIEW -d " + quote(url)

    /** What `input text` can type: plain ASCII, without `%` (it uses that for spaces). */
    private fun typeable(c: Char) = c.code in 32..126 && c != '%'

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    /** Where to connect: the address and the ADB port. */
    data class Target(val host: String, val port: Int)

    const val DEFAULT_PORT = 5555

    /**
     * Reads what the owner typed: an address like `192.168.0.50`, or `192.168.0.50:5555` for a TV that uses another port.
     * Returns null unless it is a home-network address, so Caster never sends commands to something on the internet.
     */
    fun parseTarget(input: String): Target? {
        val match = Regex("""(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?""").matchEntire(input.trim()) ?: return null
        val port = match.groupValues[2].ifEmpty { DEFAULT_PORT.toString() }.toInt()
        if (port !in 1..65535 || !isLocalIpv4(match.groupValues[1])) return null
        return Target(match.groupValues[1], port)
    }

    /**
     * True for a plain IPv4 address on a home network (192.168.x.x, 10.x.x.x, 172.16-31.x.x, or link-local). Caster only
     * ever talks to such addresses: it never sends commands to something on the internet.
     */
    fun isLocalIpv4(host: String): Boolean {
        if (!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(host)) return false
        if (host.split('.').any { it.toInt() > 255 }) return false
        val address = InetAddress.getByName(host) // a numeric address: no lookup happens
        return address.isSiteLocalAddress || address.isLinkLocalAddress
    }
}
