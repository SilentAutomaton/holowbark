package net.yggawg.mobile.config

/**
 * Parsed AmneziaWG .conf file.
 * Supports standard WireGuard fields plus AmneziaWG obfuscation parameters
 * (Jc, Jmin, Jmax, S1-S4, H1-H4, I1-I5).
 *
 * H1-H4 are stored as raw strings to support both fixed values ("N") and
 * range format ("N-M") as defined by the official amneziawg-go UAPI.
 * I1-I5 are obfuscation chain spec strings (e.g. "<r 8><d>").
 */
data class AwgConfig(
    // [Interface]
    val privateKey: String,
    val address: String,
    val dns: String? = null,
    val mtu: Int? = null,
    // AmneziaWG junk params
    val jc: Int? = null,
    val jmin: Int? = null,
    val jmax: Int? = null,
    // AmneziaWG padding params (s1=init, s2=response, s3=cookie, s4=transport)
    val s1: Int? = null,
    val s2: Int? = null,
    val s3: Int? = null,
    val s4: Int? = null,
    // AmneziaWG magic header params (raw string: "N" or "N-M")
    val h1: String? = null,
    val h2: String? = null,
    val h3: String? = null,
    val h4: String? = null,
    // AmneziaWG initialization obfuscation chains (I1-I5)
    val i1: String? = null,
    val i2: String? = null,
    val i3: String? = null,
    val i4: String? = null,
    val i5: String? = null,
    // [Peer]
    val publicKey: String,
    val presharedKey: String? = null,
    val endpoint: String,
    val allowedIPs: String = "0.0.0.0/0, ::/0",
    val persistentKeepalive: Int? = null,
) {
    /** True when the config contains at least one AmneziaWG-specific field. */
    val isAwg: Boolean get() = jc != null || s1 != null || h1 != null || i1 != null
}

class AwgConfigParseException(message: String) : Exception(message)

/**
 * Ensure an IPv6 endpoint is bracketed as required by netip.ParseAddrPort / UAPI.
 *   "[200:...]:port"       → unchanged
 *   "200:...:port"         → "[200:...]:port"
 *   "1.2.3.4:port"         → unchanged
 */
private fun normalizeEndpoint(raw: String): String {
    if (raw.startsWith("[")) return raw
    val lastColon = raw.lastIndexOf(':')
    if (lastColon < 0) return raw
    val host = raw.substring(0, lastColon)
    val port = raw.substring(lastColon + 1)
    return if (':' in host) "[$host]:$port" else raw
}

/**
 * Parse a WireGuard/AmneziaWG .conf file into [AwgConfig].
 * Handles multiple [Peer] sections; only the first peer is used.
 */
fun parseAwgConf(text: String): AwgConfig {
    val map = mutableMapOf<String, String>()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith('#') || trimmed.startsWith('[') || trimmed.isEmpty()) continue
        val idx = trimmed.indexOf('=')
        if (idx <= 0) continue
        val key = trimmed.substring(0, idx).trim()
        val value = trimmed.substring(idx + 1).trim()
        // First occurrence wins (handles multiple [Peer] sections gracefully)
        map.putIfAbsent(key, value)
    }

    return AwgConfig(
        privateKey          = map["PrivateKey"] ?: throw AwgConfigParseException("PrivateKey missing"),
        address             = map["Address"]    ?: throw AwgConfigParseException("Address missing"),
        dns                 = map["DNS"],
        mtu                 = map["MTU"]?.toIntOrNull(),
        jc                  = map["Jc"]?.toIntOrNull(),
        jmin                = map["Jmin"]?.toIntOrNull(),
        jmax                = map["Jmax"]?.toIntOrNull(),
        s1                  = map["S1"]?.toIntOrNull(),
        s2                  = map["S2"]?.toIntOrNull(),
        s3                  = map["S3"]?.toIntOrNull(),
        s4                  = map["S4"]?.toIntOrNull(),
        h1                  = map["H1"],
        h2                  = map["H2"],
        h3                  = map["H3"],
        h4                  = map["H4"],
        i1                  = map["I1"],
        i2                  = map["I2"],
        i3                  = map["I3"],
        i4                  = map["I4"],
        i5                  = map["I5"],
        publicKey           = map["PublicKey"]  ?: throw AwgConfigParseException("PublicKey missing"),
        presharedKey        = map["PresharedKey"],
        endpoint            = normalizeEndpoint(map["Endpoint"] ?: throw AwgConfigParseException("Endpoint missing")),
        allowedIPs          = map["AllowedIPs"] ?: "0.0.0.0/0, ::/0",
        persistentKeepalive = map["PersistentKeepalive"]?.toIntOrNull(),
    )
}

/** Serialise config back to wg-quick .conf format. */
fun AwgConfig.toConfString(): String = buildString {
    appendLine("[Interface]")
    appendLine("PrivateKey = $privateKey")
    appendLine("Address = $address")
    dns?.let { appendLine("DNS = $it") }
    mtu?.let { appendLine("MTU = $it") }
    jc?.let   { appendLine("Jc = $it") }
    jmin?.let { appendLine("Jmin = $it") }
    jmax?.let { appendLine("Jmax = $it") }
    s1?.let   { appendLine("S1 = $it") }
    s2?.let   { appendLine("S2 = $it") }
    s3?.let   { appendLine("S3 = $it") }
    s4?.let   { appendLine("S4 = $it") }
    h1?.let   { appendLine("H1 = $it") }
    h2?.let   { appendLine("H2 = $it") }
    h3?.let   { appendLine("H3 = $it") }
    h4?.let   { appendLine("H4 = $it") }
    i1?.let   { appendLine("I1 = $it") }
    i2?.let   { appendLine("I2 = $it") }
    i3?.let   { appendLine("I3 = $it") }
    i4?.let   { appendLine("I4 = $it") }
    i5?.let   { appendLine("I5 = $it") }
    appendLine()
    appendLine("[Peer]")
    appendLine("PublicKey = $publicKey")
    presharedKey?.let { appendLine("PresharedKey = $it") }
    appendLine("Endpoint = $endpoint")
    appendLine("AllowedIPs = $allowedIPs")
    persistentKeepalive?.let { appendLine("PersistentKeepalive = $it") }
}
