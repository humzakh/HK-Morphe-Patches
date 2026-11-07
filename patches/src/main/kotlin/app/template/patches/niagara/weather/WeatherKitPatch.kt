package app.template.patches.niagara.weather

import app.template.patches.niagara.NiagaraLauncherCompatible

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Returns a .p8 key's base64 body. Fails patching if invalid.
 */
private fun readPrivateKey(pem: String): String {
    val base64 = pem
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace(Regex("\\s"), "")

    try {
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)))
    } catch (e: Exception) {
        throw PatchException("The private key isn't a valid WeatherKit key", e)
    }
    return base64
}

@Suppress("unused")
val weatherKitPatch = bytecodePatch(
    name = "Apple WeatherKit",
    description = "Fetches weather from Apple WeatherKit in place of Niagara's weather service. Requires a WeatherKit key.",
    default = false
) {
    dependsOn(niagaraWeatherPatch)
    compatibleWith(NiagaraLauncherCompatible)

    val teamIdOption = stringOption(
        "team-id",
        null,
        null,
        "Team ID",
        "The Team ID of your Apple Developer account.",
        true,
        validator = { value -> value != null && value.matches(Regex("^[A-Z0-9]{10}$")) }
    )
    val keyIdOption = stringOption(
        "key-id",
        null,
        null,
        "Key ID",
        "The ID of a key with WeatherKit enabled.",
        true,
        validator = { value -> value != null && value.matches(Regex("^[A-Z0-9]{10}$")) }
    )
    val servicesIdOption = stringOption(
        "services-id",
        null,
        null,
        "Services ID",
        "The identifier of a Services ID registered for WeatherKit, e.g. com.example.weatherkit.",
        true,
        validator = { value -> value != null && value.matches(Regex("^[A-Za-z0-9.-]+$")) }
    )
    val privateKeyOption = stringOption(
        "private-key",
        null,
        null,
        "Private key",
        "The contents of your WeatherKit private key.",
        true,
        validator = { value -> !value.isNullOrBlank() }
    )

    execute {
        mapOf(
            teamIdFingerprint to teamIdOption.value!!,
            keyIdFingerprint to keyIdOption.value!!,
            servicesIdFingerprint to servicesIdOption.value!!,
            privateKeyFingerprint to readPrivateKey(privateKeyOption.value!!),
        ).forEach { (fingerprint, value) ->
            fingerprint.method.addInstructions(
                0,
                """
                    const-string v0, "$value"
                    return-object v0
                """
            )
        }

        setWeatherSource(WEATHERKIT_CLASS_DESCRIPTOR)
    }
}
