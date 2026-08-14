package app.morphe.extension.syncforreddit;

import com.android.volley.AuthFailureError;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves Hacker News as if it were a subreddit.
 *
 * <p>Sync builds its post list from Reddit's listing JSON, so rather than teaching the UI about a
 * second content source, the Hacker News API is translated into the listing shape Sync already
 * parses. Every screen downstream of the parser - the post list, the database and the post view -
 * then works without being patched.
 *
 * @noinspection unused
 */
public class HackerNewsExtension {
    /** The display name of the custom feed, as it is listed and shown in the toolbar. */
    public static final String FEED_NAME = "Hacker News";

    /** The custom feed that serves Hacker News instead of Reddit. Sync prefixes feeds with "multi_". */
    public static final String FEED = "multi_" + FEED_NAME;

    private static final String API = "https://hacker-news.firebaseio.com/v0/";

    /** The site stories and comments are read on, as opposed to the API they are fetched from. */
    private static final String NEWS_HOST = "https://news.ycombinator.com/";

    /** The prefixes the app puts in front of a permalink when building a link to share. */
    private static final String[] REDDIT_HOSTS = { "https://www.reddit.com", "https://reddit.com" };

    /** Matches the 30 stories Hacker News shows on a page. */
    private static final int PAGE_SIZE = 30;

    /**
     * Story ids are fetched one request each, so they are fetched in parallel. Hacker News serves
     * these from a CDN without rate limiting, but the pool is kept small enough to stay polite.
     */
    private static final int THREADS = 24;

    private static final int TIMEOUT_MS = 10_000;

    /**
     * How deep a comment thread is followed, and how many comments are loaded in total.
     *
     * <p>Hacker News serves one comment per request, so these bound how long a thread takes to open.
     * They are set above the largest threads seen on the front page, which run to a few hundred
     * comments and nothing like this deep, so in practice a thread loads completely and the limits
     * only stop a pathological one from loading forever.
     */
    private static final int MAX_COMMENT_DEPTH = 24;

    private static final int MAX_COMMENTS = 2000;

    /** Counts comments loaded for the thread being parsed, to enforce [MAX_COMMENTS]. */
    private static final AtomicInteger fetched = new AtomicInteger();

    /**
     * Hacker News has no equivalent of a subreddit id, and Sync only uses these to group posts, so
     * fixed values stand in.
     */
    private static final String SUBREDDIT_ID = "t5_hackernews";

    /**
     * The subreddit each post reports itself as belonging to.
     *
     * <p>Posts within a custom feed still carry a subreddit, which Sync shows on each post and uses to
     * group them, so stories are attributed to a Hacker News subreddit rather than the feed itself.
     */
    public static final String SUBREDDIT = "hackernews";

    /**
     * The offset of the page currently being requested.
     *
     * <p>The response is identified and translated without the request url to hand, so the offset is
     * carried from the url being built to the response being parsed. Volley dispatches one posts
     * request at a time, so a single value is enough.
     */
    private static volatile int pendingOffset;

    /** The site's own guidelines, shown as the feed's rules. */
    private static final String GUIDELINES_URL = NEWS_HOST + "newsguidelines.html";

    /** The guidelines for Show HN, which are shown while that feed is being read. */
    private static final String SHOW_GUIDELINES_URL = NEWS_HOST + "showhn.html";

    /** The feed shown when none has been chosen, whatever sort the app defaults its own posts to. */
    public static final String DEFAULT_SORT = "Hot";

    /** The feed currently being read, so the rules shown can follow it. */
    private static volatile String currentSort = DEFAULT_SORT;

    /** The site's own FAQ, shown as the feed's wiki. */
    private static final String FAQ_URL = NEWS_HOST + "newsfaq.html";

    /** Whether the page being loaded belongs to the Hacker News feed, and if so where to read it. */
    private static volatile boolean pendingGuidelines;

    private static volatile boolean pendingFaq;

    /**
     * The address to read the feed's sidebar from.
     *
     * @param name the subreddit whose about page is being loaded.
     * @return the address to request, or {@code null} to leave the request as it is.
     */
    public static String guidelinesUrl(String name) {
        pendingGuidelines = isFeedName(name);
        if (!pendingGuidelines) {
            return null;
        }

        // Show HN has its own guidelines, which are the useful ones while reading that feed.
        return "Show".equalsIgnoreCase(currentSort) ? SHOW_GUIDELINES_URL : GUIDELINES_URL;
    }

    /** The title the feed's wiki is shown under, in place of the feed name and page it is read from. */
    private static final String FAQ_TITLE = FEED_NAME + " FAQ";

    /**
     * The title to show above a wiki page.
     *
     * <p>The feed's wiki is the site's FAQ rather than a page an account wrote, so it is named as
     * that rather than as the feed and page it is read from.
     *
     * @param title the title the app was going to show.
     * @return the title to show.
     */
    public static String wikiTitle(String title) {
        return title != null && title.startsWith(FEED) ? FAQ_TITLE : title;
    }

    /**
     * The address to read the feed's wiki from.
     *
     * @param name the subreddit whose wiki is being loaded.
     * @return the address to request, or {@code null} to leave the request as it is.
     */
    public static String faqUrl(String name) {
        pendingFaq = isFeedName(name);
        return pendingFaq ? FAQ_URL : null;
    }

    /**
     * Replaces the rules response with the site's guidelines.
     *
     * <p>The app renders each rule as a heading followed by its text, so the whole page is returned as
     * a single rule, converted from the site's html to the Markdown the app renders.
     */
    public static byte[] rewriteRules(byte[] data) {
        if (!pendingGuidelines) {
            return data;
        }

        try {
            boolean show = "Show".equalsIgnoreCase(currentSort);

            JSONObject rule = new JSONObject();
            rule.put("short_name", show ? "Show HN Guidelines" : "Hacker News Guidelines");
            rule.put("description", readPage(data));

            JSONArray rules = new JSONArray();
            rules.put(rule);

            JSONObject response = new JSONObject();
            response.put("rules", rules);

            return response.toString().getBytes("UTF-8");
        } catch (Exception e) {
            return data;
        }
    }

