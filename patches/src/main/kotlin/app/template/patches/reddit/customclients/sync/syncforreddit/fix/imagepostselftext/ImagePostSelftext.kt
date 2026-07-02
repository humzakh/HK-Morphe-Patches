package app.template.patches.reddit.customclients.sync.syncforreddit.fix.imagepostselftext

import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/syncforreddit/ImagePostSelftextExtension;"

val imagePostSelftextPatch = bytecodePatch(
    name = "Fix Image Post Selftext",
    description = "Makes body text in image posts function like regular selftext posts (selectable text, hyperlinked URLs [WIP]).",
    default = true,
) {
    extendWith("extensions/syncforreddit.mpe")
    compatibleWith(*SyncForRedditCompatible)

    execute {
        CardSelftextPreviewTextViewInitFingerprint.method.addInstructions(
            1,
            """
                invoke-static {p0}, $EXTENSION_CLASS_DESCRIPTOR->applyLongClickListener(Landroid/widget/TextView;)V
            """.trimIndent()
        )

        SimpleSelftextPreviewTextViewInitFingerprint.method.addInstructions(
            1,
            """
                invoke-static {p0}, $EXTENSION_CLASS_DESCRIPTOR->applyLongClickListener(Landroid/widget/TextView;)V
            """.trimIndent()
        )
    }
}
