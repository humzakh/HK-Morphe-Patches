package app.template.patches.reddit.customclients.sync.syncforreddit.fix.ultra

import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal val UltraHelperFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("UltraHelper")
)

internal val SyncUltraLifetimeFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("Sync Ultra Monthly", "Sync Ultra Yearly", "Sync Ultra lifetime")
)