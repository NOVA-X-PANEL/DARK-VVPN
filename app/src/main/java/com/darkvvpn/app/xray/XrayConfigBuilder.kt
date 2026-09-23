package com.darkvvpn.app.xray

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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Turns a [VpnServer] into an Xray-core configuration document.
 *
 * ── Design ───────────────────────────────────────────────────────────────────
 * There is exactly **one** config path in this app: every input (the bundled
 * catalogue, an imported share link, a fetched subscription) becomes a
 * [VpnServer] first, and this builder is the only thing that knows Xray's
 * schema. Adding an input format therefore never touches config generation, and
 * a config field is added in exactly one place.
 *
 * ── What it produces ─────────────────────────────────────────────────────────
 * A complete, runnable document: a `tun` inbound, the protocol outbound tagged
 * `proxy`, plus `direct`/`block` outbounds and the routing rules that wire them
 * up. The tun inbound is what makes the tunnel real — the app passes the open
 * tun file descriptor to the core as a separate argument
 * (`CoreController.startLoop(config, tunFd)`), and the core's own gVisor
 * netstack then terminates the TCP/IP flows arriving on that interface. There is
 * no separate tun2socks process.
 *
 * ── Deliberate omissions ─────────────────────────────────────────────────────
 * [VpnProtocol.HYSTERIA2] has no Xray outbound (it is QUIC-based and needs a
 * sing-box class core). Rather than emit a document the core would reject at
 * runtime with an opaque error, [build] returns
 * [XrayConfigResult.UnsupportedProtocol] so the UI can say why.
 */
object XrayConfigBuilder {

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** The tag the UI and routing rules refer to when they mean "the tunnel". */
    const val OUTBOUND_TAG_PROXY = "proxy"
    const val OUTBOUND_TAG_DIRECT = "direct"
    const val OUTBOUND_TAG_BLOCK = "block"
    const val INBOUND_TAG_TUN = "tun"

    /**
     * Address handed to the device inside the tunnel, and the gateway the core's
     * netstack answers on. They must be in the same prefix, which is why they are
     * declared together rather than in two files: a mismatch here produces a
     * tunnel that establishes and then silently drops every packet.
     */
    const val TUN_CLIENT_ADDRESS = "10.8.0.2"
    const val TUN_GATEWAY = "10.8.0.1"
    const val TUN_PREFIX_LENGTH = 30
    const val TUN_MTU = 1500

    /** Resolver the device is told to use; queries travel through the tunnel. */
    val TUN_DNS_SERVERS = listOf("1.1.1.1", "1.0.0.1")

    /**
     * @param server the node to dial.
     * @param blockAds add routing rules that blackhole known ad and tracker hosts.
     * @param mtu tun MTU; 1500 unless the operator has a reason to lower it.
     */
    fun build(
        server: VpnServer,
        blockAds: Boolean = true,
        mtu: Int = TUN_MTU,
    ): XrayConfigResult {
        if (!server.protocol.isXrayNative) {
            return XrayConfigResult.UnsupportedProtocol(
                server.protocol,
                reason = "${server.protocol.label} is not an Xray outbound; " +
                    "it needs a sing-box class core to dial.",
            )
        }

        validate(server)?.let { return it }

        val document = buildJsonObject {
            putJsonObject("log") {
                put("loglevel", "warning")
                put("dnsLog", false)
            }

            putJsonObject("policy") {
                putJsonObject("levels") {
                    putJsonObject("8") {
                        put("handshake", 4)
                        put("connIdle", 300)
                        put("uplinkOnly", 1)
                        put("downlinkOnly", 1)
                    }
                }
                putJsonObject("system") {
                    put("statsOutboundUplink", true)
                    put("statsOutboundDownlink", true)
                }
            }

            putJsonArray("inbounds") {
                add(buildTunInbound(mtu))
            }

            putJsonArray("outbounds") {
                add(buildProtocolOutbound(server))
                add(buildJsonObject {
                    put("tag", OUTBOUND_TAG_DIRECT)
                    put("protocol", "freedom")
                })
                add(buildJsonObject {
                    put("tag", OUTBOUND_TAG_BLOCK)
                    put("protocol", "blackhole")
                    putJsonObject("settings") {
                        putJsonObject("response") { put("type", "http") }
                    }
                })
            }

            putJsonObject("routing") {
                put("domainStrategy", "AsIs")
                putJsonArray("rules") {
                    // ORDER MATTERS: Xray stops at the first matching rule, so
                    // every specific rule must precede the catch-all. Putting the
                    // catch-all first would silently disable the ad blocker.
                    if (blockAds) {
                        add(buildJsonObject {
                            put("type", "field")
                            put("outboundTag", OUTBOUND_TAG_BLOCK)
                            putJsonArray("domain") {
                                add("geosite:category-ads-all")
                            }
                        })
                    }

                    // Loopback and link-local traffic stays off the tunnel, so a
                    // LAN device (a printer, a NAS) is still reachable.
                    add(buildJsonObject {
                        put("type", "field")
                        put("outboundTag", OUTBOUND_TAG_DIRECT)
                        putJsonArray("ip") {
                            add("geoip:private")
                        }
                    })

                    // Everything else goes through the node. This is the
                    // catch-all and must stay last.
                    add(buildJsonObject {
                        put("type", "field")
                        put("outboundTag", OUTBOUND_TAG_PROXY)
                        putJsonArray("network") { add("tcp,udp") }
                    })
                }
            }

            // No `dns` section: DNS is the device's own resolver pointed at
            // TUN_DNS_SERVERS, and those queries arrive at the netstack as
            // ordinary UDP flows and travel through the node like any other
            // traffic. Configuring Xray DNS here as well would hijack them.
        }

        return XrayConfigResult.Success(
            document = document,
            rendered = json.encodeToString(JsonElement.serializer(), document),
        )
    }

