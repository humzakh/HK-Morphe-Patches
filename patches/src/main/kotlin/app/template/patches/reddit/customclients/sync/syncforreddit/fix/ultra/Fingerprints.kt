package app.template.patches.reddit.customclients.sync.syncforreddit.fix.ultra

import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal val UltraHelperFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("UltraHelper")
)

internal val SyncUltraSubTypeFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("Sync Ultra Monthly", "Sync Ultra Yearly", "Sync Ultra lifetime")
)

internal val UltraLifetimeFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("ultra_lifetime")
)

internal val CommentHolderBindFingerprint = Fingerprint(
    strings = listOf("+", "[Filtered]")
)

internal val CommentHolderOnProfileClickedFingerprint = Fingerprint(
    definingClass = "Lcom/laurencedawson/reddit_sync/ui/viewholders/comments/CommentHolder;",
    name = "onProfileClicked",
    parameters = listOf("Landroid/view/View;"),
    returnType = "V"
)

internal val CommentHolderTFingerprint = Fingerprint(
    definingClass = "Lcom/laurencedawson/reddit_sync/ui/viewholders/comments/CommentHolder;",
    name = "T",
    returnType = "Z",
    parameters = listOf(),
    accessFlags = listOf(AccessFlags.PRIVATE)
)

internal val CommentSwipeActionProfileFingerprint = Fingerprint(
    definingClass = "Lgb/m;",
    name = "E",
    parameters = listOf("Lxa/d;", "I"),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE)
)

internal val CommentSwipeActionDrawFingerprint = Fingerprint(
    definingClass = "Lgb/m;",
    name = "u",
    parameters = listOf("Landroid/graphics/Canvas;", "Landroidx/recyclerview/widget/RecyclerView;", "Landroidx/recyclerview/widget/RecyclerView\$c0;", "F", "F", "I", "Z"),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC)
)

internal val RestoreCommentParseNetworkResponseFingerprint = Fingerprint(
    definingClass = "Lr8/h;",
    name = "parseNetworkResponse",
    parameters = listOf("Lcom/android/volley/NetworkResponse;"),
    returnType = "Lcom/android/volley/Response;"
)