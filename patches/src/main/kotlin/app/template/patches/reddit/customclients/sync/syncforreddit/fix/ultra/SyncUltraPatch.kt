package app.template.patches.reddit.customclients.sync.syncforreddit.fix.ultra

import app.template.patches.reddit.customclients.sync.syncforreddit.api.SyncForRedditCompatible

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.string.replaceStringPatch

val syncUltraPatch = bytecodePatch(
    name = "Unlock Sync Ultra"
) {
    compatibleWith(*SyncForRedditCompatible)
    
    execute {
        UltraHelperFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """
        )

        SyncUltraLifetimeFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "Sync Ultra Lifetime"
                return-object v0
            """
        )
    }

    dependsOn(
        replaceStringPatch(
            "https://api.pushshift.io/reddit/comment/search/",
            "https://arctic-shift.photon-reddit.com/api/comments/ids",
            comparison = StringComparisonType.EQUALS
        )
    )
}