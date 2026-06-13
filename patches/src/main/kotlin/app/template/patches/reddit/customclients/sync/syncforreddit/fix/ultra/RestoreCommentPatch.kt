package app.template.patches.reddit.customclients.sync.syncforreddit.fix.ultra

import app.template.patches.reddit.customclients.sync.syncforreddit.api.SyncForRedditCompatible

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.string.replaceStringPatch

@Suppress("unused")
val restoreCommentPatch = bytecodePatch(
    name = "Fix Restore Comment",
    description = "Fixes the Restore Comment feature (requires Sync Ultra).",
    default = true,
) {
    compatibleWith(*SyncForRedditCompatible)

    dependsOn(
        replaceStringPatch(
            "https://api.pushshift.io/reddit/comment/search/",
            "https://arctic-shift.photon-reddit.com/api/comments/ids",
            comparison = StringComparisonType.EQUALS
        )
    )

    execute {}
}
