package app.template.patches.reddit.customclients.sync.syncforreddit.fix.ultra

import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.string.replaceStringPatch

val syncUltraPatch = bytecodePatch(
    name = "Unlock Sync Ultra",
    description = "Unlocks Sync Ultra and fixes the \"Restore Comment\" premium feature by fetching from an alternative API.",
) {
    compatibleWith(*SyncForRedditCompatible)

    execute {
        // return true when when checking for Sync Ultra subscription
        UltraHelperFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """
        )

        // return Sync Ultra Lifetime when checking for subscription type
        SyncUltraLifetimeFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "Sync Ultra Lifetime"
                return-object v0
            """
        )
    }

    dependsOn(
        // patch the Restore Comment feature to fetch from Arctic Shift
        replaceStringPatch(
            "https://api.pushshift.io/reddit/comment/search/",
            "https://arctic-shift.photon-reddit.com/api/comments/ids",
            comparison = StringComparisonType.EQUALS
        )
    )
}