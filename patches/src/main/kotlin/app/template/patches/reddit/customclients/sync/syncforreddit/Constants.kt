package app.template.patches.reddit.customclients.sync.syncforreddit

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal val SyncForRedditFreeCompatible = Compatibility(
    name = "Sync for Reddit",
    packageName = "com.laurencedawson.reddit_sync",
    targets = listOf(AppTarget(version = "v23.06.30-13:39"))
)

internal val SyncForRedditProCompatible = Compatibility(
    name = "Sync for Reddit Pro",
    packageName = "com.laurencedawson.reddit_sync.pro",
    targets = listOf(AppTarget(version = "v23.06.30-13:39"))
)

internal val SyncForRedditDevCompatible = Compatibility(
    name = "Sync for Reddit Dev",
    packageName = "com.laurencedawson.reddit_sync.dev",
    targets = listOf(AppTarget(version = "v23.06.30-13:39"))
)

internal val SyncForRedditCompatible = arrayOf(
    SyncForRedditFreeCompatible,
    SyncForRedditProCompatible,
    SyncForRedditDevCompatible
)

internal val SyncForLemmyCompatible = Compatibility(
    name = "Sync for Lemmy",
    packageName = "io.syncapps.lemmy_sync",
    targets = listOf(AppTarget(version = null))
)
