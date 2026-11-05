package dev.jacobandersen.bastion.websub.internal.subscriber

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.HexFormat

class HubSignatureTest {
    private val secret = "s3cret"
    private val body = "<feed/>".toByteArray()

    private fun header(
        method: String,
        algorithm: String,
    ) = "$method=" + HexFormat.of().formatHex(HubSignature.sign(algorithm, secret, body))

    private val vectorKey = "key"
    private val vectorBody = "The quick brown fox jumps over the lazy dog".toByteArray()

    @Test
    fun `accepts published HMAC test vectors`() {
        val sha256 = "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"
        val sha1 = "de7c9b85b8b78aa6bc8a7a36f70a90701c9db4d9"

        assertTrue(HubSignature.isValid("sha256=$sha256", vectorKey, vectorBody))
        assertTrue(HubSignature.isValid("sha1=$sha1", vectorKey, vectorBody))
        assertFalse(HubSignature.isValid("sha256=${sha256.dropLast(1)}9", vectorKey, vectorBody))
        assertFalse(HubSignature.isValid("sha1=${sha1.dropLast(1)}8", vectorKey, vectorBody))
        assertFalse(HubSignature.isValid("sha1=$sha256", vectorKey, vectorBody))
    }

    @Test
    fun `accepts every algorithm the spec allows`() {
        assertTrue(HubSignature.isValid(header("sha1", "HmacSHA1"), secret, body))
        assertTrue(HubSignature.isValid(header("sha256", "HmacSHA256"), secret, body))
        assertTrue(HubSignature.isValid(header("sha384", "HmacSHA384"), secret, body))
        assertTrue(HubSignature.isValid(header("sha512", "HmacSHA512"), secret, body))
    }

    @Test
    fun `rejects missing, malformed, unknown and mismatched signatures`() {
        assertFalse(HubSignature.isValid(null, secret, body))
        assertFalse(HubSignature.isValid("sha256", secret, body))
        assertFalse(HubSignature.isValid("sha256=zz", secret, body))
        assertFalse(HubSignature.isValid("md5=" + "00".repeat(16), secret, body))
        assertFalse(HubSignature.isValid(header("sha256", "HmacSHA256"), "other", body))
        assertFalse(HubSignature.isValid(header("sha256", "HmacSHA256"), secret, "tampered".toByteArray()))
    }
}