    /**
     * Rejects a node whose credentials are missing *before* a config is built,
     * so the failure names the missing field instead of surfacing as a parse
     * error inside the core.
     */
    private fun validate(server: VpnServer): XrayConfigResult? {
        if (server.host.isBlank()) {
            return XrayConfigResult.InvalidNode("The node has no host.")
        }
        if (server.port !in 1..65535) {
            return XrayConfigResult.InvalidNode("Port ${server.port} is out of range.")
        }
        val missing = when (server.protocol) {
            VpnProtocol.VLESS, VpnProtocol.VMESS ->
                if (server.uuid.isNullOrBlank()) "uuid" else null

            VpnProtocol.TROJAN ->
                if (server.password.isNullOrBlank()) "password" else null

            VpnProtocol.SHADOWSOCKS -> when {
                server.method.isNullOrBlank() -> "method"
                server.password.isNullOrBlank() -> "password"
                else -> null
            }

            VpnProtocol.SOCKS, VpnProtocol.HTTP -> null // both allow anonymous auth

            VpnProtocol.WIREGUARD -> when {
                server.wgPrivateKey.isNullOrBlank() -> "privateKey"
                server.wgPeerPublicKey.isNullOrBlank() -> "peerPublicKey"
                else -> null
            }

            VpnProtocol.HYSTERIA2 -> return null // handled above
        }
        if (missing != null) {
            return XrayConfigResult.InvalidNode(
                "A ${server.protocol.label} node needs a $missing.",
            )
        }
        if (server.security == VpnSecurity.REALITY) {
            if (server.publicKey.isNullOrBlank()) {
                return XrayConfigResult.InvalidNode("A REALITY node needs a publicKey.")
            }
            if (server.sni.isNullOrBlank()) {
                return XrayConfigResult.InvalidNode("A REALITY node needs a serverName (sni).")
            }
        }
        if (server.security == VpnSecurity.XTLS && server.protocol != VpnProtocol.VLESS) {
            return XrayConfigResult.InvalidNode("XTLS is only defined for VLESS.")
        }
        return null
    }

    // ------------------------------------------------------------------
    // Inbound
    // ------------------------------------------------------------------

    /**
     * The `tun` inbound.
     *
     * The file descriptor is **not** in here: the app opens the tun through
     * `VpnService.Builder.establish()` and hands the descriptor to the core as
     * the second argument of `startLoop`. What this section configures is the
     * core's side of that interface.
     *
     * The field names follow Xray's `infra/conf/tun.go`
     * (`name`, `mtu`, `gateway`, `userLevel`). `autoSystemRoutingTable` is
     * deliberately absent — it rewrites the host routing table, which on Android
     * is `VpnService`'s job and would fail without root.
     */
    private fun buildTunInbound(mtu: Int): JsonObject = buildJsonObject {
        put("tag", INBOUND_TAG_TUN)
        put("protocol", "tun")
        putJsonObject("settings") {
            put("name", "xray0")
            put("mtu", mtu)
            put("userLevel", 8)
            // Must fall inside the same prefix as the address Android is given,
            // or the netstack has no reachable gateway and every flow stalls.
            putJsonArray("gateway") { add(TUN_GATEWAY) }
        }
        putJsonObject("sniffing") {
            put("enabled", true)
            putJsonArray("destOverride") {
                add("http")
                add("tls")
                add("quic")
            }
            // `routeOnly` is false, so domains sniffed here are also used for
            // routing decisions — this is what makes `geosite:` rules work.
            put("routeOnly", false)
        }
    }

