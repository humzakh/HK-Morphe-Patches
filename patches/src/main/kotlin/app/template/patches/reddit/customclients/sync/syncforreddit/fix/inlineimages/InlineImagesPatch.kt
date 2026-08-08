package app.template.patches.reddit.customclients.sync.syncforreddit.fix.inlineimages

import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val MAX_INLINE_IMAGE_DIMENSION_PX = 8192
private const val NEW_ASPECT_RATIO_THRESHOLD = 0.05f
private const val CAPTION_TEXT_SIZE = 0.75f
private const val CAPTION_EXTENSION_DESCRIPTOR = "Lapp/morphe/extension/syncforreddit/InlineImageCaptionExtension;"
private const val CLICKABLE_SPAN_DESCRIPTOR = "Lapp/morphe/extension/syncforreddit/InlineImageClickableSpan;"

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
            replaceInstruction(aspectRatioThresholdIndex, "const v3, ${NEW_ASPECT_RATIO_THRESHOLD.toRawBits()}")
        }

        htmlLinkNodeFingerprint.method.apply {
            val aspectRatioThresholdIndex = this.instructions.indexOfFirst { instr ->
                (instr as? NarrowLiteralInstruction)?.narrowLiteral == NEW_ASPECT_RATIO_THRESHOLD.toRawBits()
            }
            check(aspectRatioThresholdIndex != -1) { "Could not find the aspect ratio threshold" }

            val imageSpanConstructorIndex = this.instructions.withIndex().first { (index, instr) ->
                index > aspectRatioThresholdIndex &&
                    (instr as? ReferenceInstruction)?.reference?.let { ref ->
                        ref is TypeReference && ref.type == "Lmb/d;"
                    } == true
            }.index
            val imageSpanRegister =
                (this.getInstruction<ReferenceInstruction>(imageSpanConstructorIndex) as OneRegisterInstruction).registerA

            // swaps the Lmb/d; span for one that opens the image with its caption as the
            // viewer's title. v1 is free here, v11 is left alone for the append logic below.
            removeInstructions(imageSpanConstructorIndex, 2)
            addInstructions(
                imageSpanConstructorIndex,
                """
                    invoke-static {v11}, $CAPTION_EXTENSION_DESCRIPTOR->buildCaption(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v1
                    new-instance v$imageSpanRegister, $CLICKABLE_SPAN_DESCRIPTOR
                    invoke-direct {v$imageSpanRegister, v2, v1}, $CLICKABLE_SPAN_DESCRIPTOR-><init>(Ljava/lang/String;Ljava/lang/String;)V
                """.trimIndent()
            )
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
                        invoke-static {v11}, $CAPTION_EXTENSION_DESCRIPTOR->buildCaption(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v11
                        if-eqz v11, :no_caption

                        const-string v0, "\n"
                        invoke-virtual {p0, v0}, Loc/c;->b(Ljava/lang/CharSequence;)V

                        const/4 v0, 0x2
                        new-array v0, v0, [Ljava/lang/Object;

                        new-instance v1, Landroid/text/style/RelativeSizeSpan;
                        const v5, ${CAPTION_TEXT_SIZE.toRawBits()}
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

        singleImageActivityBuildFragmentFingerprint.method.apply {
            val n4CallInstruction = this.instructions.first { instr ->
                (instr.opcode == Opcode.INVOKE_STATIC || instr.opcode == Opcode.INVOKE_STATIC_RANGE) &&
                    (instr as ReferenceInstruction).reference.let { ref ->
                        ref is MethodReference && ref.name == "n4"
                    }
            } as RegisterRangeInstruction

            // n4's "title" arg (vStart+5) is hardcoded null; replace it with I0()'s
            // url_title value so a caption set via url_title actually renders.
            val titleRegister = n4CallInstruction.startRegister + 5

            val titleArgIndex = this.instructions.indexOfFirst { instr ->
                instr.opcode == Opcode.CONST_4 && (instr as OneRegisterInstruction).registerA == titleRegister
            }
            check(titleArgIndex != -1) { "Could not find the const/4 setting the title register to null" }

            removeInstructions(titleArgIndex, 1)
            addInstructions(
                titleArgIndex,
                """
                    invoke-virtual {p0}, Lcom/laurencedawson/reddit_sync/ui/activities/media/AbstractImageActivity;->I0()Ljava/lang/String;
                    move-result-object v$titleRegister
                """.trimIndent()
            )
        }
    }
}
