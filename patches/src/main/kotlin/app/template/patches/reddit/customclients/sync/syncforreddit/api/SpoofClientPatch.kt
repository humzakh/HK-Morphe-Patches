package app.template.patches.reddit.customclients.sync.syncforreddit.api

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.StringComparisonType
import app.morphe.patches.all.misc.string.replaceStringPatch
import app.template.patches.reddit.customclients.spoofClientPatch
import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.Base64

@Suppress("unused")
val spoofClientPatch = spoofClientPatch { clientIdOption, redirectUriOption, userAgentOption ->
    dependsOn(
        // Redirects from SSL to WWW domain are bugged causing auth problems.
        // Manually rewrite the URLs to fix this.
        replaceStringPatch("ssl.reddit.com", "www.reddit.com", comparison = StringComparisonType.CONTAINS)
    )

    compatibleWith(*SyncForRedditCompatible)

    execute {
        val clientId = clientIdOption.value!!
        val redirectUri = redirectUriOption.value!!
        val userAgent = userAgentOption.value!!

        // region Patch client id.
        getBearerTokenFingerprint.method.apply {
            val auth = Base64.getEncoder().encodeToString("$clientId:".toByteArray(Charsets.UTF_8))
            addInstructions(0, """
                const-string v0, "Basic $auth"
                return-object v0
            """)

            val occurrenceIndex =
                getAuthorizationStringFingerprint.stringMatches.first().index

            getAuthorizationStringFingerprint.method.apply {
                val authorizationStringInstruction = getInstruction<ReferenceInstruction>(occurrenceIndex)
                val targetRegister = (authorizationStringInstruction as OneRegisterInstruction).registerA
                val reference = authorizationStringInstruction.reference as StringReference

                val newAuthorizationUrl = reference.string.replace(
                    "client_id=.*?&".toRegex(),
                    "client_id=$clientId&",
                )

                replaceInstruction(
                    occurrenceIndex,
                    "const-string v$targetRegister, \"$newAuthorizationUrl\"",
                )
            }
        }
        // endregion

        // region Patch redirect URI.
        getRedirectUriFingerprint.method.addInstructions(0, """
            const-string v0, "$redirectUri"
            return-object v0
        """)
        // endregion

        // region Patch user agent.
        getUserAgentFingerprint.method.addInstructions(0, """
            const-string v0, "$userAgent"
            return-object v0
        """)
        // endregion

        // region Patch Imgur API URL.

        imgurImageAPIFingerprint.let {
            val apiUrlIndex = it.stringMatches.first().index
            it.method.replaceInstruction(
                apiUrlIndex,
                "const-string v1, \"https://api.imgur.com/3/image\"",
            )
        }

        // endregion
    }
}
