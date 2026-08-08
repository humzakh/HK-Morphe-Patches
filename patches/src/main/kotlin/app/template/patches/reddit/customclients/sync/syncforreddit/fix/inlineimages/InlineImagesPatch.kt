package app.template.patches.reddit.customclients.sync.syncforreddit.fix.inlineimages

import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction

private const val MAX_INLINE_IMAGE_DIMENSION_PX = 8192
private val ASPECT_RATIO_THRESHOLD_ORIGINAL_BITS = 0.2f.toRawBits()
private val ASPECT_RATIO_THRESHOLD_NEW_BITS = 0.05f.toRawBits()

val inlineImagesPatch = bytecodePatch(
    name = "Fix inline images",
    description = "Fixes images in text posts showing as plain links instead of rendering inline.",
    default = true
) {
    compatibleWith(*SyncForRedditCompatible)

    execute {
        htmlLinkNodeFingerprint.method.apply {
            val instructions = this.implementation?.instructions?.toList() ?: emptyList()

            val dimensionThresholdIndex = instructions.indexOfFirst { instr ->
                (instr as? NarrowLiteralInstruction)?.narrowLiteral == 2000
            }
            check(dimensionThresholdIndex != -1) { "Could not find the 2000px image dimension threshold" }
            replaceInstruction(dimensionThresholdIndex, "const v3, $MAX_INLINE_IMAGE_DIMENSION_PX")

            val aspectRatioThresholdIndex = instructions.indexOfFirst { instr ->
                (instr as? NarrowLiteralInstruction)?.narrowLiteral == ASPECT_RATIO_THRESHOLD_ORIGINAL_BITS
            }
            check(aspectRatioThresholdIndex != -1) { "Could not find the 0.2f aspect ratio threshold" }
            replaceInstruction(aspectRatioThresholdIndex, "const v3, $ASPECT_RATIO_THRESHOLD_NEW_BITS")
        }
    }
}