    /**
     * Replaces the wiki response with the site's FAQ.
     */
    public static byte[] rewriteWiki(byte[] data) {
        if (!pendingFaq) {
            return data;
        }

        try {
            String page = readPage(data);

            // The page opens with its own title, which the header above it already says.
            String heading = "**" + FAQ_TITLE + "**";
            if (page.startsWith(heading)) {
                page = page.substring(heading.length()).trim();
            }

            JSONObject content = new JSONObject();
            content.put("content_md", page);

            JSONObject response = new JSONObject();
            response.put("kind", "wikipage");
            response.put("data", content);

            return response.toString().getBytes("UTF-8");
        } catch (Exception e) {
            return data;
        }
    }

    /**
     * Reads one of the site's pages as Markdown.
     *
     * <p>The pages are a table wrapping a run of paragraphs and bold headings, so the cell holding
     * them is taken and converted the same way comment bodies are.
     */
    private static String readPage(byte[] data) throws Exception {
        String html = new String(data, "UTF-8");

        // The page's content sits between the banner image and the footer.
        int start = html.indexOf("</a>");
        int end = html.lastIndexOf("</td>");
        if (start != -1 && end > start) {
            html = html.substring(start + "</a>".length(), end);
        }

        // Headings are bold on their own line; make them headings so they stand out when rendered.
        html = html.replaceAll("(?i)<b>(.*?)</b>", "\n\n**$1**\n");

        // The pages link to each other by file name, which means nothing once the page is read here.
        // Attributes are quoted with either kind of quote, so both are matched.
        html = html.replaceAll("(?i)href=[\"'](?!https?://)([^\"']+)[\"']", "href=\"" + NEWS_HOST + "$1\"");

        String markdown = HackerNewsMarkdown.fromHtml(html);

        // The pages wrap at a fixed width, which can fall inside a link's text. A link is not read as
        // one when its text runs over a line, so those are pulled back onto a single line.
        markdown = markdown.replaceAll("\\[([^]\\[]*)\\n\\s*([^]\\[]*)\\]\\(", "[$1 $2](");

        // The pages talk about markers such as [flagged] and [dead] as plain text. Where one is
        // followed by a bracketed aside, it reads as a link and swallows the aside, so brackets that
        // are not part of a link are escaped.
        markdown = markdown.replaceAll("\\[([^]\\[]*)\\](?!\\()", "\\\\[$1\\\\]");

        // The pages are laid out with blank lines between every element, which read as gaps once the
        // markup is gone.
        return markdown.replaceAll("\n{3,}", "\n\n");
    }

    /**
     * The feed to read, given the sort the app is asking for.
     *
     * <p>The app carries its own default sort, which may be any of Reddit's; anything this patch does
     * not offer is read as the front page, so the feed opens on [DEFAULT_SORT] rather than on a sort
     * that means nothing here.
     */
    public static String sortOrDefault(String sort) {
        currentSort = isKnownSort(sort) ? sort.trim() : DEFAULT_SORT;
        return currentSort;
    }

    /** Whether [sort] is one of the feeds this patch offers. */
    private static boolean isKnownSort(String sort) {
        if (sort == null) {
            return false;
        }

        String value = sort.trim();
        return value.equalsIgnoreCase("Hot")
                || value.equalsIgnoreCase("New")
                || value.equalsIgnoreCase("Best")
                || value.equalsIgnoreCase("Ask")
                || value.equalsIgnoreCase("Show")
                || value.equalsIgnoreCase("Jobs");
    }

    /**
     * The feeds Hacker News serves, by the name each is offered under in the sort dialog.
     *
     * <p>These stand in for Reddit's sorts, which have no meaning here. "Hot" is the site's front
     * page, named after the Reddit sort it sits in place of. Anything unrecognised, such as a sort
     * remembered from before this feed existed, falls back to that same front page.
     */
    private static String feedEndpoint(String sort) {
        if (sort == null) {
            return "topstories";
        }

        String value = sort.trim();
        if (value.equalsIgnoreCase("Hot")) {
            return "topstories";
        }
        if (value.equalsIgnoreCase("New")) {
            return "newstories";
        }
        if (value.equalsIgnoreCase("Best")) {
            return "beststories";
        }
        if (value.equalsIgnoreCase("Ask")) {
            return "askstories";
        }
        if (value.equalsIgnoreCase("Show")) {
            return "showstories";
        }
        if (value.equalsIgnoreCase("Jobs")) {
            return "jobstories";
        }
        return "topstories";
    }

    /**
     * The icon shown on the chip naming the current feed.
     *
     * <p>The app maps Reddit's own sorts to icons and falls back to a cross for anything else, so the
     * feeds added by this patch supply their own. These match the icons the sort dialog offers them
     * with; both are drawables the app already ships.
     *
     * @param sort the feed currently being read.
     * @return the drawable to show, or {@code 0} for a sort this patch did not add, which the app
     * then handles itself.
     */
    public static int sortIcon(String sort) {
        if (sort == null) {
            return 0;
        }

        String value = sort.trim();
        if (value.equalsIgnoreCase("Ask")) {
            // outline_help_outline_24
            return 0x7f08048e;
        }
        if (value.equalsIgnoreCase("Show")) {
            // ic_trending_up_white_24dp
            return 0x7f0801e1;
        }
        if (value.equalsIgnoreCase("Jobs")) {
            // outline_bar_chart_24
            return 0x7f0802cf;
        }

        // Best, Hot and New are named after Reddit sorts the app already has icons for.
        return 0;
    }

