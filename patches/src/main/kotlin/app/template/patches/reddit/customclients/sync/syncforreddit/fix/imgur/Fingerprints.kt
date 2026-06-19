package app.template.patches.reddit.customclients.sync.syncforreddit.fix.imgur

import app.morphe.patcher.Fingerprint

internal val imgurGalleryUrlStaticFingerprint = Fingerprint(
    classFingerprint = Fingerprint(strings = listOf("Title: ", "images")),
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;")
)

internal val imgurGalleryRequestHeadersFingerprint = Fingerprint(
    classFingerprint = Fingerprint(strings = listOf("Title: ", "images")),
    strings = listOf("User-Agent", "sync_for_reddit")
)

internal val imgurImageRequestClassFingerprint = Fingerprint(
    strings = listOf("sync_for_reddit", "position", "link", "description", " - ", "null")
)

internal val imgurImageUrlStaticFingerprint = Fingerprint(
    classFingerprint = imgurImageRequestClassFingerprint,
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;")
)

internal val imgurImageRequestHeadersFingerprint = Fingerprint(
    classFingerprint = imgurImageRequestClassFingerprint,
    strings = listOf("User-Agent", "sync_for_reddit")
)
