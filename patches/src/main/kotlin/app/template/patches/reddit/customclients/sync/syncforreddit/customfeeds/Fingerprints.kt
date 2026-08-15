package app.template.patches.reddit.customclients.sync.syncforreddit.customfeeds

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Names the extension serving this app's feed, which the generic extension constructs on first use.
 *
 * Compiled as a method answering nothing, which the patch replaces with the feed it was applied for.
 */
internal val feedClassFingerprint = Fingerprint(
    name = "feedClass",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    custom = { _, classDef ->
        classDef.type == "Lapp/morphe/extension/syncforreddit/CustomFeedExtension;"
    }
)

/**
 * Builds the url for a page of posts. Called only from the request's constructor, so redirecting it
 * covers every posts request the app makes.
 */
internal val postsRequestBuildUrlFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    strings = listOf("Sub: ", "&limit=25", "domain/"),
    custom = { _, classDef -> classDef.sourceFile == "OAuthPostsRequest.java" }
)

/**
 * Turns the posts response into the listing Sync parses.
 */
internal val parsePostsNetworkResponseFingerprint = Fingerprint(
    name = "parseNetworkResponse",
    returnType = "Lcom/android/volley/Response;",
    custom = { _, classDef -> classDef.sourceFile == "OAuthPostsRequest.java" }
)

/**
 * Builds the url for a post's comments.
 */
internal val commentsRequestBuildUrlFingerprint = Fingerprint(
    returnType = "Ljava/lang/String;",
    strings = listOf("comments/", "/_/", "&context=100"),
    custom = { _, classDef -> classDef.sourceFile == "OAuthCommentsRequest.java" }
)

/**
 * Turns the comments response into the comment tree Sync parses.
 */
internal val parseCommentsNetworkResponseFingerprint = Fingerprint(
    name = "parseNetworkResponse",
    returnType = "Lcom/android/volley/Response;",
    custom = { _, classDef -> classDef.sourceFile == "OAuthCommentsRequest.java" }
)

/**
 * Sends a link to another app.
 *
 * The app has two of these, with the same signature and both sending the same intent. This is the one
 * comments, subreddits and links in a page are shared with.
 */
internal val shareLinkFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;"),
    strings = listOf("android.intent.action.SEND", "android.intent.extra.TEXT"),
    custom = { method, classDef ->
        classDef.sourceFile == "RedditHelper.java" &&
            // Only the other one special cases Reddit's video host.
            method.implementation?.instructions?.none {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "v.redd.it"
            } == true
    }
)

/**
 * Sends a post's link to another app, which posts are shared with rather than [shareLinkFingerprint].
 *
 * Identified by the video host it special cases, which the other does not mention.
 */
internal val sharePostLinkFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;"),
    strings = listOf("android.intent.action.SEND", "v.redd.it"),
    custom = { _, classDef -> classDef.sourceFile == "RedditHelper.java" }
)

/**
 * Copies text to the clipboard, which is where copied links are handed in the same way.
 */
internal val copyLinkFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/CharSequence;", "Z"),
    custom = { _, classDef -> classDef.sourceFile == "RedditHelper.java" }
)

/**
 * Copies text to the clipboard, which the share and copy sheets hand a link to directly rather than
 * through [copyLinkFingerprint].
 */
internal val copyTextFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Ljava/lang/CharSequence;", "Landroid/content/Context;"),
    custom = { _, classDef -> classDef.sourceFile == "RedditHelper.java" }
)

/**
 * Copies a link to the clipboard, which the sheets offering a comment's link and the links written in
 * a post use rather than either of the methods above.
 */
internal val copyUrlFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    custom = { _, classDef -> classDef.sourceFile == "RedditHelper.java" }
)

/**
 * Hands a link to whatever opens it outside the app, as choosing to open one in a browser does.
 */
internal val openExternallyFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    strings = listOf("Opening link externally: "),
    custom = { _, classDef -> classDef.sourceFile == "LinkHandler.java" }
)

/**
 * Opens the subreddit named on a post, as tapping the name above it does.
 *
 * This is the tap on the post itself rather than the method every way of opening a subreddit runs
 * through, so that a link written out as `r/hackernews` still opens the subreddit it names.
 */
internal val openPostSubredditFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = emptyList(),
    custom = { method, classDef ->
        classDef.sourceFile == "SimpleHolder.java" &&
            method.name == "onSubviewClicked"
    }
)

/**
 * The screen a post's comments are shown on, which names the subreddit above them.
 */
internal val commentsScreenFingerprint = Fingerprint(
    custom = { method, classDef ->
        classDef.sourceFile == "CommentsFragment.java" &&
            method.name == "B3"
    }
)

/**
 * Builds the url for a subreddit's rules.
 */
internal val rulesBuildUrlFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    strings = listOf("/about/rules.json"),
    custom = { _, classDef -> classDef.sourceFile == "OAuthRulesRequest.java" }
)

/**
 * Turns the rules response into the page shown.
 */
internal val parseRulesNetworkResponseFingerprint = Fingerprint(
    name = "parseNetworkResponse",
    returnType = "Lcom/android/volley/Response;",
    custom = { _, classDef -> classDef.sourceFile == "OAuthRulesRequest.java" }
)

