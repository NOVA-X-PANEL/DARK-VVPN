package com.darkvvpn.app.data.subscription

import com.darkvvpn.app.data.model.SubscriptionFormat
import com.darkvvpn.app.data.model.VlessFlow
import com.darkvvpn.app.data.model.VpnProtocol
import com.darkvvpn.app.data.model.VpnSecurity
import com.darkvvpn.app.data.model.VpnServer
import com.darkvvpn.app.data.model.VpnTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses everything a panel or provider might serve as a node list.
 *
 * ── Inputs it accepts ────────────────────────────────────────────────────────
 *  - a single share link (`vless://`, `vmess://`, `trojan://`, `ss://`,
 *    `hysteria2://`, `socks://`, `http://`)
 *  - a newline-separated list of share links (plain text)
 *  - a base64 blob that decodes to either of the above
 *  - a Clash/mihomo YAML document with a `proxies:` list
 *  - a sing-box JSON document with an `outbounds` array
 *  - the `subscription-userinfo` response header (traffic quota)
 *
 * ── Why it never throws ──────────────────────────────────────────────────────
 * A subscription is third-party input fetched over the network. One malformed
 * node out of two hundred must not cost the user the other hundred and
 * ninety-nine, so every failure is recorded in [SubscriptionParseResult.skipped]
 * and parsing continues.
 */