    // ------------------------------------------------------------------
    // Outbound, one branch per protocol
    // ------------------------------------------------------------------
    private fun buildProtocolOutbound(server: VpnServer): JsonObject = when (server.protocol) {
        VpnProtocol.VLESS -> outbound(server) {
            putJsonObject("settings") {
                putJsonArray("vnext") {
                    addJsonObject {
                        put("address", server.host)
                        put("port", server.port)
                        putJsonArray("users") {
                            addJsonObject {
                                put("id", server.uuid.orEmpty())
                                put("encryption", "none")
                                // flow is only valid for VLESS over TLS/REALITY.
                                if (server.flow != VlessFlow.NONE &&
                                    server.security in setOf(VpnSecurity.TLS, VpnSecurity.REALITY, VpnSecurity.XTLS)
                                ) {
                                    put("flow", server.flow.wireName)
                                }
                            }
                        }
                    }
                }
            }
        }

        VpnProtocol.VMESS -> outbound(server) {
            putJsonObject("settings") {
                putJsonArray("vnext") {
                    addJsonObject {
                        put("address", server.host)
                        put("port", server.port)
                        putJsonArray("users") {
                            addJsonObject {
                                put("id", server.uuid.orEmpty())
                                put("alterId", server.alterId)
                                put("security", server.vmessSecurity)
                            }
                        }
                    }
                }
            }
        }

        VpnProtocol.TROJAN -> outbound(server) {
            putJsonObject("settings") {
                putJsonArray("servers") {
                    addJsonObject {
                        put("address", server.host)
                        put("port", server.port)
                        put("password", server.password.orEmpty())
                    }
                }
            }
        }

        VpnProtocol.SHADOWSOCKS -> outbound(server) {
            putJsonObject("settings") {
                putJsonArray("servers") {
                    addJsonObject {
                        put("address", server.host)
                        put("port", server.port)
                        put("method", server.method.orEmpty())
                        put("password", server.password.orEmpty())
                    }
                }
            }
        }

        VpnProtocol.SOCKS, VpnProtocol.HTTP -> outbound(server) {
            // SOCKS/HTTP siblings: same `servers` shape, no stream settings.
            putJsonObject("settings") {
                putJsonArray("servers") {
                    addJsonObject {
                        put("address", server.host)
                        put("port", server.port)
                        if (!server.password.isNullOrBlank()) {
                            putJsonArray("users") {
                                addJsonObject {
                                    put("user", server.uuid.orEmpty())
                                    put("pass", server.password.orEmpty())
                                }
                            }
                        }
                    }
                }
            }
        }

        VpnProtocol.WIREGUARD -> buildJsonObject {
            // WireGuard has no streamSettings and no TLS layer of its own.
            put("tag", OUTBOUND_TAG_PROXY)
            put("protocol", "wireguard")
            putJsonObject("settings") {
                put("secretKey", server.wgPrivateKey.orEmpty())
                putJsonArray("address") {
                    if (server.wgLocalAddress.isEmpty()) add("$TUN_CLIENT_ADDRESS/$TUN_PREFIX_LENGTH")
                    else server.wgLocalAddress.forEach { add(it) }
                }
                server.wgMtu?.let { put("mtu", it) }
                putJsonArray("peers") {
                    addJsonObject {
                        put("publicKey", server.wgPeerPublicKey.orEmpty())
                        server.wgPreSharedKey?.takeIf { it.isNotBlank() }
                            ?.let { put("preSharedKey", it) }
                        put("endpoint", "${server.host}:${server.port}")
                        putJsonArray("allowedIPs") {
                            add("0.0.0.0/0")
                            add("::/0")
                        }
                        put("keepAlive", 25)
                    }
                }
            }
        }

        VpnProtocol.HYSTERIA2 -> buildJsonObject { /* unreachable: rejected above */ }
    }

    /**
     * Assembles one outbound in Xray's field order: tag, protocol, settings,
     * streamSettings, mux. [body] supplies only the protocol-specific `settings`.
     */
    private fun outbound(
        server: VpnServer,
        body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("tag", OUTBOUND_TAG_PROXY)
        // The Xray name, not the share-link scheme: `ss://` is distributed as
        // `shadowsocks` in a config, and the other way round fails to start.
        put("protocol", server.protocol.xrayProtocol)
        body()
        buildStreamSettings(server)?.let { put("streamSettings", it) }
        putJsonObject("mux") {
            put("enabled", false)
        }
    }

