package mano.algo.caster.tv

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

    /** The key held down long enough to count as a long press (Home, held, opens the quick menu). */
    fun longPress(code: Int): String = "input keyevent --longpress $code"

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
        var left = removed
        while (left > 0) { // one command takes at most 200 presses, so a long text is deleted in several
            val now = minOf(left, 200)
            commands += keys(TvKey.DELETE, now)
            left -= now
        }
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
        val match = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?::(\d{1,5}))?""").matchEntire(input.trim()) ?: return null
        val octets = (1..4).map { match.groupValues[it].toInt() }
        if (octets.any { it > 255 }) return null
        // The plain a.b.c.d form (so 192.168.01.050 and 192.168.1.50 are the same TV, and are saved under one name).
        val host = octets.joinToString(".")
        val port = match.groupValues[5].ifEmpty { DEFAULT_PORT.toString() }.toInt()
        if (port !in 1..65535 || !isLocalIpv4(host)) return null
        return Target(host, port)
    }

    /**
     * True for a plain IPv4 address on a home network (192.168.x.x, 10.x.x.x, 172.16-31.x.x, or link-local). Caster only
     * ever talks to such addresses: it never sends commands to something on the internet.
     */
    fun isLocalIpv4(host: String): Boolean {
        val match = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""").matchEntire(host) ?: return false
        val o = (1..4).map { match.groupValues[it].toInt() }
        if (o.any { it > 255 }) return false
        // Worked out from the numbers, never by asking the system to resolve it (that can look the text up as a name).
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254)
    }
}