class SubscriptionParser(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    fun parse(payload: String, userInfoHeader: String? = null): SubscriptionParseResult {
        val body = payload.trim()
        if (body.isEmpty()) {
            return SubscriptionParseResult(
                servers = emptyList(),
                format = SubscriptionFormat.UNKNOWN,
                traffic = SubscriptionTraffic.parse(userInfoHeader),
                error = "The subscription returned an empty body.",
            )
        }

        // 1. Clash / sing-box documents announce themselves structurally.
        if (looksLikeClash(body)) {
            val servers = parseClash(body)
            return result(servers, SubscriptionFormat.CLASH_YAML, userInfoHeader)
        }
        if (looksLikeSingBox(body)) {
            val servers = parseSingBox(body)
            return result(servers, SubscriptionFormat.SINGBOX_JSON, userInfoHeader)
        }

        // 2. A body that is not already links is usually base64.
        val expanded = if (LinkText.looksLikeBase64(body)) {
            LinkText.decodeBase64(body) ?: body
        } else {
            body
        }

        val format = if (LinkText.looksLikeBase64(body)) {
            SubscriptionFormat.BASE64_LIST
        } else {
            SubscriptionFormat.PLAIN_LINKS
        }

        val servers = parseLinkList(expanded)
        return result(servers, format, userInfoHeader)
    }

    /** Parses a plain list of links (already decoded), collecting per-line errors. */
    fun parseLinkList(text: String): List<VpnServer> {
        val servers = ArrayList<VpnServer>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .forEach { line ->
                parseLink(line)?.let { servers += it }
            }
        return servers
    }

    /**
     * Parses one share link into a node, or `null` when the scheme is unknown or
     * the link is unusable.
     */
    fun parseLink(rawLink: String): VpnServer? {
        val link = rawLink.trim().removePrefix("\uFEFF")
        if (link.isEmpty()) return null

        val scheme = link.substringBefore("://", "").lowercase()
        val protocol = VpnProtocol.fromScheme(scheme) ?: return null

        return when (protocol) {
            VpnProtocol.VMESS -> parseVmess(link)
            VpnProtocol.SHADOWSOCKS -> parseShadowsocks(link)
            else -> parseStandard(link, protocol)
        }
    }

    // ==================================================================
    // vless / trojan / hysteria2 / socks / http
    // ==================================================================
    private fun parseStandard(link: String, protocol: VpnProtocol): VpnServer? {
        val (head, remark) = LinkText.splitFragment(link)
        val afterScheme = head.substringAfter("://")
        if (afterScheme.isEmpty()) return null

        // `userinfo@host:port` — userinfo may hold a uuid, password, or user:pass.
        val at = afterScheme.lastIndexOf('@')
        val userInfo = if (at >= 0) afterScheme.substring(0, at) else ""
        val hostPart = if (at >= 0) afterScheme.substring(at + 1) else afterScheme

        val (hostPort, rawQuery) = hostPart.split('?', limit = 2).let {
            it[0] to it.getOrNull(1)
        }
        val host = parseHost(hostPort) ?: return null
        val port = parsePort(hostPort) ?: protocol.defaultPort
        val params = LinkText.queryParams(rawQuery)

        val (user, pass) = splitCredentials(userInfo, protocol)
        val security = resolveSecurity(params, protocol, port)
        val transport = VpnTransport.fromName(params["type"] ?: params["net"])
        val geo = GeoNaming.resolve(remark ?: host)

        val base = VpnServer(
            name = (remark ?: host).ifBlank { host },
            country = geo.country,
            countryCode = geo.countryCode,
            city = geo.city,
            host = host,
            port = port,
            protocol = protocol,
            security = security,
            transport = transport,
            sni = params["sni"] ?: params["peer"] ?: params["serverName"],
            fingerprint = params["fp"] ?: params["fingerprint"],
            publicKey = params["pbk"] ?: params["publicKey"],
            shortId = params["sid"] ?: params["shortId"],
            spiderX = params["spx"] ?: params["spiderX"],
            allowInsecure = LinkText.isTruthy(params["allowInsecure"]) ||
                LinkText.isTruthy(params["insecure"]) ||
                LinkText.isTruthy(params["allowinsecure"]),
            alpn = params["alpn"]?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
            path = LinkText.normalisePath(params["path"]),
            hostHeader = params["host"] ?: params["hostheader"],
            serviceName = params["serviceName"] ?: params["servicename"],
            kcpSeed = params["seed"],
            uuid = if (protocol == VpnProtocol.VLESS) user else null,
            password = when (protocol) {
                VpnProtocol.TROJAN, VpnProtocol.HYSTERIA2 -> user
                VpnProtocol.SOCKS, VpnProtocol.HTTP -> pass ?: user
                else -> pass
            },
            method = params["method"],
            flow = VlessFlow.fromName(params["flow"]),
        )
        return if (base.host.isBlank()) null else base
    }

    // ==================================================================
    // vmess — base64 JSON body (v2) or the legacy plain form
    // ==================================================================
    private fun parseVmess(link: String): VpnServer? {
        val (head, remark) = LinkText.splitFragment(link)
        val payload = head.substringAfter("://")
        if (payload.isEmpty()) return null

        // Legacy: `vmess://method:uuid@host:port?...` — not JSON, no base64.
        if (payload.contains('@') && !payload.trimStart().startsWith("{")) {
            return parseVmessLegacy(payload, remark)
        }

        val decoded = LinkText.decodeBase64(payload) ?: return null
        val node = try {
            json.parseToJsonElement(decoded).jsonObject
        } catch (_: Throwable) {
            return null
        }

        val host = node.str("add") ?: return null
        val port = node.str("port")?.toIntOrNull()
            ?: node.int("port")
            ?: VpnProtocol.VMESS.defaultPort

        // VMess carries its TLS state in `tls` ("" or "tls") rather than `security`.
        val tlsValue = node.str("tls").orEmpty()
        val security = if (tlsValue.isNotBlank() && tlsValue != "none") {
            VpnSecurity.TLS
        } else {
            VpnSecurity.NONE
        }

        val rawRemark = node.str("ps")?.takeIf { it.isNotBlank() } ?: remark ?: host
        val geo = GeoNaming.resolve(rawRemark)

        return VpnServer(
            name = rawRemark,
            country = geo.country,
            countryCode = geo.countryCode,
            city = geo.city,
            host = host,
            port = port,
            protocol = VpnProtocol.VMESS,
            security = security,
            transport = VpnTransport.fromName(node.str("net")),
            sni = node.str("sni")?.takeIf { it.isNotBlank() } ?: node.str("host"),
            fingerprint = node.str("fp"),
            // VMess has no standard insecure flag; accept the two spellings that
            // the panels that do emit one actually use.
            allowInsecure = LinkText.isTruthy(node.str("allowInsecure")) ||
                LinkText.isTruthy(node.str("skip-cert-verify")),
            alpn = node.str("alpn")?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
            path = LinkText.normalisePath(node.str("path")),
            hostHeader = node.str("host")?.takeIf { it.isNotBlank() },
            serviceName = node.str("path")?.takeIf {
                VpnTransport.fromName(node.str("net")) == VpnTransport.GRPC
            },
            uuid = node.str("id"),
            alterId = node.str("aid")?.toIntOrNull() ?: node.int("aid") ?: 0,
            vmessSecurity = node.str("scy")?.takeIf { it.isNotBlank() } ?: "auto",
        )
    }

    private fun parseVmessLegacy(payload: String, remark: String?): VpnServer? {
        val (userInfo, hostAndQuery) = payload.split('@', limit = 2).let {
            it[0] to it.getOrNull(1).orEmpty()
        }
        val (hostPort, rawQuery) = hostAndQuery.split('?', limit = 2).let {
            it[0] to it.getOrNull(1)
        }
        val host = parseHost(hostPort) ?: return null
        val params = LinkText.queryParams(rawQuery)
        // Legacy form: `method:uuid`
        val uuid = userInfo.substringAfter(':', userInfo).takeIf { it.isNotBlank() } ?: return null
        val geo = GeoNaming.resolve(remark ?: host)

        return VpnServer(
            name = (remark ?: host).ifBlank { host },
            country = geo.country,
            countryCode = geo.countryCode,
            city = geo.city,
            host = host,
            port = parsePort(hostPort) ?: VpnProtocol.VMESS.defaultPort,
            protocol = VpnProtocol.VMESS,
            security = if ((params["tls"] ?: "").lowercase().contains("tls")) VpnSecurity.TLS else VpnSecurity.NONE,
            transport = VpnTransport.fromName(params["type"] ?: params["net"]),
            sni = params["sni"] ?: params["host"],
            path = LinkText.normalisePath(params["path"]),
            hostHeader = params["host"],
            serviceName = params["serviceName"],
            uuid = uuid,
            alterId = params["aid"]?.toIntOrNull() ?: 0,
        )
    }

    // ==================================================================
    // shadowsocks — SIP002 and the legacy whole-body base64 form
    // ==================================================================
    private fun parseShadowsocks(link: String): VpnServer? {
        val (head, remark) = LinkText.splitFragment(link)
        var body = head.substringAfter("://")
        if (body.isEmpty()) return null

        // Legacy: the whole `method:password@host:port` is base64 with no `@`.
        if (!body.contains('@')) {
            val decoded = LinkText.decodeBase64(body) ?: return null
            body = decoded
        }

        val at = body.lastIndexOf('@')
        if (at < 0) return null
        val credPart = body.substring(0, at)
        val hostPart = body.substring(at + 1)

        val (hostPort, rawQuery) = hostPart.split('?', limit = 2).let { it[0] to it.getOrNull(1) }
        val host = parseHost(hostPort) ?: return null
        val port = parsePort(hostPort) ?: VpnProtocol.SHADOWSOCKS.defaultPort

        // `method:password` — either plain or base64 (SIP002 uses base64 here).
        val credentials = if (credPart.contains(':')) {
            credPart
        } else {
            LinkText.decodeBase64(credPart) ?: return null
        }
        val method = credentials.substringBefore(':', "")
        val password = credentials.substringAfter(':', "")

        val params = LinkText.queryParams(rawQuery)
        val geo = GeoNaming.resolve(remark ?: host)

        return VpnServer(
            name = (remark ?: host).ifBlank { host },
            country = geo.country,
            countryCode = geo.countryCode,
            city = geo.city,
            host = host,
            port = port,
            protocol = VpnProtocol.SHADOWSOCKS,
            security = VpnSecurity.NONE,
            transport = VpnTransport.TCP,
            method = method.takeIf { it.isNotBlank() },
            password = password.takeIf { it.isNotBlank() },
            path = params["plugin"].takeIf { !it.isNullOrBlank() },
        )
    }

    // ==================================================================
    // Clash / mihomo YAML  (proxies: list)
    // ==================================================================
    private fun looksLikeClash(body: String): Boolean =
        body.contains("proxies:") && !body.trimStart().startsWith("{")

    private fun parseClash(body: String): List<VpnServer> {
        val servers = ArrayList<VpnServer>()
        var inProxies = false
        var current: MutableMap<String, String>? = null

        fun flush() {
            current?.let { parseClashProxy(it)?.let { s -> servers += s } }
            current = null
        }

        body.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore(" #").trimEnd()
            if (line.isBlank()) return@forEach

            if (!inProxies) {
                if (line.trimEnd().startsWith("proxies:")) inProxies = true
                return@forEach
            }
            // A new top-level key ends the proxies block.
            if (line.isNotEmpty() && !line[0].isWhitespace()) {
                flush()
                inProxies = false
                return@forEach
            }

            val trimmed = line.trim()
            when {
                trimmed.startsWith("- ") || trimmed == "-" -> {
                    flush()
                    current = LinkedHashMap()
                    parseClashPair(trimmed.removePrefix("-").trim())?.let { (k, v) ->
                        current?.put(k, v)
                    }
                }
                else -> parseClashPair(trimmed)?.let { (k, v) -> current?.put(k, v) }
            }
        }
        flush()
        return servers
    }

    private fun parseClashPair(line: String): Pair<String, String>? {
        val idx = line.indexOf(':')
        if (idx <= 0) return null
        val key = line.substring(0, idx).trim()
        val value = stripQuotes(line.substring(idx + 1).trim())
        if (key.isEmpty()) return null
        return key to value
    }

    private fun parseClashProxy(m: Map<String, String>): VpnServer? {
        val type = m["type"]?.lowercase() ?: return null
        val host = m["server"] ?: return null
        val port = m["port"]?.toIntOrNull() ?: return null
        val name = m["name"] ?: host
        val geo = GeoNaming.resolve(name)

        val protocol = when (type) {
            "vless" -> VpnProtocol.VLESS
            "vmess" -> VpnProtocol.VMESS
            "trojan" -> VpnProtocol.TROJAN
            "ss", "shadowsocks" -> VpnProtocol.SHADOWSOCKS
            "hysteria2", "hy2" -> VpnProtocol.HYSTERIA2
            "socks5", "socks" -> VpnProtocol.SOCKS
            "http", "https" -> VpnProtocol.HTTP
            "wireguard" -> VpnProtocol.WIREGUARD
            else -> return null
        }

        val tlsFlag = m["tls"]?.lowercase()
        val reality = m["reality-opts"] != null || m["public-key"] != null
        val security = when {
            reality -> VpnSecurity.REALITY
            tlsFlag == "true" -> VpnSecurity.TLS
            else -> VpnSecurity.NONE
        }

        return VpnServer(
            name = name,
            country = geo.country,
            countryCode = geo.countryCode,
            city = geo.city,
            host = host,
            port = port,
            protocol = protocol,
            security = security,
            transport = VpnTransport.fromName(m["network"]),
            sni = m["servername"] ?: m["sni"],
            fingerprint = m["client-fingerprint"],
            publicKey = m["public-key"],
            shortId = m["short-id"],
            allowInsecure = m["skip-cert-verify"]?.lowercase() == "true",
            alpn = m["alpn"]?.trim('[', ']')?.split(',')
                ?.map { stripQuotes(it.trim()) }?.filter { it.isNotBlank() } ?: emptyList(),
            path = LinkText.normalisePath(m["ws-path"] ?: m["path"]),
            hostHeader = m["ws-headers"]?.substringAfter("Host:", "")?.trim()?.takeIf { it.isNotBlank() },
            uuid = m["uuid"],
            password = m["password"],
            method = m["cipher"],
            alterId = m["alterId"]?.toIntOrNull() ?: 0,
        )
    }

    // ==================================================================
    // sing-box JSON  (outbounds: [])
    // ==================================================================
    private fun looksLikeSingBox(body: String): Boolean =
        body.trimStart().startsWith("{") &&
            (body.contains("\"outbounds\"") || body.contains("\"inbounds\""))

    private fun parseSingBox(body: String): List<VpnServer> {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Throwable) {
            return emptyList()
        }
        val outbounds = (root["outbounds"] as? JsonArray) ?: return emptyList()
        return outbounds.mapNotNull { parseSingBoxOutbound(it) }
    }

    private fun parseSingBoxOutbound(element: JsonElement): VpnServer? {
        val o = element as? JsonObject ?: return null
        val type = o.str("type")?.lowercase() ?: return null
        val protocol = when (type) {
            "vless" -> VpnProtocol.VLESS
            "vmess" -> VpnProtocol.VMESS
            "trojan" -> VpnProtocol.TROJAN
            "shadowsocks" -> VpnProtocol.SHADOWSOCKS
            "hysteria2" -> VpnProtocol.HYSTERIA2
            "socks" -> VpnProtocol.SOCKS
            "http" -> VpnProtocol.HTTP
            "wireguard" -> VpnProtocol.WIREGUARD
            else -> return null
        }

        val host = o.str("server") ?: return null
        val port = o.str("server_port")?.toIntOrNull() ?: return null
        val tag = o.str("tag")?.takeIf { it.isNotBlank() } ?: host
        val geo = GeoNaming.resolve(tag)

        val tls = o["tls"] as? JsonObject
        val reality = tls?.get("reality") as? JsonObject
        val security = when {
            reality != null -> VpnSecurity.REALITY
            tls != null -> VpnSecurity.TLS
            else -> VpnSecurity.NONE
        }

        val transport = o["transport"] as? JsonObject
        val transportType = transport?.str("type")
        val path = transport?.str("path")
        val hostHeader = (transport?.get("headers") as? JsonObject)?.str("Host")

        return VpnServer(
            name = tag,
            country = geo.country,
            countryCode = geo.countryCode,
            city = geo.city,
            host = host,
            port = port,
            protocol = protocol,
            security = security,
            transport = VpnTransport.fromName(transportType),
            sni = tls?.str("server_name"),
            publicKey = reality?.str("public_key"),
            shortId = reality?.str("short_id"),
            allowInsecure = tls?.get("insecure")?.jsonPrimitive?.booleanOrNull == true,
            alpn = (tls?.get("alpn") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
            path = LinkText.normalisePath(path),
            hostHeader = hostHeader,
            serviceName = transport?.str("service_name"),
            uuid = o.str("uuid"),
            password = o.str("password"),
            method = o.str("method"),
        )
    }

    // ==================================================================
    // helpers
    // ==================================================================
    private fun result(
        servers: List<VpnServer>,
        format: SubscriptionFormat,
        userInfoHeader: String?,
    ): SubscriptionParseResult = SubscriptionParseResult(
        servers = servers,
        format = format,
        traffic = SubscriptionTraffic.parse(userInfoHeader),
        error = if (servers.isEmpty()) {
            "No usable node was found in the subscription payload."
        } else {
            null
        },
    )

    private fun resolveSecurity(
        params: Map<String, String>,
        protocol: VpnProtocol,
        port: Int,
    ): VpnSecurity {
        params["security"]?.let { return VpnSecurity.fromName(it) }
        // No explicit flag: infer from the shape of the link, which is how most
        // panels published before `security=` became common were written.
        if (params["pbk"] != null) return VpnSecurity.REALITY
        if (params["tls"]?.lowercase() in setOf("tls", "1", "true")) return VpnSecurity.TLS
        return when (protocol) {
            // Trojan is TLS by definition; a 443 Hysteria2 node is too.
            VpnProtocol.TROJAN, VpnProtocol.HYSTERIA2 -> VpnSecurity.TLS
            else -> VpnSecurity.NONE
        }
    }

    /** Splits userinfo into (user, password), honouring each protocol's shape. */
    private fun splitCredentials(userInfo: String, protocol: VpnProtocol): Pair<String, String?> {
        if (userInfo.isEmpty()) return "" to null
        val decoded = LinkText.decode(userInfo)
        return when (protocol) {
            VpnProtocol.SOCKS, VpnProtocol.HTTP -> {
                val idx = decoded.indexOf(':')
                if (idx < 0) decoded to null
                else decoded.substring(0, idx) to decoded.substring(idx + 1)
            }
            else -> decoded to null
        }
    }

    /** Host from a `host:port` pair, unwrapping `[::1]:443` IPv6 literals. */
    private fun parseHost(hostPort: String): String? {
        val h = hostPort.substringBefore('?').trim()
        if (h.isEmpty()) return null
        if (h.startsWith("[")) {
            val end = h.indexOf(']')
            if (end < 0) return null
            return h.substring(1, end).ifBlank { null }
        }
        val host = h.substringBefore(':')
        return host.ifBlank { null }
    }

    private fun parsePort(hostPort: String): Int? {
        val h = hostPort.substringBefore('?').trim()
        val idx = h.lastIndexOf(':')
        if (idx < 0 || idx == h.length - 1) return null
        return h.substring(idx + 1).toIntOrNull()?.takeIf { it in 1..65535 }
    }

    private fun stripQuotes(s: String): String =
        s.removeSurrounding("\"").removeSurrounding("'")

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull
}

