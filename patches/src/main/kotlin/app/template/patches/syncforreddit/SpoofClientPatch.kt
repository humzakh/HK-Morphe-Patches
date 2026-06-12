package app.template.patches.syncforreddit

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.StringComparisonType
import app.morphe.patches.all.misc.string.replaceStringPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.Base64

@Suppress("unused")
val spoofClientPatch = bytecodePatch(
    name = "Spoof client",
    description = "Restores functionality of the app by using custom client ID.",
    default = true,
) {
    dependsOn(
        // Redirects from SSL to WWW domain are bugged causing auth problems.
        // Manually rewrite the URLs to fix this.
        replaceStringPatch("ssl.reddit.com", "www.reddit.com", comparison = StringComparisonType.CONTAINS)
    )

    val clientIdOption = stringOption(
        "client-id",
        "yH0aTnJEt6qUgGn835B4vg",
        null,
        "OAuth client ID",
        "The Reddit OAuth client ID. Refer to documentation for more " +
                "information on what to put here.",
        true,
        validator = { value ->
            if (value.isNullOrBlank()) {
                return@stringOption false
            }
            if (!value.matches(Regex("^[\\w-]{22}$"))) {
                return@stringOption false
            }
            true
        }
    )
    val redirectUriOption = stringOption(
        "redirect-uri",
        "redreader://rr_oauth_redir",
        null,
        "Redirect URI",
        "The Reddit OAuth redirect URI. Refer to documentation for more " +
                "information on what to put here. Default value is RedReader's redirect URI.",
        true,
    )
    val userAgentOption = stringOption(
        "user-agent",
        "org.quantumbadger.redreader/1.25.1",
        null,
        "User agent",
        "The app's user agent. Refer to documentation for more information " +
                "on what to put here. Default value is RedReader's user agent.",
        true
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
