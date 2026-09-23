package com.darkvvpn.app.data.subscription

/**
 * Rejects endpoints that cannot possibly be dialled.
 *
 * ── Why this exists ──────────────────────────────────────────────────────────
 * Panels inject placeholder entries into their own subscription lists as a way of
 * talking to the user. A real list examined while building this contained:
 *
 *     vless://…@1.2.3.4.5:1234#⚡هر روز ساب خود را آپدیت کنید⚡
 *
 * The remark is an announcement ("update your subscription every day") and
 * `1.2.3.4.5` is not a host at all — it is a five-octet non-address that will
 * never resolve. Imported faithfully, it becomes a row in the server list that
 * shows "—" for latency, sorts last, and fails if the user picks it.
 *
 * Dropping such entries at parse time is better than showing them: a list of
 * working nodes with the junk removed is more useful than one that faithfully
 * reproduces the provider's advertising.
 */
internal object HostValidator {

    /**
     * True when [host] could plausibly be dialled.
     *
     * Accepts a hostname, an IPv4 literal, or a bracketed/unbracketed IPv6
     * literal. Rejects a dotted-quad-shaped string that is not a real IPv4
     * address, which is what the placeholder above is.
     */
    fun isDialable(host: String?): Boolean {
        val h = host?.trim()?.trim('[', ']').orEmpty()
        if (h.isEmpty()) return false
        // A hostname of one character is not a name; it is a placeholder.
        if (h.length < 3) return false
        if (h.length > 253) return false
        if (h.contains(' ') || h.contains('/') || h.contains('@')) return false
        if (h.startsWith('.') || h.endsWith('.')) return false

        // Looks like a dotted number: it must actually be one.
        if (looksNumeric(h)) return isIpv4(h) && !isUnusableIpv4(h)

        // An IPv6 literal contains colons, which no hostname may.
        if (h.contains(':')) return isIpv6(h) && !isUnusableIpv6(h)

        // Otherwise it is a hostname: demand at least one dot and a valid TLD-ish
        // tail, so `localhost`-style placeholders and bare words are rejected
        // while real names pass.
        if (!h.contains('.')) return false
        val labels = h.split('.')
        if (labels.any { it.isEmpty() }) return false
        if (labels.any { it.length > 63 }) return false
        if (labels.none { it.length >= 2 }) return false

        val tld = labels.last()
        // A TLD is letters (or an IDN punycode label). It is never all digits, so
        // `1.2.3.4.5` and friends are caught here as well.
        return tld.all { it.isLetter() || it == '-' || it.isDigit() } &&
            tld.any { it.isLetter() } &&
            labels.all { label ->
                label.all { it.isLetterOrDigit() || it == '-' || it == '_' } &&
                    !label.startsWith('-') && !label.endsWith('-')
            }
    }

    /**
     * Rejects addresses that cannot be a remote node.
     *
     * Only the loopback and unspecified ranges are excluded. Private ranges
     * (`10.` `192.168.` `172.16–31.`) are deliberately allowed, because a
     * self-hosted panel on the user's own LAN is a legitimate node — while
     * `127.0.0.1` and `0.0.0.0` never are, and would otherwise consume a row in
     * the list pointing at the phone itself.
     */
    private fun isUnusableIpv4(host: String): Boolean {
        val first = host.substringBefore('.').toIntOrNull() ?: return true
        return first == 0 || first == 127
    }

    private fun isUnusableIpv6(host: String): Boolean {
        val normalised = host.trim('[', ']').lowercase()
        return normalised == "::" || normalised == "::1" ||
            normalised.startsWith("fe80:") // link-local
    }

    private fun looksNumeric(host: String): Boolean =
        host.isNotEmpty() && host.all { it.isDigit() || it == '.' }

    private fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            if (part.isEmpty() || part.length > 3) return@all false
            if (part.length > 1 && part[0] == '0') return@all false
            part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun isIpv6(host: String): Boolean {
        // A pragmatic check rather than a full parser: hex groups separated by
        // colons, at most one `::`, at most 8 groups.
        if (host.count { it == ':' } < 2) return false
        val groups = host.split("::")
        if (groups.size > 2) return false
        val present = host.replace("::", ":").trim(':').split(':').filter { it.isNotEmpty() }
        if (present.size > 8) return false
        return present.all { group ->
            group.length in 1..4 && group.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        }
    }
}
