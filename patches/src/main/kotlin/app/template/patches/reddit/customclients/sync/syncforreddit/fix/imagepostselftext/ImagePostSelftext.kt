package app.template.patches.reddit.customclients.sync.syncforreddit.fix.imagepostselftext

import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/syncforreddit/ImagePostSelftextExtension;"

val imagePostSelftextPatch = bytecodePatch(
    name = "Fix Image Post Selftext",
    description = "Fixes selftext in image posts to properly render Markdown.",
    default = true,
) {
    extendWith("extensions/syncforreddit.mpe")
    compatibleWith(*SyncForRedditCompatible)

    execute {
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
                    replaceInstruction(cIndex, "invoke-static {p0, p1}, $EXTENSION_CLASS_DESCRIPTOR->getOptions(Landroid/widget/TextView;Lxa/d;)Lnc/a;")
                }
            }
        }
    }
}
