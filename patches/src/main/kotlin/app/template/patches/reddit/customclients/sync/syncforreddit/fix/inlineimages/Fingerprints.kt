package app.template.patches.reddit.customclients.sync.syncforreddit.fix.inlineimages

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val htmlLinkNodeFingerprint = Fingerprint(
    returnType = "V",
    strings = listOf("preview.redd.it", "translate", "sync-settings")
)

internal val singleImageActivityBuildFragmentFingerprint = Fingerprint(
    definingClass = "Lcom/laurencedawson/reddit_sync/ui/activities/media/SingleImageActivity;",
    returnType = "V",
    custom = { method, _ ->
        method.implementation?.instructions?.any { instr ->
            (instr.opcode == Opcode.INVOKE_STATIC || instr.opcode == Opcode.INVOKE_STATIC_RANGE) &&
                (instr as ReferenceInstruction).reference.let { ref ->
                    ref is MethodReference && ref.name == "n4"
                }
        } == true
    }
)
