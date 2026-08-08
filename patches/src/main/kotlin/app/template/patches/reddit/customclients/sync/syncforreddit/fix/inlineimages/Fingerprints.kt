package app.template.patches.reddit.customclients.sync.syncforreddit.fix.inlineimages

import app.morphe.patcher.Fingerprint

internal val htmlLinkNodeFingerprint = Fingerprint(
    returnType = "V",
    strings = listOf("preview.redd.it", "translate", "sync-settings")
)
