package dev.jacobandersen.bastion.websub.internal.subscriber

import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies the `X-Hub-Signature` header of a WebSub content delivery
 * (`method=hexdigest`, an HMAC of the raw body keyed with the subscription
 * secret). The spec allows sha1, sha256, sha384 and sha512; many hubs still
 * send sha1, so all four are accepted.
 */
object HubSignature {
    private val ALGORITHMS =
        mapOf(
            "sha1" to "HmacSHA1",
            "sha256" to "HmacSHA256",
            "sha384" to "HmacSHA384",
            "sha512" to "HmacSHA512",
        )

    fun isValid(
        header: String?,
        secret: String,
        body: ByteArray,
    ): Boolean {
        val (method, hex) = header?.trim()?.split('=', limit = 2)?.takeIf { it.size == 2 } ?: return false
        val algorithm = ALGORITHMS[method.lowercase()] ?: return false
        val provided = runCatching { HexFormat.of().parseHex(hex.trim()) }.getOrNull() ?: return false
        return MessageDigest.isEqual(provided, sign(algorithm, secret, body))
    }

    internal fun sign(
        algorithm: String,
        secret: String,
        body: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), algorithm))
        return mac.doFinal(body)
    }
}
