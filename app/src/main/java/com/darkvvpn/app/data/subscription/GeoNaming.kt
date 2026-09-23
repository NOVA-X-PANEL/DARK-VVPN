package com.darkvvpn.app.data.subscription

/**
 * Derives an ISO country code and a display country/city from a node's remark.
 *
 * Subscription providers put the location in the remark, and almost all of them
 * include the flag emoji. A regional-indicator pair is exactly the ISO 3166-1
 * alpha-2 code expressed in Unicode, so the flag is the most reliable signal
 * available — far more so than matching city names in a dozen languages.
 */
object GeoNaming {

    /** `🇳🇱 Amsterdam` → `NL`. Returns `null` when the remark carries no flag. */
    fun countryCodeFromFlag(remark: String): String? {
        val codePoints = remark.codePoints().toArray()
        for (i in codePoints.indices) {
            val cp = codePoints[i]
            // Regional Indicator Symbol Letter A = U+1F1E6
            if (cp in 0x1F1E6..0x1F1FF && i + 1 < codePoints.size) {
                val next = codePoints[i + 1]
                if (next in 0x1F1E6..0x1F1FF) {
                    return buildString {
                        append(('A' + (cp - 0x1F1E6)))
                        append(('A' + (next - 0x1F1E6)))
                    }
                }
            }
        }
        return null
    }

    /** Longest-match search so "United States" wins over "United". */
    fun countryFromName(text: String): Pair<String, String>? {
        val haystack = text.lowercase()
        return NAME_TO_ISO.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { haystack.contains(it.key) }
            ?.let { it.value }
    }

    /**
     * Best-effort split of a remark into (country, city). Anything not matched is
     * left in the name, so a remark like `DE-01 | Frankfurt` still yields a code.
     */
    fun resolve(remark: String): ResolvedGeo {
        val code = countryCodeFromFlag(remark)
        if (code != null) {
            val country = ISO_TO_NAME[code]
            return ResolvedGeo(
                countryCode = code,
                country = country ?: code,
                city = cityFromRemark(remark, country),
            )
        }
        countryFromName(remark)?.let { (iso, name) ->
            return ResolvedGeo(
                countryCode = iso,
                country = name,
                city = cityFromRemark(remark, name),
            )
        }
        return ResolvedGeo("", "", "")
    }

    /**
     * Extracts the city from a remark by removing the country name and seeing
     * what is left.
     *
     * The country usually arrives from the flag emoji, which is *not* the text in
     * the remark — `🇳🇱 Amsterdam` names the city and encodes the country, so
     * searching the remark for "Netherlands" finds nothing. Stripping the
     * country when present and treating the remainder as the candidate handles
     * both shapes.
     */
    private fun cityFromRemark(remark: String, country: String?): String {
        val cleaned = stripDecoration(remark).trim()
        if (cleaned.isEmpty()) return ""

        val withoutCountry = if (country.isNullOrBlank()) {
            cleaned
        } else {
            cleaned.replace(country, "", ignoreCase = true)
        }

        val candidate = withoutCountry
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', '·', ',', '#', '_', '.', '/', '\\')
            .trim()

        // Reject anything that is not plausibly a place name: too short, too
        // long, purely numeric, or nothing but the country repeated.
        if (candidate.length !in 2..28) return ""
        if (candidate.none { it.isLetter() }) return ""
        if (candidate.equals(country, ignoreCase = true)) return ""
        // A bare server index such as "01" is not a city.
        if (candidate.all { it.isDigit() || it == ' ' }) return ""

        return candidate
    }

    /** Drops the flag, digits, and separator noise so a city name can be found. */
    fun stripDecoration(remark: String): String {
        val sb = StringBuilder()
        remark.codePoints().forEach { cp ->
            val isFlag = cp in 0x1F1E6..0x1F1FF
            val isNonAsciiSymbol = cp in 0x1F300..0x1FAFF
            if (!isFlag && !isNonAsciiSymbol) sb.appendCodePoint(cp)
        }
        return sb.toString().replace(Regex("[\\[\\](){}]"), " ")
    }

    data class ResolvedGeo(val countryCode: String, val country: String, val city: String)

    /** ISO alpha-2 → display name, for the codes most commonly seen in panels. */
    private val ISO_TO_NAME: Map<String, String> = mapOf(
        "NL" to "Netherlands", "DE" to "Germany", "FR" to "France", "GB" to "United Kingdom",
        "US" to "United States", "CA" to "Canada", "TR" to "Türkiye", "IR" to "Iran",
        "AE" to "UAE", "SG" to "Singapore", "JP" to "Japan", "KR" to "South Korea",
        "CN" to "China", "HK" to "Hong Kong", "TW" to "Taiwan", "IN" to "India",
        "RU" to "Russia", "SE" to "Sweden", "NO" to "Norway", "FI" to "Finland",
        "DK" to "Denmark", "PL" to "Poland", "CH" to "Switzerland", "AT" to "Austria",
        "IT" to "Italy", "ES" to "Spain", "PT" to "Portugal", "IE" to "Ireland",
        "BE" to "Belgium", "CZ" to "Czechia", "RO" to "Romania", "UA" to "Ukraine",
        "AU" to "Australia", "NZ" to "New Zealand", "BR" to "Brazil", "AR" to "Argentina",
        "ZA" to "South Africa", "IL" to "Israel", "SA" to "Saudi Arabia", "QA" to "Qatar",
        "KW" to "Kuwait", "OM" to "Oman", "BH" to "Bahrain", "JO" to "Jordan",
        "AM" to "Armenia", "GE" to "Georgia", "AZ" to "Azerbaijan", "KZ" to "Kazakhstan",
        "ID" to "Indonesia", "MY" to "Malaysia", "TH" to "Thailand", "VN" to "Vietnam",
        "PH" to "Philippines", "PK" to "Pakistan", "BD" to "Bangladesh", "LK" to "Sri Lanka",
        "MX" to "Mexico", "CL" to "Chile", "CO" to "Colombia", "PE" to "Peru",
        "HU" to "Hungary", "BG" to "Bulgaria", "GR" to "Greece", "HR" to "Croatia",
        "RS" to "Serbia", "SK" to "Slovakia", "SI" to "Slovenia", "LT" to "Lithuania",
        "LV" to "Latvia", "EE" to "Estonia", "MD" to "Moldova", "BY" to "Belarus",
    )

    /** Name → (ISO, canonical name). Keys must be lowercase. */
    private val NAME_TO_ISO: Map<String, Pair<String, String>> = buildMap {
        ISO_TO_NAME.forEach { (iso, name) -> put(name.lowercase(), iso to name) }
        // Aliases people actually write in remarks.
        put("holland", "NL" to "Netherlands")
        put("usa", "US" to "United States")
        put("america", "US" to "United States")
        put("uk", "GB" to "United Kingdom")
        put("england", "GB" to "United Kingdom")
        put("britain", "GB" to "United Kingdom")
        put("turkey", "TR" to "Türkiye")
        put("emirates", "AE" to "UAE")
        put("dubai", "AE" to "UAE")
        put("russia", "RU" to "Russia")
        put("deutschland", "DE" to "Germany")
        put("germany", "DE" to "Germany")
        put("frankfurt", "DE" to "Germany")
        put("netherlands", "NL" to "Netherlands")
        put("amsterdam", "NL" to "Netherlands")
        put("korea", "KR" to "South Korea")
    }
}
