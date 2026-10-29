package app.template.patches.niagara.weather

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val FORECAST_CLASS_DESCRIPTOR = "Lapp/morphe/extension/niagara/NiagaraWeather\$Forecast;"

private const val SEND_REQUEST_METHOD_NAME = "patch_sendWeatherRequest"

private val LAUNCH_PARAMETERS = listOf("Landroid/content/Intent;", "Landroid/content/Context;", "Landroid/view/View;")

private fun MethodReference.smali() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Instruction.stringOrNull() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

/**
 * Answers Niagara's weather server request from the NiagaraWeather extension, and makes the forecast's
 * long press ask which app to open. Source patches depend on this and call [setWeatherSource].
 */
internal val niagaraWeatherPatch = bytecodePatch {
    extendWith("extensions/niagara.mpe")

    execute {
        // After the weather URL is built, the first call taking the request sends it and returns the
        // response body. Everything else, from parsing to caching, is left to Niagara.
        val requestType = requestToStringFingerprint.classDef.type

        fetchFromServerFingerprint.apply {
            val urlIndex = method.instructions.indexOfFirst { it.stringOrNull() == "weather2" }
            val sendIndex = method.instructions.withIndex().first { (index, instruction) ->
                index > urlIndex && ((instruction as? ReferenceInstruction)?.reference as? MethodReference)
                    ?.parameterTypes?.any { it.toString() == requestType } == true
            }.index
            val send = method.getInstruction<ReferenceInstruction>(sendIndex).reference as MethodReference

            if (send.returnType != "Ljava/lang/Object;" && send.returnType != "Ljava/lang/String;") {
                throw PatchException("Niagara's weather request returns ${send.returnType}, not its body")
            }

            // A receiver becomes the copy's first parameter.
            val (invoke, receiver) = when (method.getInstruction(sendIndex).opcode) {
                Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> "invoke-static/range" to emptyList()
                Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> "invoke-virtual/range" to listOf(send.definingClass)
                Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE -> "invoke-interface/range" to listOf(send.definingClass)
                else -> throw PatchException("Niagara's weather request is sent by an unsupported call")
            }
            val parameters = receiver + send.parameterTypes.map { it.toString() }
            val parameterSizes = parameters.map { if (it == "J" || it == "D") 2 else 1 }
            val requestRegister = parameterSizes.take(parameters.indexOf(requestType)).sum()

            // The copy answers from the extension, or sends the request if it has no answer.
            val sendRequest = ImmutableMethod(
                classDef.type,
                SEND_REQUEST_METHOD_NAME,
                parameters.map { ImmutableMethodParameter(it, null, null) },
                send.returnType,
                AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(parameterSizes.sum() + 1)
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { p$requestRegister }, $NIAGARA_WEATHER_CLASS_DESCRIPTOR->fetchResponse(Ljava/lang/Object;)Ljava/lang/String;
                        move-result-object v0
                        if-eqz v0, :send
                        return-object v0

                        :send
                        $invoke { p0 .. p${parameterSizes.sum() - 1} }, ${send.smali()}
                        move-result-object v0
                        return-object v0
                    """
                )
            }
            classDef.methods.add(sendRequest)

            // Same registers, as the copy's parameters are the call's.
            val call = method.getInstruction<Instruction>(sendIndex)
            val sendFromCopy = if (call is RegisterRangeInstruction) {
                "invoke-static/range { v${call.startRegister} .. v${call.startRegister + call.registerCount - 1} }"
            } else {
                (call as FiveRegisterInstruction).let {
                    val registers = listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG)
                        .take(it.registerCount)
                    "invoke-static { ${registers.joinToString { register -> "v$register" }} }"
                }
            }
            method.replaceInstruction(
                sendIndex,
                "$sendFromCopy, ${classDef.type}->$SEND_REQUEST_METHOD_NAME(${parameters.joinToString("")})${send.returnType}"
            )
        }

        // Swap the long press's OpenWeather launch for the app picker, which takes the same arguments.
        openWeatherAppFingerprint.method.apply {
            val intentIndex = instructions.indexOfFirst {
                it.stringOrNull() == "uk.co.openweather.MainActivity"
            }
            val launchIndex = instructions.withIndex().first { (index, instruction) ->
                index > intentIndex && instruction.opcode == Opcode.INVOKE_STATIC &&
                    ((instruction as ReferenceInstruction).reference as MethodReference)
                        .parameterTypes.map { it.toString() } == LAUNCH_PARAMETERS
            }.index

            val launch = getInstruction<FiveRegisterInstruction>(launchIndex)
            replaceInstruction(
                launchIndex,
                "invoke-static { v${launch.registerC}, v${launch.registerD}, v${launch.registerE} }, " +
                    "$WEATHER_APP_CLASS_DESCRIPTOR->open(${LAUNCH_PARAMETERS.joinToString("")})V"
            )
        }
    }
}

/**
 * Sets the weather source: a class with `static NiagaraWeather.Forecast fetch(double, double)`.
 * Fails if a source is already set.
 */
context(_: BytecodePatchContext)
internal fun setWeatherSource(sourceClass: String) {
    extensionFetchFromSourceFingerprint.method.apply {
        // Unfilled, it's just `return null`.
        if (instructions.first().opcode != Opcode.CONST_4) {
            throw PatchException("Only one Niagara weather source patch can be applied")
        }

        addInstructions(
            0,
            """
                invoke-static { p0, p1, p2, p3 }, $sourceClass->fetch(DD)$FORECAST_CLASS_DESCRIPTOR
                move-result-object v0
                return-object v0
            """
        )
    }
}
