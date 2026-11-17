package com.example.flipperdroid.util

/**
 * Safe construction of command lines passed to `su -c` (a single shell argument).
 *
 * Any data coming from the user or the network must either be validated by one
 * of the validators below, or wrapped by [quote]. Without that, input such as
 * `1.1.1.1; reboot` ran as root. No Android imports: JVM-testable.
 */
object ShellArgs {

    /** Wraps in POSIX single quotes; internal quotes become '\''. */
    fun quote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

    fun join(args: List<String>): String = args.joinToString(" ") { quote(it) }

    private val HOSTNAME = Regex("^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.?$")
    private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$")
    private val IPV4_CIDR = Regex("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)/(3[0-2]|[12]?\\d)$")
    private val IPV6 = Regex("^[0-9A-Fa-f:.]{2,45}$")
    private val IFACE = Regex("^[A-Za-z0-9._-]{1,15}$")
    private val MAC = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    fun isValidIpv4(s: String) = IPV4.matches(s)

    /** Accepted network target: IPv4, IPv4/CIDR, literal IPv6 or hostname. */
    fun isValidTarget(s: String): Boolean =
        IPV4.matches(s) || IPV4_CIDR.matches(s) || (s.contains(':') && IPV6.matches(s)) ||
            // A hostname does not end with a purely numeric label (otherwise "256.1.1.1" would pass).
            (HOSTNAME.matches(s) && !s.trimEnd('.').substringAfterLast('.').all { it.isDigit() })

    fun isValidInterface(s: String) = IFACE.matches(s)

    fun isValidMac(s: String) = MAC.matches(s)

    /**
     * Splits nmap-style options into arguments, rejecting shell metacharacters
     * (even quoted, they have no business in options) and options that
     * write/read arbitrary files or execute scripts.
     * @return the argument list, or null if rejected.
     */
    fun splitSafeOptions(options: String): List<String>? {
        val trimmed = options.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.any { it in ";&|`$<>\\\"'\n\r(){}" }) return null
        val parts = trimmed.split(Regex("\\s+"))
        // -o* (output files), -i* (input lists), NSE scripts and data paths.
        val forbiddenPrefixes = listOf("-o", "-i", "--script", "--datadir", "--resume", "--stylesheet", "--servicedb", "--versiondb")
        if (parts.any { p -> forbiddenPrefixes.any { p.startsWith(it) } }) return null
        return parts
    }
}