    /**
     * The url Sync requests for the Hacker News feed. Only the id list is fetched here; the stories
     * themselves are fetched while parsing, once the page offset is known.
     *
     * @param after the pagination cursor, which is the offset into the story list.
     * @param sort  the feed chosen from the sort dialog.
     */
    public static String buildUrl(String after, String sort) {
        // The story list is a plain ordered array, so the cursor is just the offset into it.
        pendingOffset = parseOffset(after);
        return API + feedEndpoint(sort) + ".json";
    }

    /**
     * The stories and comments served from Hacker News, as Reddit ids.
     *
     * <p>Hacker News ids are numeric, but so are some Reddit ids, so the ids actually served are
     * remembered rather than guessed at from their shape. Bounded so that a long browsing session
     * cannot grow it without limit; the oldest ids are dropped, which at worst lets a write action
     * through for a post scrolled far past.
     */
    private static final Set<String> served =
            Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_REMEMBERED_IDS;
                }
            }));

    private static final int MAX_REMEMBERED_IDS = 2000;

    /**
     * The request parameters naming the post or comment a write action acts on.
     *
     * <p>Voting and saving send it as "id"; replying sends it as "thing_id".
     */
    private static final String[] ID_PARAMS = { "id", "thing_id" };

    /**
     * Fails the request if it acts on Hacker News content.
     *
     * <p>Reddit's write endpoints take the id of the post or comment being acted on, so such a request
     * would ask Reddit to act on an id that means nothing there. Read requests do not name an id this
     * way, so they are unaffected.
     *
     * <p>Failing rather than quietly dropping the request lets Volley report it through the error
     * listener the action was made with, so the app shows the action failing instead of appearing to
     * succeed.
     *
     * @param params the parameters the request is being sent with.
     * @throws AuthFailureError if the request acts on Hacker News content.
     */
    public static void checkWriteAllowed(Map<String, String> params) throws AuthFailureError {
        if (params == null) {
            return;
        }

        for (String key : ID_PARAMS) {
            String id;
            try {
                id = params.get(key);
            } catch (Exception e) {
                // A map that does not support lookups is not one of Sync's parameter maps.
                return;
            }

            if (isHackerNewsId(id)) {
                throw new AuthFailureError("Not supported on Hacker News");
            }
        }
    }

    /**
     * Whether a fullname such as {@code t3_49294997} names Hacker News content.
     *
     * <p>The ids served are only known for as long as the app has been running, because posts are
     * rebuilt from Sync's database rather than from a response. So an id that was not served this
     * session is judged by its shape instead: Hacker News numbers its items, while Reddit's ids are
     * base 36 and in practice always contain a letter at their current length.
     *
     * <p>Judging by shape can only ever be wrong in the safe direction here. Mistaking a Reddit id for
     * a Hacker News one fails an action the user can retry, whereas the reverse would send a vote to
     * Reddit for a post that does not exist there.
     */
    private static boolean isHackerNewsId(String id) {
        if (id == null) {
            return false;
        }

        if (served.contains(id)) {
            return true;
        }

        int separator = id.indexOf('_');
        if (separator == -1) {
            return false;
        }

        String value = id.substring(separator + 1);
        if (value.isEmpty()) {
            return false;
        }

        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * The story whose comments are being loaded.
     *
     * <p>As with the page offset, the story is carried from the url being built to the response being
     * parsed, since the response is identified without the request url to hand.
     */
    private static volatile String pendingStory;

    /**
     * The url Sync requests for a story's comments.
     *
     * <p>Only the story is fetched here. Its comments are fetched while parsing, where they can be
     * fetched concurrently rather than one request at a time.
     *
     * <p>Recognised by the id rather than by the subreddit the post reports, because Reddit has a
     * subreddit of the same name whose posts must still be read from Reddit.
     *
     * @param id the post whose comments are being loaded.
     * @return the url to request, or {@code null} to leave the request as it is.
     */
    public static String buildCommentsUrl(String id) {
        if (!isHackerNewsId("t3_" + id)) {
            return null;
        }

        pendingStory = id;
        return API + "item/" + id + ".json";
    }

    /**
     * Translates a Hacker News story into the comment tree Sync parses.
     *
     * <p>Sync expects Reddit's two element comments response: the post, followed by its comments.
     *
     * @param data the raw story response.
     * @return the comment tree to parse in place of the response, or the response unchanged when it is
     * not a Hacker News one.
     */
    public static byte[] rewriteComments(byte[] data) {
        try {
            if (data == null || pendingStory == null || isStoryList(data)) {
                return data;
            }

            JSONObject story = new JSONObject(new String(data, "UTF-8"));

            // Reddit posts carry a "subreddit"; a Hacker News item never does, which distinguishes a
            // story response from a Reddit one.
            if (story.has("subreddit") || !pendingStory.equals(String.valueOf(story.optInt("id")))) {
                return data;
            }

            JSONArray postChildren = new JSONArray();
            postChildren.put(toChild(story));

            fetched.set(0);
            JSONArray comments = fetchComments(childIds(story), "t3_" + pendingStory, 0);

            JSONArray response = new JSONArray();
            response.put(listing(postChildren));
            response.put(listing(comments));

            return response.toString().getBytes("UTF-8");
        } catch (Exception e) {
            return data;
        }
    }

    /** Wraps [children] in the listing envelope Sync unwraps. */
    private static JSONObject listing(JSONArray children) throws Exception {
        JSONObject data = new JSONObject();
        data.put("after", JSONObject.NULL);
        data.put("before", JSONObject.NULL);
        data.put("children", children);

        JSONObject listing = new JSONObject();
        listing.put("kind", "Listing");
        listing.put("data", data);
        return listing;
    }

    /**
     * Fetches [ids] and their replies, as Reddit comment objects.
     *
     * <p>Hacker News serves one comment per request, so a thread costs one request per comment rather
     * than the single request a Reddit thread costs. Each level is fetched concurrently, and the tree
     * is bounded by [MAX_COMMENT_DEPTH] and [MAX_COMMENTS] so that a large thread cannot spend an
     * unbounded number of requests.
     */
    private static JSONArray fetchComments(List<Integer> ids, String parent, int depth) throws Exception {
        JSONArray children = new JSONArray();

        if (ids.isEmpty() || depth > MAX_COMMENT_DEPTH || fetched.get() >= MAX_COMMENTS) {
            return children;
        }

        for (JSONObject comment : fetchItems(ids)) {
            if (fetched.incrementAndGet() > MAX_COMMENTS) {
                break;
            }

            // Deleted comments keep their replies, which are still worth showing.
            String author = comment.optString("by", "[deleted]");
            String text = comment.optString("text", "");

            String id = String.valueOf(comment.optInt("id"));
            served.add("t1_" + id);

            JSONObject data = new JSONObject();
            data.put("id", id);
            data.put("name", "t1_" + id);
            data.put("parent_id", parent);
            data.put("link_id", "t3_" + pendingStory);
            data.put("subreddit", SUBREDDIT);
            data.put("subreddit_id", SUBREDDIT_ID);
            data.put("author", author);
            data.put("body", HackerNewsMarkdown.fromHtml(text));
            data.put("created_utc", comment.optLong("time"));
            data.put("depth", depth);

            // Sharing a comment builds reddit.com followed by this, which was empty for Hacker News
            // comments. The full Hacker News address is stored so the reddit.com prefix can be
            // stripped back off when the link is shared.
            data.put("permalink", discussionUrl(id));

            // Hacker News does not expose per comment scores.
            data.put("score", 1);
            data.put("score_hidden", true);
            data.put("likes", JSONObject.NULL);
            data.put("saved", false);
            data.put("edited", false);
            data.put("locked", true);
            data.put("archived", true);
            data.put("gilded", 0);
            data.put("controversiality", 0);
            data.put("distinguished", JSONObject.NULL);
            data.put("stickied", false);

            JSONArray replies = fetchComments(childIds(comment), "t1_" + id, depth + 1);
            // Reddit sends an empty string rather than an empty listing for a comment with no replies.
            data.put("replies", replies.length() == 0 ? "" : listing(replies));

            JSONObject child = new JSONObject();
            child.put("kind", "t1");
            child.put("data", data);
            children.put(child);
        }

        return children;
    }

    /** The ids of an item's direct replies. */
    private static List<Integer> childIds(JSONObject item) {
        List<Integer> ids = new ArrayList<>();

        JSONArray kids = item.optJSONArray("kids");
        if (kids != null) {
            for (int i = 0; i < kids.length(); i++) {
                ids.add(kids.optInt(i));
            }
        }
        return ids;
    }

    /**
     * Hacker News' own icon, the orange "Y".
     *
     * <p>Icons are shown from a url rather than from a bundled image, so the site's own is used. The
     * png is used rather than the svg the site also serves, since the app's image loading has no svg
     * support and shows Reddit's own icons as pngs.
     */
    private static final String ICON_URL = NEWS_HOST + "apple-touch-icon.png";

    /**
     * Whether a feed can be deleted, which the Hacker News feed cannot.
     *
     * <p>The feed is not one the account holds, but one this patch adds to the list, so deleting it
     * would ask Reddit to remove a feed it does not have. It reappears whenever the list is read
     * again, so the option is left off rather than failing.
     *
     * @param deletable whether the app was going to offer to delete the feed.
     * @param name the feed the list entry is showing.
     * @return whether the feed can be deleted.
     */
    public static boolean isDeletable(boolean deletable, String name) {
        return deletable && !isFeedName(name);
    }

    /**
     * Whether a name refers to the Hacker News feed, in either of the forms it is passed around as.
     *
     * <p>The subreddit a story reports is deliberately not one of them. Reddit has a subreddit of that
     * name, and matching it would give the feed's sorts, rules, wiki and icon to that subreddit, which
     * has nothing to do with this patch.
     *
     * @param name the feed or title a screen is showing.
     */
    public static boolean isFeedName(String name) {
        if (name == null) {
            return false;
        }

        String value = name.trim();
        return value.equalsIgnoreCase(FEED) || value.equalsIgnoreCase(FEED_NAME);
    }

    /**
     * Reddit's index of the links people have submitted, which is where thumbnails come from.
     *
     * <p>Hacker News serves no images with its stories, but a story's link has usually also been
     * submitted to Reddit, which generates a thumbnail for it. Asking Reddit what it holds for the
     * same link is the same request the app makes for "Other Discussions".
     */
    private static final String INFO_URL = "https://oauth.reddit.com/api/info?url=";

    /** The user agent the app identifies itself to Reddit with. */
    private static final String USER_AGENT =
            "android:com.laurencedawson.reddit_sync:vv23.06.30-13:39 (by /u/ljdawson)";

    /** The token the current posts request was sent with, used to ask Reddit about the same links. */
    private static volatile String token;

    /**
     * The thumbnail found for each link, kept for as long as the app is running.
     *
     * <p>Opening a post rewrites its row from the comments response, so the thumbnail found when the
     * post list was built is reused rather than looked up again. Bounded like the ids served, since a
     * long session would otherwise grow it without limit.
     */
    private static final Map<String, Preview> knownThumbnails =
            Collections.synchronizedMap(new LinkedHashMap<String, Preview>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Preview> eldest) {
                    return size() > MAX_REMEMBERED_IDS;
                }
            });

    /**
     * Remembers the token the posts request is authenticated with.
     *
     * <p>Read from the request being parsed, since Reddit only answers this for a signed-in client and
     * the token is held per request rather than anywhere reachable on its own.
     */
    public static void noteToken(String value) {
        token = value;
    }

    /**
     * The thumbnails Reddit holds for [urls], by the url each belongs to.
     *
     * <p>Looked up in one request for the whole page rather than one per story. Stories whose link has
     * not been submitted to Reddit simply have no entry, and are left without a thumbnail.
     */
    private static Map<String, Preview> thumbnails(List<String> urls) {
        Map<String, Preview> found = new LinkedHashMap<>();

        if (token == null || urls.isEmpty()) {
            return found;
        }

        // Reddit answers this for one link at a time, so the page's links are looked up in parallel
        // rather than one after another.
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(THREADS, urls.size()));

        try {
            Map<String, Future<Preview>> pending = new LinkedHashMap<>();
            for (final String url : urls) {
                pending.put(url, executor.submit(new Callable<Preview>() {
                    @Override
                    public Preview call() {
                        return previewFor(url);
                    }
                }));
            }

            for (Map.Entry<String, Future<Preview>> entry : pending.entrySet()) {
                try {
                    Preview preview = entry.getValue().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (preview != null) {
                        found.put(entry.getKey(), preview);
                        knownThumbnails.put(entry.getKey(), preview);
                    }
                } catch (Exception e) {
                    // A link Reddit does not answer for simply goes without a thumbnail.
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return found;
    }

    /**
     * What Reddit holds for a link: the small thumbnail, and the full size preview behind it.
     *
     * <p>The app shows a post as a large card when it has a preview, and as a small thumbnail when it
     * only has that, so both are taken.
     */
    private static final class Preview {
        final String thumbnail;
        final String url;
        final int width;
        final int height;

        Preview(String thumbnail, String url, int width, int height) {
            this.thumbnail = thumbnail;
            this.url = url;
            this.width = width;
            this.height = height;
        }
    }

    /** The thumbnail Reddit holds for a single link, or {@code null} if it holds none. */
    private static Preview previewFor(String url) {
        try {
            String body = get(INFO_URL + URLEncoder.encode(url, "UTF-8"), token);
            if (body == null) {
                return null;
            }

            JSONArray children = new JSONObject(body)
                    .getJSONObject("data")
                    .getJSONArray("children");

            for (int i = 0; i < children.length(); i++) {
                JSONObject post = children.getJSONObject(i).optJSONObject("data");
                if (post == null) {
                    continue;
                }

                String thumbnail = post.optString("thumbnail", "");
                // Reddit uses these in place of an image for posts it has no preview for.
                boolean hasThumbnail = thumbnail.startsWith("http")
                        && !thumbnail.equals("self")
                        && !thumbnail.equals("default")
                        && !thumbnail.equals("nsfw")
                        && !thumbnail.equals("spoiler");
                if (!hasThumbnail) {
                    continue;
                }

                // The full size image behind the thumbnail, which is what makes the app show the post
                // as a large card rather than a small row.
                String previewUrl = "";
                int width = 0;
                int height = 0;

                JSONObject preview = post.optJSONObject("preview");
                if (preview != null) {
                    JSONArray images = preview.optJSONArray("images");
                    if (images != null && images.length() > 0) {
                        JSONObject source = images.getJSONObject(0).optJSONObject("source");
                        if (source != null) {
                            // Reddit escapes these for html, and they are rejected as they stand.
                            previewUrl = HackerNewsMarkdown.unescape(source.optString("url", ""));
                            width = source.optInt("width");
                            height = source.optInt("height");
                        }
                    }
                }

                return new Preview(thumbnail, previewUrl, width, height);
            }
        } catch (Exception e) {
            // Treated the same as Reddit holding nothing for the link.
        }
        return null;
    }

    /**
     * The name to open in place of [name], for the name shown on [post].
     *
     * <p>A story carries the subreddit it reports rather than the feed it came from, so opening one by
     * name would ask Reddit for a subreddit that does not exist. The feed is opened instead.
     *
     * <p>Decided from the post rather than from the name it reports, because Reddit has a subreddit of
     * that name as well, and a post from there opens the subreddit as it always did.
     *
     * @param name the subreddit being opened.
     * @param shownOn the holder or screen the name was tapped on.
     * @return the feed to open, or [name] unchanged for a post from Reddit.
     */
    public static String feedFor(String name, Object shownOn) {
        return isHackerNewsPost(postShownOn(shownOn)) ? FEED : name;
    }

    /**
     * The post whose name is shown on [shownOn].
     *
     * <p>A post in a list holds its own post, while the screen a post's comments are shown on holds
     * the state it was opened with and reads the post out of that. Both are obfuscated, so each is
     * reached by the name its getter keeps.
     */
    private static Object postShownOn(Object shownOn) {
        try {
            // A post in a list, which answers with the post it is showing.
            return shownOn.getClass().getMethod("j").invoke(shownOn);
        } catch (Exception e) {
            // Not a post in a list, so the comments screen is tried instead.
        }

        try {
            // The comments screen, which answers with the state it was opened with, and that with the
            // post the comments belong to.
            Object state = shownOn.getClass().getMethod("A3").invoke(shownOn);
            return state == null ? null : state.getClass().getMethod("e0").invoke(state);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The icon to show for the Hacker News feed.
     *
     * <p>Icons are looked up by name, and the name reaching the lookup is sometimes the feed, sometimes
     * the title it is shown under, and sometimes the subreddit its posts report. All three are matched
     * so the icon appears wherever the feed does.
     *
     * @param name the subreddit or feed an icon is wanted for.
     * @return the icon to show, or {@code null} to leave the lookup as it is.
     */
    public static String iconUrl(String name) {
        return isFeedName(name) ? ICON_URL : null;
    }

    /**
     * Whether the subreddits being listed are those of the Hacker News feed.
     *
     * <p>The feed does not exist on Reddit, so asking Reddit what it contains fails and the app reports
     * that the feed could not be loaded. The response is replaced rather than the request avoided,
     * since the request is made whichever url it is given.
     */
    private static volatile boolean pendingFeed;

    /**
     * The address to list the Hacker News feed's subreddits from.
     *
     * <p>Reddit answers 404 for a feed that does not exist there, and a failed response is raised as
     * an error before it can be parsed. So the request is pointed at an address that answers
     * successfully, and its response replaced with the feed's contents once parsing is reached.
     *
     * @param name the feed being listed.
     * @return the address to request, or {@code null} to leave the request as it is.
     */
    public static String feedSubredditsUrl(String name) {
        pendingFeed = FEED_NAME.equals(name);

        // Any address answering with a small, valid body will do; the response is replaced regardless.
        return pendingFeed ? API + "maxitem.json" : null;
    }

    /**
     * Replaces the response listing a feed's subreddits, when it is the Hacker News feed.
     *
     * @param data the raw response, which for this feed is Reddit's error.
     * @return the subreddits the feed contains, or the response unchanged for any other feed.
     */
    public static byte[] rewriteFeedSubreddits(byte[] data) {
        if (!pendingFeed) {
            return data;
        }

        byte[] subreddits = feedSubreddits();
        return subreddits == null ? data : subreddits;
    }

    /**
     * The subreddits the Hacker News feed contains, in the shape Reddit lists them.
     */
    private static byte[] feedSubreddits() {
        try {
            JSONObject subreddit = new JSONObject();
            subreddit.put("name", SUBREDDIT);

            JSONArray subreddits = new JSONArray();
            subreddits.put(subreddit);

            JSONObject data = new JSONObject();
            data.put("name", FEED_NAME);
            data.put("subreddits", subreddits);

            JSONObject response = new JSONObject();
            response.put("kind", "LabeledMulti");
            response.put("data", data);

            return response.toString().getBytes("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Adds the Hacker News feed to the account's custom feeds.
     *
     * <p>The feeds response is an array of {@code {"data":{"name":...}}} objects, so the feed is added
     * by appending one more entry rather than by patching the parsing that reads it.
     *
     * @param data the raw custom feeds response.
     * @return the response with the Hacker News feed appended, or unchanged if it cannot be read.
     */
    public static byte[] addFeed(byte[] data) {
        try {
            if (data == null) {
                return null;
            }

            JSONArray feeds = new JSONArray(new String(data, "UTF-8"));

            // The response is re-read on every refresh, so guard against listing the feed twice.
            for (int i = 0; i < feeds.length(); i++) {
                JSONObject existing = feeds.getJSONObject(i).optJSONObject("data");
                if (existing != null && FEED_NAME.equals(existing.optString("name"))) {
                    return data;
                }
            }

            JSONObject feed = new JSONObject();
            feed.put("name", FEED_NAME);
            feed.put("display_name", FEED_NAME);

            JSONObject entry = new JSONObject();
            entry.put("kind", "LabeledMulti");
            entry.put("data", feed);

            feeds.put(entry);

            return feeds.toString().getBytes("UTF-8");
        } catch (Exception e) {
            // Leaving the response untouched simply means the feed is not listed.
            return data;
        }
    }

    /**
     * Translates a Hacker News response into a Reddit listing.
     *
     * <p>Reddit responses are JSON objects and the Hacker News story list is the only bare array Sync
     * receives here, which is what identifies a response as one to translate.
     *
     * @param data the raw response body.
     * @return the listing to parse in place of the response, or the response unchanged when it is not
     * a Hacker News one.
     */
    public static byte[] rewriteResponse(byte[] data) {
        try {
            if (data == null || !isStoryList(data)) {
                return data;
            }

            String listing = buildListing(data, pendingOffset);
            return listing == null ? data : listing.getBytes("UTF-8");
        } catch (Exception e) {
            // Returning the response untouched leaves Sync to fail the request as it normally would.
            return data;
        }
    }

    /** Whether the body is the Hacker News story id array rather than a Reddit response. */
    private static boolean isStoryList(byte[] data) {
        for (byte b : data) {
            // Skip any leading whitespace to reach the first meaningful character.
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
                continue;
            }
            return b == '[';
        }
        return false;
    }

    private static String buildListing(byte[] data, int offset) {
        try {
            JSONArray ids = new JSONArray(new String(data, "UTF-8"));

            List<Integer> page = new ArrayList<>();
            for (int i = offset; i < ids.length() && page.size() < PAGE_SIZE; i++) {
                page.add(ids.getInt(i));
            }

            List<JSONObject> stories = fetchItems(page);

            // Hacker News serves no images, so Reddit is asked what it holds for the same links. One
            // request covers the whole page; stories Reddit has never seen simply go without.
            List<String> links = new ArrayList<>();
            for (JSONObject story : stories) {
                String link = story.optString("url", "");
                if (!link.isEmpty()) {
                    links.add(link);
                }
            }
            Map<String, Preview> thumbnails = thumbnails(links);

            JSONArray children = new JSONArray();
            for (JSONObject story : stories) {
                children.put(toChild(story, thumbnails));
            }

            JSONObject listing = new JSONObject();

            // Sync reads the offset back out of "after" on the next page request, which is what makes
            // scrolling load more. It reads it as a string, so the end of the list is marked with an
            // empty one rather than a null, which would not read as a string.
            int next = offset + page.size();
            listing.put("after", next < ids.length() ? String.valueOf(next) : "");
            listing.put("before", "");
            listing.put("children", children);

            JSONObject root = new JSONObject();
            root.put("kind", "Listing");
            root.put("data", listing);

            return root.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Fetches each story, preserving the ranking order of {@code ids}. */
    private static List<JSONObject> fetchItems(List<Integer> ids) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(THREADS, Math.max(1, ids.size())));

        try {
            List<Future<JSONObject>> futures = new ArrayList<>();
            for (final int id : ids) {
                futures.add(executor.submit(new Callable<JSONObject>() {
                    @Override
                    public JSONObject call() {
                        return fetchItem(id);
                    }
                }));
            }

            List<JSONObject> items = new ArrayList<>();
            for (Future<JSONObject> future : futures) {
                try {
                    JSONObject item = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    // Deleted and dead stories come back as null and are dropped from the page.
                    if (item != null) {
                        items.add(item);
                    }
                } catch (Exception e) {
                    // A story that fails to load is skipped rather than failing the whole page.
                }
            }
            return items;
        } finally {
            executor.shutdownNow();
        }
    }

    private static JSONObject fetchItem(int id) {
        try {
            String body = get(API + "item/" + id + ".json");
            if (body == null || "null".equals(body.trim())) {
                return null;
            }
            return new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String get(String url) {
        return get(url, null);
    }

    /**
     * Reads [url], signed in with [bearer] when one is given.
     *
     * <p>Reddit answers its api only for a signed-in client, and expects the app's user agent.
     */
    private static String get(String url, String bearer) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);

            if (bearer != null) {
                connection.setRequestProperty("Authorization", "bearer " + bearer);
                connection.setRequestProperty("User-Agent", USER_AGENT);
            }

            InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            in.close();

            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Maps one Hacker News story onto the Reddit post fields Sync reads. */
    private static JSONObject toChild(JSONObject story) throws Exception {
        // Opening a post rewrites its row, so the thumbnail found when the post list was built is
        // reused; without it the row would be rewritten with none.
        return toChild(story, knownThumbnails);
    }

    /**
     * Maps one Hacker News story onto the Reddit post fields Sync reads, with the thumbnail Reddit
     * holds for its link when there is one.
     */
    private static JSONObject toChild(JSONObject story, Map<String, Preview> thumbnails)
            throws Exception {
        int id = story.optInt("id");
        String hnId = String.valueOf(id);

        // Ask HN and similar text posts carry no url. The app reads a post as a self post when its url
        // is the post's own address on Reddit, so that is what one is given - the Hacker News address
        // would be read as a link out, and the post shown as a link rather than its text.
        String url = story.optString("url", "");
        boolean isSelf = url.isEmpty();
        if (isSelf) {
            url = REDDIT_HOSTS[0] + permalink(hnId);
        }

        String text = story.optString("text", "");
        String selftext = text.isEmpty() ? "" : HackerNewsMarkdown.fromHtml(text);

        served.add("t3_" + hnId);

        JSONObject data = new JSONObject();
        data.put("id", hnId);
        data.put("name", "t3_" + hnId);
        data.put("subreddit", SUBREDDIT);
        data.put("subreddit_id", SUBREDDIT_ID);
        data.put("title", story.optString("title", ""));
        data.put("author", story.optString("by", "[deleted]"));
        data.put("created_utc", story.optLong("time"));
        data.put("score", story.optInt("score"));
        data.put("num_comments", story.optInt("descendants"));
        data.put("url", url);
        data.put("domain", isSelf ? "self." + SUBREDDIT : domainOf(url));
        data.put("permalink", permalink(hnId));
        data.put("is_self", isSelf);
        data.put("selftext", selftext);
        data.put("selftext_raw", selftext);
        // What Reddit holds for the same link, where it has anything; a story it has never seen is
        // left without, which the app renders as a post with no image.
        Preview preview = isSelf ? null : thumbnails.get(story.optString("url", ""));
        data.put("thumbnail", isSelf ? "self" : preview == null ? "" : preview.thumbnail);

        // The full size image behind the thumbnail, which is what the app draws a link post's picture
        // from. Without it a story is left with the small thumbnail instead, so it is given in the
        // shape Reddit returns it in and the app already reads.
        //
        // The smaller sizes are given as well as the full size one. The app only takes the picture
        // from a post that offers a choice of sizes, and treats one without as having no picture at
        // all - which leaves the story shown as the media its link is not.
        if (preview != null && !preview.url.isEmpty()) {
            JSONObject source = new JSONObject();
            source.put("url", preview.url);
            source.put("width", preview.width);
            source.put("height", preview.height);

            JSONObject image = new JSONObject();
            image.put("source", source);
            image.put("resolutions", new JSONArray().put(source));

            JSONObject previews = new JSONObject();
            previews.put("images", new JSONArray().put(image));
            data.put("preview", previews);
        }


        // Sync reads these for the post footer and action states. Hacker News exposes no equivalent,
        // so they are set to the values that render a plain, unvoted, unsaved post.
        data.put("likes", JSONObject.NULL);
        data.put("saved", false);
        data.put("hidden", false);
        data.put("visited", false);
        data.put("stickied", false);
        data.put("over_18", false);
        data.put("spoiler", false);
        data.put("locked", true);
        data.put("archived", true);
        data.put("gilded", 0);
        data.put("upvote_ratio", 1);
        data.put("edited", false);
        data.put("distinguished", JSONObject.NULL);
        data.put("link_flair_text", JSONObject.NULL);

        JSONObject child = new JSONObject();
        child.put("kind", "t3");
        child.put("data", data);
        return child;
    }

    /** The post's path, in the form Sync builds comment requests from. */
    public static String permalink(String hnId) {
        return "/r/" + SUBREDDIT + "/comments/" + hnId + "/hn/";
    }

    /** The Hacker News page for a story or comment. */
    private static String discussionUrl(String hnId) {
        return NEWS_HOST + "item?id=" + hnId;
    }

    /**
     * The link to open in place of [url], for a link to a Hacker News discussion.
     *
     * <p>A discussion is served by this patch as a post, so a link to one is turned into that post's
     * address and opened as the post it is, rather than handed to a browser.
     *
     * <p>Only a link to a discussion is taken. A link to an article on the site, or anywhere else, is
     * left to be opened as it always was.
     *
     * @param url the link being opened.
     * @return the link to open in its place, or [url] unchanged for any other link.
     */
    public static String linkFor(String url) {
        if (url == null) {
            return null;
        }

        String id = discussionId(url);
        return id == null ? url : REDDIT_HOSTS[0] + permalink(id);
    }

    /**
     * The item a Hacker News link refers to, or {@code null} if it refers to none.
     *
     * <p>Both the site and its mobile host are matched, with or without a scheme, since a link may be
     * written any of those ways.
     */
    private static String discussionId(String url) {
        String value = url.trim();
        int item = value.indexOf("news.ycombinator.com/item?id=");
        if (item == -1) {
            return null;
        }

        String id = value.substring(item + "news.ycombinator.com/item?id=".length());

        // The id runs to the end of the link, or to whatever follows it.
        for (int i = 0; i < id.length(); i++) {
            char character = id.charAt(i);
            if (character < '0' || character > '9') {
                id = id.substring(0, i);
                break;
            }
        }

        return id.isEmpty() ? null : id;
    }

    /**
     * Corrects copied text, which may be a link to Hacker News content.
     *
     * <p>Copying is also used for text that is not a link, such as a comment's body, which is returned
     * unchanged by [shareUrl].
     */
    public static CharSequence shareText(CharSequence text) {
        return text == null ? null : shareUrl(text.toString());
    }

    /**
     * Corrects a link built for Hacker News content before it is shared or copied.
     *
     * <p>Links are built from the post's subreddit and id, which for this feed produce a reddit.com
     * address that does not exist. The id in such a link is the Hacker News one, so the link is
     * rewritten to the story or comment it actually refers to.
     *
     * <p>Links to real Reddit content, and links that are already elsewhere, are returned unchanged.
     *
     * @param url the link about to be shared or copied.
     */
    public static String shareUrl(String url) {
        if (url == null) {
            return null;
        }

        try {
            // Sharing a comment builds reddit.com followed by the permalink, which for Hacker News
            // content is already the Hacker News page. Only a link built that way is corrected, so
            // that copied text merely containing a Hacker News link is left as it is.
            for (String prefix : REDDIT_HOSTS) {
                if (url.startsWith(prefix + NEWS_HOST)) {
                    return url.substring(prefix.length());
                }
            }

            // Sharing the feed itself builds a reddit.com address from the name it is listed under,
            // which is not a feed Reddit has. The site the feed is read from is shared instead.
            for (String prefix : REDDIT_HOSTS) {
                if (url.trim().equals(prefix + "/r/" + FEED)) {
                    return NEWS_HOST;
                }
            }

            // Sharing a post builds reddit.com/r/<subreddit>/comments/<id>/...
            String marker = "/r/" + SUBREDDIT + "/comments/";

            int start = -1;
            for (String prefix : REDDIT_HOSTS) {
                if (url.startsWith(prefix + marker)) {
                    start = prefix.length();
                    break;
                }
            }
            if (start == -1) {
                return url;
            }

            String id = url.substring(start + marker.length());
            int end = id.indexOf('/');
            if (end != -1) {
                id = id.substring(0, end);
            }

            // Recognised by the id rather than by the subreddit the link names, because Reddit has a
            // subreddit of that name whose posts must still be shared as Reddit links.
            return isHackerNewsId("t3_" + id) ? discussionUrl(id) : url;
        } catch (Exception e) {
            return url;
        }
    }

    private static String domainOf(String url) {
        try {
            String host = new URL(url).getHost();
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return SUBREDDIT;
        }
    }

    /**
     * Reads the page offset out of the cursor Sync passes back, defaulting to the first page.
     *
     * <p>The cursor is the offset into the story list, as put there by [buildListing]. An empty cursor
     * marks the end of the list, and anything unrecognised starts again from the beginning.
     */
    private static int parseOffset(String after) {
        if (after == null) {
            return 0;
        }

        try {
            return Math.max(0, Integer.parseInt(after.trim()));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Whether the post the markers are currently being built for is a story.
     *
     * <p>Noted from in front of each test, because the app reads a post and one of its answers through
     * the same register and so has no post left to hand once the test has run. The line is built for
     * one post at a time on the main thread, so a single value is enough.
     */
    private static volatile boolean markingStory;

    /** Notes whether the post a marker is about to be built for is a story. */
    public static void noteMarkedPost(Object post) {
        markingStory = isHackerNewsPost(post);
    }

    /**
     * Whether a post is marked as archived or locked in the line under its title.
     *
     * <p>Stories are archived and locked so that the app does not offer to vote on or reply to them,
     * which Hacker News content cannot accept through Reddit. Those are means rather than something
     * true of the story, so the markers they would otherwise add are left off.
     *
     * @param marked whether the app was going to mark the post.
     * @return whether the post is marked.
     */
    public static boolean showsMarker(boolean marked) {
        return marked && !markingStory;
    }


    /**
     * The notice shown above a post's comments, naming Hacker News in place of the archiving a story
     * is served with.
     *
     * <p>A story is archived so that the app does not offer to vote on or reply to it through Reddit,
     * which it cannot do. Saying so as "archived" describes the means rather than the reason, so the
     * notice names where the post is from instead.
     *
     * @param notice the notice the app was going to show.
     * @param post the post the notice is being shown for.
     * @return the notice to show.
     */
    public static String noticeFor(String notice, Object post) {
        if (notice == null || !isHackerNewsPost(post)) {
            return notice;
        }

        return notice.replace("an archived post", "a " + FEED_NAME + " post");
    }

    /** Whether [post] is a story served from Hacker News. */
    private static boolean isHackerNewsPost(Object post) {
        try {
            // The post's class is obfuscated, so its id is read by the name the getter keeps rather
            // than by calling it directly.
            return isHackerNewsId("t3_" + post.getClass().getMethod("U").invoke(post));
        } catch (Exception e) {
            // A post that cannot be read is left as the app had it.
            return false;
        }
    }
}
