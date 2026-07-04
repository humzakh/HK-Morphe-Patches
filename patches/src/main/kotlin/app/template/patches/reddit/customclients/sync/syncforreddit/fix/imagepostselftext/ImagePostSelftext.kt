package app.template.patches.reddit.customclients.sync.syncforreddit.fix.imagepostselftext

import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/syncforreddit/ImagePostSelftextExtension;"

val imagePostSelftextPatch = bytecodePatch(
    name = "Fix Image Post Selftext",
    description = "Makes body text in image posts function like regular selftext posts (selectable text, hyperlinked URLs [WIP]).",
    default = true,
) {
    extendWith("extensions/syncforreddit.mpe")
    compatibleWith(*SyncForRedditCompatible)

    execute {
        listOf(
            CardSelftextPreviewTextViewInitFingerprint,
            SimpleSelftextPreviewTextViewInitFingerprint
        ).forEach { fingerprint ->
            fingerprint.method.addInstructions(
                1,
                """
                    invoke-static {p0}, $EXTENSION_CLASS_DESCRIPTOR->applyLongClickListener(Landroid/widget/TextView;)V
                """.trimIndent()
            )
        }

        // Patch the expanded bind methods (K in Card, L in Simple) to use nc/a.b() instead of nc/a.c()
        listOf(
            CardSelftextPreviewTextViewExpandedBindFingerprint,
            SimpleSelftextPreviewTextViewExpandedBindFingerprint
        ).forEach { fingerprint ->
            fingerprint.method.apply {
                val instructions = this.implementation?.instructions?.toList() ?: emptyList()
                val cIndex = instructions.indexOfFirst { instr ->
                    (instr as? ReferenceInstruction)?.reference?.let { ref ->
                        ref is MethodReference && ref.name == "c" && ref.definingClass == "Lnc/a;"
                    } == true
                }
                if (cIndex != -1) {
                    replaceInstruction(cIndex, "invoke-static {}, Lnc/a;->b()Lnc/a;")
                }
            }
        }
    }
}
