package app.template.patches.reddit.customclients.sync.syncforreddit.fix.gestures

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

val ConvertToOpaqueFingerprint = Fingerprint(
    custom = { methodDef, classDef ->
        classDef.type == "Lwc/n;" && methodDef.name == "a"
    }
)

val ConvertToTranslucentFingerprint = Fingerprint(
    custom = { methodDef, classDef ->
        classDef.type == "Lwc/n;" && methodDef.name == "c"
    }
)

@Suppress("unused")
val swipeToReturnBytecodePatch = bytecodePatch(
    name = "Experimental: Restore \"Swipe to return\" translucency",
    description = "Fixes the opaque background when using the \"Swipe to return\" option. Experimental due to animation issues. Incompatible with the Predictive Back Gesture patch.",
    default = false,
) {
    compatibleWith(*SyncForRedditCompatible)

    execute {
        ConvertToOpaqueFingerprint.method.apply {
            // NOP this because it throws an exception on modern Android if used improperly
            addInstructions(0, "return-void")
        }

        ConvertToTranslucentFingerprint.method.apply {
            // Call Java extension which waits 100ms for the default system animation 
            // to finish before setting the window to translucent.
            addInstructions(
                0,
                """
                invoke-static {p0}, Lapp/morphe/extension/syncforreddit/SwipeToReturnExtension;->convertToTranslucentDelayed(Landroid/app/Activity;)V
                return-void
                """.trimIndent()
            )
        }
    }
}
