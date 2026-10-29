package app.template.patches.niagara.weather

import app.morphe.patcher.Fingerprint

internal const val NIAGARA_WEATHER_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/niagara/NiagaraWeather;"

internal const val WEATHERKIT_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/niagara/WeatherKitExtension;"

internal const val WEATHER_APP_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/niagara/WeatherAppExtension;"

/**
 * Builds and sends the request to Niagara's weather server. The query parameters are the server's API,
 * so they outlive changes to the code around them.
 */
internal val fetchFromServerFingerprint = Fingerprint(
    strings = listOf("weather2", "lat", "long", "lang")
)

/**
 * OkHttp's `Request.toString()`. Its class is the request.
 */
internal val requestToStringFingerprint = Fingerprint(
    name = "toString",
    strings = listOf("Request{method=")
)

/**
 * The forecast's long press, which opens OpenWeather.
 */
internal val openWeatherAppFingerprint = Fingerprint(
    strings = listOf("uk.co.openweather.MainActivity")
)

private fun extensionMethodFingerprint(name: String) = Fingerprint(
    name = name,
    custom = { _, classDef -> classDef.type == NIAGARA_WEATHER_CLASS_DESCRIPTOR }
)

// NiagaraWeather placeholder.
internal val extensionFetchFromSourceFingerprint = extensionMethodFingerprint("fetchFromSource")

private fun credentialFingerprint(name: String) = Fingerprint(
    name = name,
    custom = { _, classDef -> classDef.type == WEATHERKIT_CLASS_DESCRIPTOR }
)

// WeatherKitExtension credential placeholders.
internal val teamIdFingerprint = credentialFingerprint("teamId")
internal val keyIdFingerprint = credentialFingerprint("keyId")
internal val servicesIdFingerprint = credentialFingerprint("servicesId")
internal val privateKeyFingerprint = credentialFingerprint("privateKeyBase64")
