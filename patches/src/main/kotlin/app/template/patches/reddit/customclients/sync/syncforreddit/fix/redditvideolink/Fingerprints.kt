package app.template.patches.reddit.customclients.sync.syncforreddit.fix.redditvideolink

import app.morphe.patcher.Fingerprint

internal val linkHandlerOpenLinkFingerprint = Fingerprint(
    strings = listOf("Opening strange reddit video link", "Opening link: ")
)


