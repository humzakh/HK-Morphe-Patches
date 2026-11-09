package app.template.patches.reddit.customclients.sync.syncforreddit.api

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string

internal val getAuthorizationStringFingerprint = Fingerprint(
    // Evaluated after replaceStringPatch, so this matches the rewritten www URL.
    strings = listOf("https://www.reddit.com/api/v1/authorize.compact?client_id=Q43fSpTe8LckEg&response_type=code&state=")
)

internal val getBearerTokenFingerprint = Fingerprint(
    classFingerprint = getAuthorizationStringFingerprint,
    strings = listOf("Basic ")
)

internal val getUserAgentFingerprint = Fingerprint(
    returnType = "Ljava/lang/String;",
    filters = listOf(string("android:com.laurencedawson.reddit_sync", StringComparisonType.STARTS_WITH))
)

internal val imgurImageAPIFingerprint = Fingerprint(
    strings = listOf("https://imgur-apiv3.p.rapidapi.com/3/image")
)

internal val getRedirectUriFingerprint = Fingerprint(
    strings = listOf("http://redditsync/auth")
)
