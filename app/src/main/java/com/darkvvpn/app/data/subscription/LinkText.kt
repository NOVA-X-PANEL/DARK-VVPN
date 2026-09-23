package com.darkvvpn.app.data.subscription

/**
 * Minimal, allocation-light helpers for share-link payloads.
 *
 * Kept separate from the parser so the fiddly percent-decoding and base64
 * normalisation is testable on its own.
 */
internal object LinkText {

    /** `%2F` → `/`, `+` → space; falls back to the input when it is malformed. */
    fun decode(value: String): String = try {
        java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        value
    }

    /**
     * Decodes base64 the several ways panels emit it: standard or URL-safe
     * alphabet, with or without padding, whitespace or newlines sprinkled in.
     * Returns `null` when the input is not valid base64 at all.
     */
    fun decodeBase64(input: String): String? {
        val cleaned = input.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        val normalised = cleaned
            .replace('-', '+')
            .replace('_', '/')
        val padded = when (normalised.length % 4) {
            2 -> "$normalised=="
            3 -> "$normalised="
            0 -> normalised
            else -> return null
        }
        return try {
            String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Throwable) {
            // Not every payload that reaches here is Android; fall back to the JDK.
            try {
                String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    /** True when [s] looks like base64 rather than a list of share links. */
    fun looksLikeBase64(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.contains("://")) return false
        // Allow UTF-16 BOM remnants and the newlines panels pad base64 with.
        val body = t.filterNot { it.isWhitespace() }
        if (body.length < 16) return false
        val normalised = body.replace('-', '+').replace('_', '/')
        if (normalised.length % 4 !in 0..3) return false
        return normalised.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
    }

    /** Splits a query string into decoded key/value pairs, keeping the first of each. */
    fun queryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        rawQuery.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val idx = pair.indexOf('=')
            if (idx <= 0) {
                out.putIfAbsent(decode(pair), "")
            } else {
                val key = decode(pair.substring(0, idx))
                val value = decode(pair.substring(idx + 1))
                out.putIfAbsent(key, value)
            }
        }
        return out
    }

    /**
     * Splits `#fragment`, tolerating remarks that themselves contain `#`.
     * The fragment is the *last* `#` segment; everything before it is the rest.
     */
    fun splitFragment(link: String): Pair<String, String?> {
        val idx = link.lastIndexOf('#')
        if (idx < 0) return link to null
        return link.substring(0, idx) to decode(link.substring(idx + 1))
    }

    /** Last path segment of a WS/HTTP path, `?ed=2048` and friends stripped. */
    fun normalisePath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val withoutQuery = path.substringBefore('?')
        return when {
            withoutQuery.isEmpty() -> "/"
            withoutQuery.startsWith("/") -> withoutQuery
            else -> "/$withoutQuery"
        }
    }

    /** Truthy values used by the various `insecure`/`allowInsecure` spellings. */
    fun isTruthy(v: String?): Boolean =
        v?.lowercase()?.let { it in setOf("1", "true", "yes", "on") } == true
}
