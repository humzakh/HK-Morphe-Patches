package app.template.patches.reddit.customclients.sync.syncforreddit.fix.random

import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/syncforreddit/FixRandomSubredditPatch;"

@Suppress("unused")
val randomSubredditPatch = bytecodePatch(
    name = "Fix r/random",
    description = "Restores random subreddit functionality by fetching from an alternative API.",
    default = true,
) {
    extendWith("extensions/syncforreddit.mpe")
    compatibleWith(*SyncForRedditCompatible)

    execute {
        randomRequestBuildUrlFingerprint.method.addInstructions(
            0,
            """
                if-nez p1, :nsfw
                
                const-string v0, "https://api.redditrand.com/reddit-runner/rand?nsfw=0"
                return-object v0
                
                :nsfw
                const-string v0, "https://api.redditrand.com/reddit-runner/rand?nsfw=1"
                return-object v0
            """
        )

        parseRandomNetworkResponseFingerprint.method.addInstructions(
            0,
            """
                # p1 is com.android.volley.NetworkResponse
                iget-object v0, p1, Lcom/android/volley/NetworkResponse;->data:[B
                
                invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->parseRandomResponse([B)Ljava/lang/String;
                move-result-object v0
                
                if-eqz v0, :fallback
                
                const/4 v1, 0x0
                invoke-static { v0, v1 }, Lcom/android/volley/Response;->success(Ljava/lang/Object;Lcom/android/volley/Cache${'$'}Entry;)Lcom/android/volley/Response;
                move-result-object v0
                return-object v0
                
                :fallback
                nop
            """
        )
    }
}
