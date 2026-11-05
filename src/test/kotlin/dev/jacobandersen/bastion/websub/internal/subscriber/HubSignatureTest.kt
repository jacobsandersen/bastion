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
