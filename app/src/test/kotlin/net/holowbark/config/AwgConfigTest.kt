package net.holowbark.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgConfigTest {

    // Placeholders, not keys: the parser stores these verbatim and never decodes
    // them, so a real base64 key would only be a secret sitting in the repository.
    private val privatePlaceholder = "A".repeat(43) + "="
    private val publicPlaceholder = "B".repeat(43) + "="

    private val minimal = """
        [Interface]
        PrivateKey = $privatePlaceholder
        Address = 10.100.0.2/24

        [Peer]
        PublicKey = $publicPlaceholder
        Endpoint = [200:4825:fd69:6d41:5475:a08a:8885:9542]:51820
        AllowedIPs = 0.0.0.0/0, ::/0
    """.trimIndent()

    @Test
    fun parse_plainWireGuardConf_isNotAwg() {
        val config = parseAwgConf(minimal)
        assertFalse(config.isAwg)
        assertEquals("WireGuard", config.protocolName)
        assertEquals("10.100.0.2/24", config.address)
        assertNull(config.dns)
    }

    @Test
    fun parse_confWithObfuscationParams_isAwg() {
        val config = parseAwgConf(minimal.replace("[Peer]", """
            Jc = 4
            Jmin = 40
            S1 = 15
            H1 = 1148771881
            I1 = <r 8><d>

            [Peer]
        """.trimIndent()))
        assertTrue(config.isAwg)
        assertEquals("AmneziaWG", config.protocolName)
        assertEquals(4, config.jc)
        assertEquals(40, config.jmin)
        assertEquals(15, config.s1)
        assertEquals("1148771881", config.h1)
        assertEquals("<r 8><d>", config.i1)
    }

    @Test
    fun parse_magicHeaderRange_isKeptAsWritten() {
        // The UAPI accepts "N" or "N-M", so H1..H4 must not be coerced to an Int.
        val config = parseAwgConf(minimal.replace("[Peer]", "H1 = 5-10\n\n[Peer]"))
        assertEquals("5-10", config.h1)
    }

    @Test
    fun parse_unbracketedIpv6Endpoint_isBracketed() {
        val config = parseAwgConf(
            minimal.replace("[200:4825:fd69:6d41:5475:a08a:8885:9542]:51820",
                "200:4825:fd69:6d41:5475:a08a:8885:9542:51820"))
        assertEquals("[200:4825:fd69:6d41:5475:a08a:8885:9542]:51820", config.endpoint)
    }

    @Test
    fun parse_ipv4Endpoint_isLeftAlone() {
        val config = parseAwgConf(
            minimal.replace("[200:4825:fd69:6d41:5475:a08a:8885:9542]:51820", "1.2.3.4:51820"))
        assertEquals("1.2.3.4:51820", config.endpoint)
    }

    @Test
    fun parse_multiplePeerSections_usesTheFirst() {
        val config = parseAwgConf(minimal + """

            [Peer]
            PublicKey = ${"C".repeat(43)}=
            Endpoint = 9.9.9.9:9999
        """.trimIndent())
        assertEquals("[200:4825:fd69:6d41:5475:a08a:8885:9542]:51820", config.endpoint)
    }

    @Test
    fun parse_commentsAndBlankLines_areSkipped() {
        val config = parseAwgConf("# a comment\n\n$minimal\n# trailing\n")
        assertEquals("10.100.0.2/24", config.address)
    }

    @Test
    fun parse_missingRequiredField_throws() {
        listOf("PrivateKey", "Address", "PublicKey", "Endpoint").forEach { field ->
            val without = minimal.lines().filterNot { it.startsWith(field) }.joinToString("\n")
            try {
                parseAwgConf(without)
                throw AssertionError("expected a parse failure without $field")
            } catch (e: AwgConfigParseException) {
                assertTrue(e.message.orEmpty().contains(field))
            }
        }
    }

    @Test
    fun toConfString_roundTripsThroughTheParser() {
        val original = parseAwgConf(minimal.replace("[Peer]", """
            DNS = 1.1.1.1
            MTU = 1280
            Jc = 4
            S2 = 20
            H3 = 5-10
            I2 = <b 0xf0>

            [Peer]
        """.trimIndent()) + "\nPersistentKeepalive = 25")
        assertEquals(original, parseAwgConf(original.toConfString()))
    }
}
