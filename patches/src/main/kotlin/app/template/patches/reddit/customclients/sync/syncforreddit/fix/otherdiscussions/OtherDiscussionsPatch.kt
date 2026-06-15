package app.template.patches.reddit.customclients.sync.syncforreddit.fix.otherdiscussions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

@Suppress("unused")
val otherDiscussionsPatch = bytecodePatch(
    name = "Fix \"Other Discussions\"",
    description = "Fixes the \"Other Discussions\" feature.",
    default = true,
) {
    compatibleWith(*SyncForRedditCompatible)

    execute {
        otherDiscussionsBuildUrlFingerprint.method.apply {
            val submitMatch = otherDiscussionsBuildUrlFingerprint.stringMatches.firstOrNull {
                val instr = getInstruction<ReferenceInstruction>(it.index)
                val ref = instr.reference as? StringReference
                ref?.string == "submit.json?url="
            }

            if (submitMatch != null) {
                val instr = getInstruction<ReferenceInstruction>(submitMatch.index)
                val targetRegister = (instr as OneRegisterInstruction).registerA
                replaceInstruction(submitMatch.index, "const-string v${targetRegister}, \"api/info?url=\"")
            }

            val instructions = this.implementation?.instructions?.toList() ?: emptyList()
            val sslIndex = instructions.indexOfFirst {
                (it as? ReferenceInstruction)?.reference?.let { ref ->
                    ref is StringReference && (ref.string == "https://ssl.reddit.com/" || ref.string == "https://www.reddit.com/")
                } == true
            }

            if (sslIndex != -1) {
                val sslInstr = getInstruction<ReferenceInstruction>(sslIndex)
                val targetRegister = (sslInstr as OneRegisterInstruction).registerA
                replaceInstruction(sslIndex, "const-string v${targetRegister}, \"https://oauth.reddit.com/\"")
            }
        }
    }
}
