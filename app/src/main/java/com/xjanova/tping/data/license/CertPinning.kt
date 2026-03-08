package com.xjanova.tping.data.license

import okhttp3.CertificatePinner

/**
 * Shared certificate pinning configuration for xman4289.com.
 *
 * All HTTP clients that talk to xman4289.com MUST use [pinner].
 * UpdateChecker (api.github.com) does NOT use this.
 *
 * Pins are SHA-256 SPKI hashes (base64-encoded).
 * When the server certificate rotates, update [PIN_LEAF].
 * The [PIN_INTERMEDIATE] backup ensures continuity during rotation.
 */
object CertPinning {

    // Primary: leaf certificate public key pin
    private const val PIN_LEAF = "sha256/GohdTsJN/x3Y5tkwi1U6Qtxe/OovLV8T0XyiUXlPS4E="

    // Backup: intermediate CA public key pin (for cert rotation)
    private const val PIN_INTERMEDIATE = "sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4="

    val pinner: CertificatePinner = CertificatePinner.Builder()
        .add("xman4289.com", PIN_LEAF)
        .add("xman4289.com", PIN_INTERMEDIATE)
        .build()
}
