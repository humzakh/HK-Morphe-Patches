package app.template.patches.reddit.customclients.sync.syncforreddit.fix.otherdiscussions

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal val otherDiscussionsBuildUrlFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    strings = listOf("submit.json?url="),
    custom = { _, classDef -> classDef.type == "Ly8/k0;" }
)