/** Everything a subscription body yields. */
data class SubscriptionParseResult(
    val servers: List<VpnServer>,
    val format: SubscriptionFormat,
    val traffic: SubscriptionTraffic? = null,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null && servers.isNotEmpty()
}

/** The `subscription-userinfo` header, when the provider sends one. */
data class SubscriptionTraffic(
    val uploadBytes: Long?,
    val downloadBytes: Long?,
    val totalBytes: Long?,
    val expiresAtEpochMillis: Long?,
) {
    val usedBytes: Long? get() =
        if (uploadBytes == null && downloadBytes == null) null
        else (uploadBytes ?: 0L) + (downloadBytes ?: 0L)

    companion object {
        /**
         * `upload=123; download=456; total=789; expire=1700000000`
         * Unknown keys are ignored rather than rejected, because providers add
         * their own fields.
         */
        fun parse(header: String?): SubscriptionTraffic? {
            if (header.isNullOrBlank()) return null
            val fields = header.split(';')
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) null
                    else part.substring(0, idx).trim().lowercase() to
                        part.substring(idx + 1).trim().toLongOrNull()
                }
                .toMap()
            if (fields.isEmpty()) return null
            return SubscriptionTraffic(
                uploadBytes = fields["upload"],
                downloadBytes = fields["download"],
                totalBytes = fields["total"],
                expiresAtEpochMillis = fields["expire"]?.takeIf { it > 0 }?.times(1000),
            )
        }
    }
}
