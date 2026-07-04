package app.template.patches.reddit.customclients.sync.syncforreddit.fix.imagepostselftext

import app.morphe.patcher.Fingerprint

internal val CardSelftextPreviewTextViewInitFingerprint = Fingerprint(
    definingClass = "Lcom/laurencedawson/reddit_sync/ui/views/posts/cards/CardSelftextPreviewTextView;",
    name = "<init>",
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;"),
    returnType = "V"
)

internal val SimpleSelftextPreviewTextViewInitFingerprint = Fingerprint(
    definingClass = "Lcom/laurencedawson/reddit_sync/ui/views/posts/simple/SimpleSelftextPreviewTextView;",
    name = "<init>",
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;"),
    returnType = "V"
)

internal val CardSelftextPreviewTextViewBindFingerprint = Fingerprint(
    definingClass = "Lcom/laurencedawson/reddit_sync/ui/views/posts/cards/CardSelftextPreviewTextView;",
    name = "J",
    parameters = listOf("Lxa/d;"),
    returnType = "V"
)

internal val SimpleSelftextPreviewTextViewBindFingerprint = Fingerprint(
    definingClass = "Lcom/laurencedawson/reddit_sync/ui/views/posts/simple/SimpleSelftextPreviewTextView;",
    name = "K",
    parameters = listOf("Lxa/d;"),
    returnType = "V"
)

internal val CardSelftextPreviewTextViewExpandedBindFingerprint = Fingerprint(
    definingClass = "Lcom/laurencedawson/reddit_sync/ui/views/posts/cards/CardSelftextPreviewTextView;",
    name = "K",
    parameters = listOf("Lxa/d;", "Z"),
    returnType = "V"
)

internal val SimpleSelftextPreviewTextViewExpandedBindFingerprint = Fingerprint(
    definingClass = "Lcom/laurencedawson/reddit_sync/ui/views/posts/simple/SimpleSelftextPreviewTextView;",
    name = "L",
    parameters = listOf("Lxa/d;", "Z"),
    returnType = "V"
)
