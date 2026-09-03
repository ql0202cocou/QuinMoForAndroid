package moe.matsuri.nb4a.utils

// The ECH config wire format is not portable across the three cores:
//   sing-box -> PEM carrying an "ECH CONFIGS" block (common/tls/ech.go pem.Decode)
//   mihomo   -> bare base64 ECHConfigList ("base64 decode ech config string failed")
//   Xray     -> bare base64 ECHConfigList (no PEM path in the shipped binary)
// A profile stores whatever the subscription or the user supplied, so every
// builder converts on the way out instead of assuming one format.

private const val ECH_PEM_HEADER = "-----BEGIN ECH CONFIGS-----"
private const val ECH_PEM_FOOTER = "-----END ECH CONFIGS-----"

// Body of the first HEADER..FOOTER block; anything before the header or
// after the footer is dropped (sing-box rejects trailing bytes, and a second
// pasted block must not leak into the base64 the other cores receive).
// A missing footer leaves everything after the header up to the next header
// as the body.
private fun String.echPemBody(): String =
    substringAfter(ECH_PEM_HEADER, "")
        .substringBefore(ECH_PEM_FOOTER)
        .substringBefore(ECH_PEM_HEADER)

fun String.echAsBase64(): String =
    (if (contains(ECH_PEM_HEADER)) echPemBody() else this).filterNot { it.isWhitespace() }

// sing-box joins the Listable with "\n" before pem.Decode, and rejects any
// trailing bytes, so emit exactly one block and nothing after it.
fun String.echAsPem(): String =
    (listOf(ECH_PEM_HEADER) + echAsBase64().chunked(64) + ECH_PEM_FOOTER).joinToString("\n")