/**
 * Builds the url for a subreddit's wiki.
 */
internal val wikiBuildUrlFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    strings = listOf("/wiki/"),
    custom = { _, classDef -> classDef.sourceFile == "OAuthWikiRequest.java" }
)

/**
 * Turns the wiki response into the page shown.
 */
internal val parseWikiNetworkResponseFingerprint = Fingerprint(
    name = "parseNetworkResponse",
    returnType = "Lcom/android/volley/Response;",
    custom = { _, classDef -> classDef.sourceFile == "OAuthWikiRequest.java" }
)

/**
 * The icon shown on the chip naming the current sort.
 *
 * Recognises Reddit's own sorts and falls back to a cross for anything else, which is what the feeds
 * added here would otherwise show.
 */
internal val sortChipIconFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "I",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
    strings = listOf("best", "hot", "new", "Controversial"),
    custom = { _, classDef -> classDef.sourceFile == "MaterialPostsHelper.java" }
)

/**
 * Fills the sort dialog with the sorts available for what is being viewed.
 */
internal val sortOptionsFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = emptyList(),
    strings = listOf("Hot", "New", "Top", "Comments"),
    custom = { _, classDef -> classDef.sourceFile == "SubredditSortAccessDialogBottomSheet.java" }
)

/**
 * Whether a name is a subreddit that shows an icon, as opposed to a custom feed or a special page
 * such as the frontpage, which do not.
 */
internal val showsSubredditIconFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    strings = listOf("Messaging"),
    custom = { _, classDef -> classDef.sourceFile == "MaterialPostsHelper.java" }
)

/**
 * Looks up the icon shown for a subreddit or feed.
 */
internal val subredditIconFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;"),
    custom = { _, classDef -> classDef.sourceFile == "SubredditIconManager.java" }
)

/**
 * Turns a subreddit or feed name into the title shown above the posts.
 */
internal val subredditTitleFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;", "Z"),
    strings = listOf("multi_", " [M]"),
    custom = { _, classDef -> classDef.sourceFile == "SubredditHelper.java" }
)

/**
 * Builds the url listing the subreddits a custom feed contains.
 */
internal val multiSubredditsBuildUrlFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    strings = listOf("api/multi/user/", "/m/"),
    custom = { _, classDef -> classDef.sourceFile == "OAuthMultisInnerRequest.java" }
)

/**
 * Turns the response listing a custom feed's subreddits into the names Sync shows.
 */
internal val parseMultiSubredditsNetworkResponseFingerprint = Fingerprint(
    name = "parseNetworkResponse",
    returnType = "Lcom/android/volley/Response;",
    custom = { _, classDef -> classDef.sourceFile == "OAuthMultisInnerRequest.java" }
)

/**
 * Supplies the parameters every Reddit request is sent with.
 *
 * Requests that act on a post or comment put its id in these parameters, so this is the one place
 * every write action can be recognised, rather than patching each request in turn.
 */
internal val requestParamsFingerprint = Fingerprint(
    name = "getParams",
    returnType = "Ljava/util/Map;",
    custom = { _, classDef -> classDef.sourceFile == "RedditRequest.java" }
)

/**
 * Builds the line of markers shown under a post's title, such as its flair and whether it is pinned,
 * archived or locked.
 */
internal val postMarkersFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    strings = listOf("Archived", "Locked", "Pinned"),
    custom = { _, classDef -> classDef.sourceFile == "TextFormatterManager.java" }
)

/**
 * Fills in the notice shown above a post's comments when it cannot be voted on or replied to.
 */
internal val commentsNoticeFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    strings = listOf("This is an archived post.\nYou will not be able to vote or comment"),
    custom = { _, classDef -> classDef.sourceFile == "CommentHeaderHolder.java" }
)

/**
 * Whether the entry in the subreddit list is a custom feed, which is what the option to delete one is
 * shown for.
 */
internal val deletableFeedFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE),
    returnType = "Z",
    parameters = emptyList(),
    strings = listOf("multi_"),
    custom = { _, classDef -> classDef.sourceFile == "SubredditHolder.java" }
)

/**
 * Decides what a link opens, which a link handed to the app from elsewhere is routed through.
 *
 * A link tapped in a post or comment is settled before it reaches here, but one opened from another
 * app arrives here directly, so both are taken.
 */
internal val openLinkFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    strings = listOf("Was comments", "Was subreddit", "Was reddit user"),
    custom = { _, classDef -> classDef.sourceFile == "LinkHelper.java" }
)

/**
 * Fills in the wiki page, which is titled with the subreddit and the page being read.
 */
internal val wikiTitleFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
    strings = listOf(" - "),
    custom = { _, classDef -> classDef.sourceFile == "WikiBottomSheetFragment.java" }
)

/**
 * Turns the response listing the account's custom feeds into the names Sync shows.
 */
internal val parseMultiredditsNetworkResponseFingerprint = Fingerprint(
    name = "parseNetworkResponse",
    returnType = "Lcom/android/volley/Response;",
    custom = { _, classDef -> classDef.sourceFile == "OAuthMultiredditsRequest.java" }
)

