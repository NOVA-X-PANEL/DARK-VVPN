package com.darkvvpn.app.util

/**
 * Central place to decide what may reach logcat.
 *
 * Rule for this project: connection credentials (UUIDs, passwords, private
 * keys, subscription URLs with embedded tokens) are NEVER logged. Use
 * [Redact.secret] when a log line genuinely needs to show such a value.
 */
object Redact {

    private const val KEEP_HEAD = 2
    private const val KEEP_TAIL = 2

    /** `abcdef12-…-9f` → keeps a short, non-reversible-looking fingerprint. */
    fun secret(value: String?): String {
        if (value.isNullOrEmpty()) return "<none>"
        if (value.length <= KEEP_HEAD + KEEP_TAIL) return "***"
        return value.take(KEEP_HEAD) + "***" + value.takeLast(KEEP_TAIL)
    }

    /**
     * Strips the query string from a URL so share/subscription links that carry
     * `?token=…` are safe to log.
     */
    fun url(value: String?): String {
        if (value.isNullOrEmpty()) return "<none>"
        val q = value.indexOf('?')
        return if (q >= 0) value.substring(0, q) + "?<redacted>" else value
    }
}
