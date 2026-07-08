package app.template.patches.reddit.customclients.sync.syncforreddit.fix.redditvideolink

import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/syncforreddit/RedditVideoLinkExtension;"

val redditVideoLinkPatch = bytecodePatch(
    name = "Fix Reddit Video Links",
    description = "Fixes reddit video links (reddit.com/link/.../video) so they open in the native video player.",
    default = true,
) {
    extendWith("extensions/syncforreddit.mpe")
    compatibleWith(*SyncForRedditCompatible)

    execute {
        linkHandlerOpenLinkFingerprint.method.addInstructions(
            0,
            """
                # p3 holds the URL string in the LinkHelper.openLink method
                invoke-static { p3 }, $EXTENSION_CLASS_DESCRIPTOR->fixVideoLink(Ljava/lang/String;)Ljava/lang/String;
                move-result-object p3
            """
        )
    }
}
