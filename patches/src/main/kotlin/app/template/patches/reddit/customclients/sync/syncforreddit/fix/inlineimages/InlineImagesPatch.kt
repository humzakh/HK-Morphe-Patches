package app.template.patches.reddit.customclients.sync.syncforreddit.fix.inlineimages

import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val MAX_INLINE_IMAGE_DIMENSION_PX = 8192
private const val NEW_ASPECT_RATIO_THRESHOLD = 0.05f
private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/syncforreddit/InlineImageCaptionExtension;"

val inlineImagesPatch = bytecodePatch(
    name = "Fix inline images",
    description = "Fixes images in text posts showing as plain links instead of rendering inline. Also adds captions below the images where applicable.",
    default = true
) {
    extendWith("extensions/syncforreddit.mpe")
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
                (instr as? NarrowLiteralInstruction)?.narrowLiteral == 0.2f.toRawBits()
            }
            check(aspectRatioThresholdIndex != -1) { "Could not find the 0.2f aspect ratio threshold" }
            replaceInstruction(aspectRatioThresholdIndex, "const v3, $NEW_ASPECT_RATIO_THRESHOLD.toRawBits()")
        }

        htmlLinkNodeFingerprint.method.apply {
            val heightMarkerIndex = this.instructions.indexOfFirst { instr ->
                (instr as? ReferenceInstruction)?.reference?.let { ref ->
                    ref is StringReference && ref.string == "&height"
                } == true
            }
            check(heightMarkerIndex != -1) { "Could not find the \"&height\" marker" }

            // pack link text + untruncated URL (v2 gets cut at "&height" below) into
            // v11, the only register free for the rest of this method.
            addInstructions(
                heightMarkerIndex,
                """
                    new-instance v11, Ljava/lang/StringBuilder;
                    invoke-direct {v11}, Ljava/lang/StringBuilder;-><init>()V
                    invoke-virtual {v11, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    const-string v10, "\u0001"
                    invoke-virtual {v11, v10}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    invoke-virtual {v11, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    invoke-virtual {v11}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                    move-result-object v11
                """.trimIndent()
            )

            val returnVoidIndex = this.instructions.withIndex().first { (index, instr) ->
                index > heightMarkerIndex + 1 && instr.opcode == Opcode.RETURN_VOID
            }.index

            val returnVoidInstruction = this.getInstruction(returnVoidIndex)

            // 3 sibling branches reach this return-void, 2 via `goto`
            // inserting once here would let those gotos skip right past it, so insert at all 3 origins.
            val insertionPoints = this.instructions.withIndex().mapNotNull { (index, instr) ->
                when {
                    index >= returnVoidIndex -> null
                    (instr.opcode == Opcode.GOTO || instr.opcode == Opcode.GOTO_16 || instr.opcode == Opcode.GOTO_32) &&
                        (instr as BuilderOffsetInstruction).target.location.index == returnVoidIndex -> index
                    else -> null
                }
            } + returnVoidIndex

            insertionPoints.sortedDescending().forEach { insertionIndex ->
                addInstructionsWithLabels(
                    insertionIndex,
                    """
                        invoke-static {v11}, $EXTENSION_CLASS_DESCRIPTOR->buildCaption(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v11
                        if-eqz v11, :no_caption

                        const-string v0, "\n"
                        invoke-virtual {p0, v0}, Loc/c;->b(Ljava/lang/CharSequence;)V

                        const/4 v0, 0x2
                        new-array v0, v0, [Ljava/lang/Object;

                        new-instance v1, Landroid/text/style/RelativeSizeSpan;
                        const/high16 v5, 0x3f400000    # 0.75f
                        invoke-direct {v1, v5}, Landroid/text/style/RelativeSizeSpan;-><init>(F)V
                        const/4 v5, 0x0
                        aput-object v1, v0, v5

                        new-instance v1, Landroid/text/style/AlignmentSpan${'$'}Standard;
                        sget-object v5, Landroid/text/Layout${'$'}Alignment;->ALIGN_CENTER:Landroid/text/Layout${'$'}Alignment;
                        invoke-direct {v1, v5}, Landroid/text/style/AlignmentSpan${'$'}Standard;-><init>(Landroid/text/Layout${'$'}Alignment;)V
                        const/4 v5, 0x1
                        aput-object v1, v0, v5

                        invoke-virtual {p0, v11, v0}, Loc/c;->c(Ljava/lang/String;[Ljava/lang/Object;)V
                    """.trimIndent(),
                    ExternalLabel("no_caption", returnVoidInstruction)
                )
            }
        }
    }
}