    // ------------------------------------------------------------------
    // streamSettings: security layer + transport layer
    // ------------------------------------------------------------------
    private fun buildStreamSettings(server: VpnServer): JsonObject? {
        val isTunnel = server.protocol in setOf(
            VpnProtocol.VLESS, VpnProtocol.VMESS, VpnProtocol.TROJAN,
        )
        // Shadowsocks and plain proxies ignore TLS/transport settings entirely.
        if (!isTunnel) return null

        return buildJsonObject {
            put("network", server.transport.wireName)

            when (server.security) {
                VpnSecurity.REALITY -> {
                    put("security", "reality")
                    putJsonObject("realitySettings") {
                        put("serverName", server.sni.orEmpty())
                        put("fingerprint", server.fingerprint ?: "chrome")
                        put("publicKey", server.publicKey.orEmpty())
                        put("shortId", server.shortId.orEmpty())
                        put("spiderX", server.spiderX ?: "/")
                        put("show", false)
                    }
                }

                VpnSecurity.TLS, VpnSecurity.XTLS -> {
                    put("security", server.security.wireName)
                    val key = if (server.security == VpnSecurity.XTLS) "xtlsSettings" else "tlsSettings"
                    putJsonObject(key) {
                        put("serverName", server.sni ?: server.hostHeader ?: server.host)
                        put("allowInsecure", server.allowInsecure)
                        server.fingerprint?.let { put("fingerprint", it) }
                        if (server.alpn.isNotEmpty()) {
                            putJsonArray("alpn") { server.alpn.forEach { add(it) } }
                        }
                    }
                }

                VpnSecurity.NONE -> put("security", "none")
            }

            buildTransportSettings(server)?.let { transport ->
                transport.forEach { (k, v) -> put(k, v) }
            }
        }
    }

    /** The transport-specific sub-object, keyed by its Xray settings name. */
    private fun buildTransportSettings(server: VpnServer): Map<String, JsonElement>? {
        val path = server.path?.takeIf { it.isNotBlank() }
        val hostHeader = server.hostHeader ?: server.sni ?: server.host

        // Xray expects `"headers": { "Host": "…" }` — a flat object whose key is
        // the header name. Nesting another `headers` level here produces a config
        // where the Host is silently ignored, which breaks WS nodes whose server
        // routes on it.
        fun wsHeaders(): JsonObject = buildJsonObject {
            put("Host", hostHeader)
        }

        return when (server.transport) {
            VpnTransport.WS -> mapOf(
                "wsSettings" to buildJsonObject {
                    put("path", path ?: "/")
                    put("headers", wsHeaders())
                },
            )

            VpnTransport.HTTP -> mapOf(
                "httpSettings" to buildJsonObject {
                    putJsonArray("path") { add(path ?: "/") }
                    putJsonArray("host") { add(hostHeader) }
                },
            )

            VpnTransport.HTTP_UPGRADE -> mapOf(
                "httpupgradeSettings" to buildJsonObject {
                    put("path", path ?: "/")
                    put("host", hostHeader)
                },
            )

            VpnTransport.SPLIT_HTTP -> mapOf(
                "splithttpSettings" to buildJsonObject {
                    put("path", path ?: "/")
                    put("host", hostHeader)
                },
            )

            VpnTransport.XHTTP -> mapOf(
                "xhttpSettings" to buildJsonObject {
                    put("path", path ?: "/")
                    put("host", hostHeader)
                },
            )

            VpnTransport.GRPC -> mapOf(
                "grpcSettings" to buildJsonObject {
                    put("serviceName", server.serviceName.orEmpty())
                    put("multiMode", false)
                },
            )

            VpnTransport.QUIC -> mapOf(
                "quicSettings" to buildJsonObject {
                    put("security", "none")
                    put("key", "")
                    putJsonObject("header") { put("type", "none") }
                },
            )

            VpnTransport.KCP -> mapOf(
                "kcpSettings" to buildJsonObject {
                    putJsonObject("header") { put("type", "none") }
                    server.kcpSeed?.takeIf { it.isNotBlank() }?.let { put("seed", it) }
                },
            )

            VpnTransport.TCP, VpnTransport.RAW -> null
        }
    }

    /** Convenience for tests and diagnostics: the JSON string only. */
    fun render(server: VpnServer, blockAds: Boolean = true): String? =
        (build(server, blockAds = blockAds) as? XrayConfigResult.Success)?.rendered
}

/** Outcome of a config build, so every failure has a nameable reason. */
sealed interface XrayConfigResult {
    /** [rendered] is the pretty-printed document; [document] is its tree form. */
    data class Success(val document: JsonObject, val rendered: String) : XrayConfigResult

    /** The protocol has no Xray outbound at all. */
    data class UnsupportedProtocol(
        val protocol: VpnProtocol,
        val reason: String,
    ) : XrayConfigResult

    /** The node is structurally fine but is missing a required field. */
    data class InvalidNode(val reason: String) : XrayConfigResult

    val isSuccess: Boolean get() = this is Success

    /** A message safe to show the user, or `null` on success. */
    val errorMessage: String?
        get() = when (this) {
            is Success -> null
            is UnsupportedProtocol -> reason
            is InvalidNode -> reason
        }
}
