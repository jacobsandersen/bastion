package dev.jacobandersen.bastion.webmention.internal.data.domain

/**
 * The outcome of recording a received webmention's verification result.
 * [changed] is true when the responses the target displays changed, which is
 * what triggers a Salmention resend.
 */
data class ReceivedWebmentionUpdate(
    val webmention: ReceivedWebmention,
    val changed: Boolean,
)
