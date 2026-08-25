package app.morphe.extension.syncforreddit;

import android.util.Log;

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

/**
 * Serves a site that is not Reddit as if it were a subreddit.
 *
 * <p>Sync builds every screen from Reddit's listing JSON, so rather than teaching the UI about a
 * second content source, a site is translated into the listing shape Sync already parses. Every
 * screen downstream of the parser - the post list, the database and the post view - then works
 * without being patched.
 *
 * <p>This holds what is true of any such feed: the bookkeeping that tells the feed's content apart
 * from Reddit's, the listing envelopes responses are built into, the correction of links built for
 * content Reddit does not have, and the reading of pages over http. What a particular site is - where
 * its posts come from, how they are shaped, what it calls its sorts - is left to a subclass.
 *
 * <p>The patch injects calls to the static methods here, which pass to the feed the patch was applied
 * for. One feed is served per patched app, so it is held statically rather than looked up per call.
 *
 * @noinspection unused
 */
public abstract class CustomFeedExtension {
    /** The feed the patch was applied for, which the static entry points below pass to. */
    private static volatile CustomFeedExtension active;

    /**
     * Names the feed this app serves.
     *
     * <p>The patch calls the entry points below rather than the subclass, so nothing would otherwise
     * load it. The patch writes the subclass's name here instead, and it is constructed on first use.
     *
     * <p>Replaced by the patch with the feed it was applied for.
     */
    private static String feedClass() {
        return null;
    }

    /**
     * The feed this app serves, or {@code null} when the patch named none.
     *
     * <p>Constructed once, on whichever thread reaches an entry point first. Constructing it twice
     * would be harmless - a feed holds no state of its own, the bookkeeping being static - so the
     * cost of a race is a second instance rather than anything the app could notice.
     */
    private static CustomFeedExtension feed() {
        CustomFeedExtension feed = active;
        if (feed != null) {
            return feed;
        }

        String name = feedClass();
        if (name == null) {
            return null;
        }

        try {
            feed = (CustomFeedExtension) Class.forName(name).newInstance();
            active = feed;
            return feed;
        } catch (Exception e) {
            // A feed that cannot be loaded leaves every entry point below passing its argument
            // through, which is the app behaving as though the patch had not been applied.
            return null;
        }
    }

    // The site's own identity, which everything below is named and recognised by.

    /** The display name of the custom feed, as it is listed and shown in the toolbar. */
    protected abstract String feedName();

    /**
     * The subreddit each post reports itself as belonging to.
     *
     * <p>Posts within a custom feed still carry a subreddit, which Sync shows on each post and uses
     * to group them, so posts are attributed to a subreddit of the site's name rather than to the
     * feed itself.
     */
    protected abstract String subreddit();

    /** The site posts are read on, as opposed to the API they may be fetched from. */
    protected abstract String siteUrl();

    /**
     * The site's own icon.
     *
     * <p>Icons are shown from a url rather than from a bundled image, so the site's own is used.
     */
    protected abstract String iconUrl();

    /** The custom feed that serves the site instead of Reddit. Sync prefixes feeds with "multi_". */
    protected final String feedKey() {
        return "multi_" + feedName();
    }

    /** The prefixes the app puts in front of a permalink when building a link to share. */
    protected static final String[] REDDIT_HOSTS = { "https://www.reddit.com", "https://reddit.com" };

    /**
     * Sync only uses a subreddit id to group posts, so a fixed value derived from the name stands in
     * for a site that has no equivalent.
     */
    protected final String subredditId() {
        return "t5_" + subreddit();
    }

    protected static final int TIMEOUT_MS = 10_000;

    /** Matches the number of stories a site typically shows on a page. */
    protected static final int PAGE_SIZE = 30;

    /**
     * How many requests are made at once when a page needs one per item.
     *
     * <p>One per item of a page, so that a page's stories, and the thumbnails for them, each load in
     * a single round of requests rather than leaving the last few waiting on the rest.
     */
    protected static final int THREADS = PAGE_SIZE;

    // The ids served, which is what tells the feed's content apart from Reddit's.

    private static final int MAX_REMEMBERED_IDS = 2000;

    /**
     * The posts and comments served from the site, as Reddit ids.
     *
     * <p>A site's ids may look like Reddit's, so the ids actually served are remembered rather than
     * guessed at from their shape. Bounded so that a long browsing session cannot grow it without
     * limit; the oldest ids are dropped, which at worst lets a write action through for a post
     * scrolled far past.
     */
    protected static final Set<String> served =
            Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_REMEMBERED_IDS;
                }
            }));

    /**
     * Whether a fullname such as {@code t3_49294997} names this feed's content.
     *
     * <p>The ids served are only known for as long as the app has been running, because posts are
     * rebuilt from Sync's database rather than from a response. So an id that was not served this
     * session is judged by its shape instead, which each feed decides for itself.
     */
    protected boolean isFeedId(String id) {
        if (id == null) {
            return false;
        }

        if (served.contains(id)) {
            return true;
        }

        int separator = id.indexOf('_');
        return separator != -1 && isFeedItemId(id.substring(separator + 1));
    }

    /**
     * Whether a bare id looks like one of the site's, for an id that was not served this session.
     *
     * <p>Judging by shape can only ever be wrong in the safe direction: mistaking a Reddit id for one
     * of the site's fails an action the user can retry, whereas the reverse would send a vote to
     * Reddit for a post that does not exist there.
     */
    protected abstract boolean isFeedItemId(String id);

    /**
     * Whether a name refers to this feed, in either of the forms it is passed around as.
     *
     * <p>The subreddit a post reports is deliberately not one of them. Reddit may have a subreddit of
     * that name, and matching it would give the feed's sorts, rules, wiki and icon to that subreddit,
     * which has nothing to do with the feed.
     */
    protected boolean isFeed(String name) {
        if (name == null) {
            return false;
        }

        // A name read back out of an address carries its space escaped, since a Markdown link would
        // otherwise end at it.
        String value = name.trim().replace("%20", " ");
        return value.equalsIgnoreCase(feedKey()) || value.equalsIgnoreCase(feedName());
    }

    // The requests the app makes, pointed at the site in place of Reddit.

    /**
     * The page offset currently being requested.
     *
     * <p>The response is identified and translated without the request url to hand, so the offset is
     * carried from the url being built to the response being parsed. Volley dispatches one posts
     * request at a time, so a single value is enough.
     */
    protected static volatile int pendingOffset;

    /** The post whose comments are being loaded, carried the same way as the page offset. */
    protected static volatile String pendingStory;

    /** Whether the page being loaded belongs to this feed, and if so which page it is. */
    protected static volatile boolean pendingGuidelines;

    protected static volatile boolean pendingFaq;

    protected static volatile boolean pendingFeedSubreddits;

    /** The feed currently being read, so that the pages shown can follow it. */
    protected static volatile String currentSort;

    /**
     * The url Sync requests for a page of the feed.
     *
     * @param after the pagination cursor, which is the offset into the post list.
     * @param sort the feed chosen from the sort dialog.
     */
    protected abstract String postsUrl(String after, String sort);

    /**
     * The url Sync requests for a post's comments.
     *
     * @return the url to request, or {@code null} to leave the request as it is.
     */
    protected abstract String commentsUrl(String id);

    /** The feed shown when none has been chosen, whatever sort the app defaults its own posts to. */
    protected abstract String defaultSort();

    /** Whether a sort is one of the feeds this patch offers in place of Reddit's. */
    protected abstract boolean isKnownSort(String sort);

    /**
     * The feeds offered in place of Reddit's sorts, in the order they are listed.
     *
     * <p>Named the same way the sort dialog names them, since a sort picked here is read back as one
     * of those.
     */
    protected abstract String[] sortLabels();

    /**
     * The icon shown on the chip naming the current feed.
     *
     * @return the drawable to show, or {@code 0} for a sort the app already has an icon for.
     */
    protected int sortIcon(String sort) {
        return 0;
    }

    /**
     * The address to read the feed's rules from, which is a page the site holds in place of the
     * Reddit rules that do not exist for it.
     *
     * @return the address to request, or {@code null} for a feed that offers none.
     */
    protected String guidelinesUrl() {
        return null;
    }

    /**
     * The address to read the feed's wiki from.
     *
     * @return the address to request, or {@code null} for a feed that offers none.
     */
    protected String faqUrl() {
        return null;
    }

    /** The title the feed's wiki is shown under, in place of the feed name and page it is read from. */
    protected String wikiTitle() {
        return feedName() + " FAQ";
    }

    /**
     * The address of one of the site's pages, by the name a link to it was rewritten under.
     *
     * @return the address to request, or {@code null} for a page the feed does not serve.
     */
    protected String wikiPageUrl(String page) {
        return null;
    }

    /** The title to show for one of those pages, or {@code null} for the wiki's usual title. */
    protected String wikiPageTitle(String page) {
        return null;
    }

    /**
     * The address to share for one of those pages, which is where it is read on the site.
     *
     * <p>Defaults to the address the page is requested from, which for a page served whole is the same
     * thing. A page holding one section of another names that section, so it is shared as the part of
     * the page it actually is.
     */
    protected String wikiPageShareUrl(String page) {
        return wikiPageUrl(page);
    }

    // Translating what the site answers with into what Sync parses.

    /** Turns a page of the site's posts into the listing Sync parses. */
    protected abstract byte[] toListing(byte[] data, int offset);

    /** Turns a post and its comments into the comment tree Sync parses. */
    protected abstract byte[] toComments(byte[] data);

    /** Whether a response is one of the site's rather than one of Reddit's. */
    protected abstract boolean isFeedResponse(byte[] data);

    /** Turns the site's rules page into the rules Sync shows, for a feed that offers them. */
    protected byte[] toRules(byte[] data) {
        return data;
    }

    /** Turns the site's wiki page into the wiki Sync shows, for a feed that offers one. */
    protected byte[] toWiki(byte[] data) {
        return data;
    }

    /** Wraps children in the listing envelope Sync unwraps. */
    protected static JSONObject listing(JSONArray children) throws Exception {
        JSONObject data = new JSONObject();
        data.put("after", JSONObject.NULL);
        data.put("before", JSONObject.NULL);
        data.put("children", children);

        JSONObject listing = new JSONObject();
        listing.put("kind", "Listing");
        listing.put("data", data);
        return listing;
    }

    // Links, which are built from a post's subreddit and id and so name an address Reddit does not
    // have.

    /** The post's path, in the form Sync builds comment requests from. */
    protected final String permalink(String id) {
        return "/r/" + subreddit() + "/comments/" + id + "/" + linkSlug() + "/";
    }

    /** The path of a single comment within its post, in the form the app reads a thread from. */
    protected final String threadPermalink(String storyId, String commentId) {
        return "/r/" + subreddit() + "/comments/" + storyId + "/" + linkSlug() + "/" + commentId;
    }

    /**
     * The word standing in for the title Reddit writes into a permalink.
     *
     * <p>Anything will do, since the app reads only the id out of a link, so a short name of the feed
     * is used.
     */
    protected String linkSlug() {
        return "feed";
    }

    /** The site's own page for a post or comment. */
    protected abstract String itemUrl(String id);

    /**
     * The item one of the site's links refers to, or {@code null} if it refers to none.
     *
     * @param url the link being followed or shown.
     */
    protected abstract String itemIdIn(String url);

    /**
     * The link to open in place of one of the site's, which is served by this patch as a post.
     *
     * <p>A link naming a post that has been served is opened as that post. Anything else is named as
     * both the thread and the comment to show, which is what opens a comment at the thread it belongs
     * to - what an id names is otherwise only known by asking the site, which cannot be done here,
     * since this runs as the link is followed, on the thread the app draws on.
     */
    /**
     * How long a link waits for the site to say what it names, before it is opened as a post.
     *
     * <p>This is the pause between tapping a link and the thread opening, so it is kept to about what
     * a single request takes. Overrunning it leaves the link opening as a post, which the comments
     * response then corrects, rather than leaving the app looking stuck.
     */
    private static final int RESOLVE_TIMEOUT_MS = 2_500;

    /**
     * Whether an id names a comment rather than a post.
     *
     * <p>The site numbers posts and comments alike and writes links to both the same way, so the two
     * are told apart by what the item says it is. What has been served is known already; anything
     * else is asked about, which is one request.
     *
     * <p>This runs as a link is followed, on the thread the app draws on, so the asking is done on
     * another and waited for briefly. An answer that does not arrive in time leaves the link opening
     * as a post, which is corrected once the comments are fetched.
     */
    private boolean isComment(String id) {
        if (served.contains("t1_" + id)) {
            return true;
        }

        if (served.contains("t3_" + id)) {
            return false;
        }

        final String item = id;
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<Boolean> asked = executor.submit(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return namesComment(item);
                }
            });

            Boolean comment = asked.get(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (comment == null) {
                return false;
            }

            // Remembered, so that the same link is not asked about twice.
            served.add((comment ? "t1_" : "t3_") + id);
            return comment;
        } catch (Exception e) {
            // Opened as a post, which the comments response corrects if it turns out to be a comment.
            return false;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Whether the site says an id names a comment, or {@code null} where it could not be read.
     *
     * <p>Asked of the site rather than guessed at from the id, which says nothing about what it is.
     */
    protected abstract Boolean namesComment(String id);

    protected String openLink(String url) {
        if (url == null) {
            return null;
        }

        // One of the site's own pages is opened as the wiki page it is served as, so that following a
        // link to it stays in the app rather than handing the address to a browser.
        String page = wikiPageOpened(url);
        if (page != null) {
            return page;
        }

        String id = itemIdIn(url);
        if (id == null) {
            return url;
        }

        // A story is opened as the post it is. A comment is named as both the thread and the comment
        // to show, which opens it at the thread it belongs to and is what the app shows its "single
        // thread" notice for - so a story must never be named that way.
        return REDDIT_HOSTS[0] + (isComment(id)
                ? threadPermalink(id, id)
                : permalink(id));
    }

    /**
     * Corrects a link built for this feed's content before it is shared or copied.
     *
     * <p>Links are built from the post's subreddit and id, which for this feed produce a reddit.com
     * address that does not exist. The id in such a link is the site's own, so the link is rewritten
     * to the post or comment it actually refers to.
     *
     * <p>Links to real Reddit content, and links that are already elsewhere, are returned unchanged.
     */
    protected String correctUrl(String url) {
        if (url == null) {
            return null;
        }

        try {
            // Sharing a comment builds reddit.com followed by the permalink, which for this feed is
            // already the site's own page. Only a link built that way is corrected, so that copied
            // text merely containing one of the site's links is left as it is.
            for (String prefix : REDDIT_HOSTS) {
                if (url.startsWith(prefix + siteUrl())) {
                    return url.substring(prefix.length());
                }
            }

            // Sharing one of the site's pages builds a wiki address of the feed, which is not a page
            // Reddit has. The page on the site is shared instead.
            String page = wikiPageShared(url);
            if (page != null) {
                return page;
            }

            // Sharing the feed itself builds a reddit.com address from the name it is listed under,
            // which is not a feed Reddit has. The site the feed is read from is shared instead.
            for (String prefix : REDDIT_HOSTS) {
                if (url.trim().equals(prefix + "/r/" + feedKey())) {
                    return siteUrl();
                }
            }

            // Sharing a post builds reddit.com/r/<subreddit>/comments/<id>/...
            String marker = "/r/" + subreddit() + "/comments/";

            int start = -1;
            for (String prefix : REDDIT_HOSTS) {
                if (url.startsWith(prefix + marker)) {
                    start = prefix.length();
                    break;
                }
            }

            // The path is also written without a host, as the permalink a post is stored with.
            if (start == -1 && url.startsWith(marker)) {
                start = 0;
            }
            if (start == -1) {
                return url;
            }

            String id = url.substring(start + marker.length());
            int end = id.indexOf('/');
            if (end != -1) {
                id = id.substring(0, end);
            }

            // Recognised by the id rather than by the subreddit the link names, because Reddit may
            // have a subreddit of that name whose posts must still be shared as Reddit links.
            return isFeedId("t3_" + id) ? itemUrl(id) : url;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Rewrites the addresses this patch serves posts under, wherever they appear in text.
     *
     * <p>The sheets offering a post or comment's link show it as text rather than handing it over as
     * a link, so an address inside a longer piece of text is corrected as readily as one on its own.
     */
    private String correctEmbeddedUrls(String text) {
        String marker = "/r/" + subreddit() + "/comments/";
        StringBuilder rewritten = new StringBuilder();

        int from = 0;
        while (true) {
            int at = text.indexOf(marker, from);
            if (at == -1) {
                break;
            }

            // The address starts at whichever Reddit host precedes the path, where one does.
            int start = at;
            for (String prefix : REDDIT_HOSTS) {
                if (at >= prefix.length() && text.startsWith(prefix, at - prefix.length())) {
                    start = at - prefix.length();
                    break;
                }
            }

            // The address runs to the first space or line break after it.
            int end = at + marker.length();
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                end++;
            }

            String replacement = correctUrl(text.substring(start, end));
            rewritten.append(text, from, start).append(replacement);
            from = end;
        }

        return from == 0 ? text : rewritten.append(text.substring(from)).toString();
    }

    // Write actions, which Reddit cannot carry out for content it does not hold.

    /**
     * The request parameters naming the post or comment a write action acts on.
     *
     * <p>Voting and saving send it as "id"; replying sends it as "thing_id".
     */
    private static final String[] ID_PARAMS = { "id", "thing_id" };

    /**
     * The post a marker or notice is currently being built for, noted from in front of each test.
     *
     * <p>The app reads a post and one of its answers through the same register and so has no post
     * left to hand once the test has run. The line is built for one post at a time on the main
     * thread, so a single value is enough.
     */
    private static volatile boolean markingFeedPost;

    /** Whether a post is one served from the site. */
    protected boolean isFeedPost(Object post) {
        try {
            // The post's class is obfuscated, so its id is read by the name the getter keeps rather
            // than by calling it directly.
            return isFeedId("t3_" + post.getClass().getMethod("U").invoke(post));
        } catch (Exception e) {
            // A post that cannot be read is left as the app had it.
            return false;
        }
    }

    /**
     * The post whose name is shown on a holder or screen.
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

    // The account a feed's writes are made as, which is the feed's own business - the entry points
    // here are what the patch injects calls to, and each passes to the feed.

    /**
     * Shows one of the account's details on the row listing it, and asks for it again when tapped.
     *
     * @param row the row listing the setting, which the patch finds on the screen.
     * @param key the setting the row is listing.
     */
    public static void bindAccountSetting(Object row, String key) {
        CustomFeedExtension feed = feed();
        if (feed != null) {
            feed.bindSetting(row, key);
        }
    }

    /** Shows and asks for one of the account's details, for the feeds that keep one. */
    protected void bindSetting(Object row, String key) {
    }

    /**
     * Notes whether the save being built is a save or the undoing of one.
     *
     * <p>The parameters a save is made with name only the item, so which way round it is is taken from
     * the address as it is built, in the same way as the page offset for a listing.
     */
    public static void noteSave(boolean unsave) {
        CustomFeedExtension feed = feed();
        if (feed != null) {
            feed.notedSave(!unsave);
        }
    }

    /** Notes which way round the save being built is, for the feeds that make one. */
    protected void notedSave(boolean saved) {
    }

    /**
     * Forgets what the feed read about the account, so that it is read again.
     *
     * <p>Called when the feed is opened or refreshed, so that something done to the account elsewhere
     * is picked up rather than waiting for the app to be restarted.
     */
    protected void refreshAccountState() {
    }

    /** What came of a write made on the site, which the app reports to the user. */
    public enum WriteResult {
        /** The site took it. */
        DONE,
        /** No account has been given to act as. */
        NO_ACCOUNT,
        /** The site refused it, which for a write it otherwise allows means the details are wrong. */
        REFUSED,
        /** The site allows this write only for some accounts, such as downvoting. */
        NOT_ALLOWED,
        /** The site could not be reached, or answered in a way that could not be read. */
        FAILED,
        /** The feed does not offer this at all, having nothing to make the write as. */
        UNSUPPORTED;

        /** What to tell the user, or {@code null} where there is nothing to say. */
        public String message(String feedName) {
            switch (this) {
                case NO_ACCOUNT:
                    return "Add your " + feedName + " account in Settings to do that";
                case REFUSED:
                    return feedName + " refused that - check your username and password";
                case NOT_ALLOWED:
                    return feedName + " does not allow that from this account yet";
                case FAILED:
                    return "Could not reach " + feedName;
                case UNSUPPORTED:
                    return "Not supported on " + feedName;
                default:
                    return null;
            }
        }
    }

    // Reading pages, and what Reddit holds for the same links.

    /** Reads a url, or {@code null} if it cannot be read. */
    protected static String get(String url) {
        return get(url, null);
    }

    /**
     * Reads a url, signed in with a bearer token when one is given.
     *
     * <p>Reddit answers its api only for a signed-in client, and expects the app's user agent.
     */
    protected static String get(String url, String bearer) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);

            if (bearer != null) {
                connection.setRequestProperty("Authorization", "bearer " + bearer);
                connection.setRequestProperty("User-Agent", userAgent);
            }

            int status = connection.getResponseCode();
            if (status >= 400) {
                Log.w(LOG_TAG, "HTTP " + status + " for " + url);
                connection.disconnect();
                return null;
            }

            InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            // A connection read to the end is closed but not disconnected, so the next request to the
            // same host reuses it instead of paying for a new TLS handshake. A thread costs one request
            // per comment, which makes that handshake most of the time it takes.
            in.close();

            return out.toString("UTF-8");
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to read " + url, e);
            // A connection left part way through a response cannot be reused, so it is closed.
            if (connection != null) {
                connection.disconnect();
            }
            return null;
        }
    }

    /**
     * The answer to a form sent to a url, which the site's writes are made with.
     *
     * <p>Sent as a form rather than as json, which is what the site's own pages post, and answered
     * without being followed: the site answers a write with a redirect, and where it went is what
     * says whether the write was taken.
     *
     * @return where the site redirected to, or {@code null} if the request could not be made.
     */
    /**
     * Runs a call for each of a list of items at once, dropping any that fail.
     *
     * <p>A site that serves one item per request costs one request per post or comment, so a page is
     * fetched in parallel rather than one after another.
     */
    protected static <T, R> List<R> parallel(List<T> items, Fetcher<T, R> fetcher) {
        if (items.isEmpty()) {
            return new ArrayList<>();
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(THREADS, items.size()));

        try {
            List<Future<R>> futures = new ArrayList<>();
            for (final T item : items) {
                futures.add(executor.submit(new Callable<R>() {
                    @Override
                    public R call() {
                        return fetcher.fetch(item);
                    }
                }));
            }

            List<R> results = new ArrayList<>();
            for (Future<R> future : futures) {
                try {
                    R result = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    // An item that comes back as nothing, such as a deleted post, is dropped.
                    if (result != null) {
                        results.add(result);
                    }
                } catch (Exception e) {
                    // An item that fails to load is skipped rather than failing the whole page.
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Runs a call for each of a list of items at once, keeping only those that finish within a
     * shared time budget.
     *
     * <p>For lookups that only add to a page, so that the page is not held back by the slowest of
     * them. Calls still running when the budget is spent are abandoned, and their items left out.
     */
    protected static <T, R> List<R> parallel(List<T> items, Fetcher<T, R> fetcher, long budgetMs) {
        if (items.isEmpty()) {
            return new ArrayList<>();
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(THREADS, items.size()));

        try {
            List<Future<R>> futures = new ArrayList<>();
            for (final T item : items) {
                futures.add(executor.submit(new Callable<R>() {
                    @Override
                    public R call() {
                        return fetcher.fetch(item);
                    }
                }));
            }

            long deadline = System.currentTimeMillis() + budgetMs;
            List<R> results = new ArrayList<>();
            for (Future<R> future : futures) {
                long remaining = deadline - System.currentTimeMillis();
                try {
                    R result = remaining > 0
                            ? future.get(remaining, TimeUnit.MILLISECONDS)
                            // Out of time, but anything already finished is still taken.
                            : future.isDone() ? future.get() : null;
                    if (result != null) {
                        results.add(result);
                    }
                } catch (Exception e) {
                    // An item that fails or runs out of time is left out rather than failing the page.
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /** Fetches one item, for use with {@link #parallel}. */
    public interface Fetcher<T, R> {
        R fetch(T item);
    }

    /**
     * Reddit's search for the posts linking to a url, which is where thumbnails come from.
     *
     * <p>Reddit's index of submitted links, {@code /api/info?url=}, is closed to third party clients
     * and answers with an html 403 page. Search still finds the posts a link was submitted in, in the
     * same listing shape.
     */
    private static final String SEARCH_URL =
            "https://oauth.reddit.com/search?type=link&sort=relevance&limit=10&raw_json=1&q=url%3A";

    /**
     * How long a page waits for its thumbnails.
     *
     * <p>Thumbnails only add to a page, so it is shown without the ones Reddit is slow to answer for
     * rather than held back by them. Those are looked up again the next time the page is loaded.
     */
    private static final long THUMBNAIL_BUDGET_MS = 3_000;

    /** Stands for a link Reddit was asked about and holds no thumbnail for. */
    private static final Preview NO_PREVIEW = new Preview("", "", 0, 0);

    /** Tag the feed's requests are logged under. */
    protected static final String LOG_TAG = "MorpheCustomFeed";

    /**
     * The user agent the app identifies itself to Reddit with.
     *
     * <p>Read from the app rather than written out, since the token is only accepted alongside the
     * user agent of the client it was issued to, which a patch spoofing the client changes. The
     * original is kept until the app has reported its own.
     */
    private static volatile String userAgent =
            "android:com.laurencedawson.reddit_sync:vv23.06.30-13:39 (by /u/ljdawson)";

    /** The token the current posts request was sent with, used to ask Reddit about the same links. */
    private static volatile String token;

    /**
     * What Reddit holds for a link: the small thumbnail, and the full size preview behind it.
     *
     * <p>The app shows a post as a large card when it has a preview, and as a small thumbnail when it
     * only has that, so both are taken.
     */
    protected static final class Preview {
        public final String thumbnail;
        public final String url;
        public final int width;
        public final int height;

        Preview(String thumbnail, String url, int width, int height) {
            this.thumbnail = thumbnail;
            this.url = url;
            this.width = width;
            this.height = height;
        }
    }

    /** A thumbnail together with the link it was found for. */
    private static final class Found {
        final String url;
        final Preview preview;

        Found(String url, Preview preview) {
            this.url = url;
            this.preview = preview;
        }
    }

    /**
     * The thumbnail found for each link, kept for as long as the app is running.
     *
     * <p>Opening a post rewrites its row from the comments response, so the thumbnail found when the
     * post list was built is reused rather than looked up again. Bounded like the ids served, since a
     * long session would otherwise grow it without limit.
     */
    protected static final Map<String, Preview> knownThumbnails =
            Collections.synchronizedMap(new LinkedHashMap<String, Preview>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Preview> eldest) {
                    return size() > MAX_REMEMBERED_IDS;
                }
            });

    /**
     * The thumbnails Reddit holds for a page's links, by the url each belongs to.
     *
     * <p>A site that serves no images of its own can still show one for a link that has also been
     * submitted to Reddit, which is the same request the app makes for "Other Discussions". Links
     * Reddit has never seen simply have no entry, and are left without a thumbnail.
     */
    protected static Map<String, Preview> thumbnails(List<String> urls) {
        Map<String, Preview> found = new LinkedHashMap<>();

        if (urls.isEmpty()) {
            return found;
        }
        if (token == null) {
            Log.w(LOG_TAG, "No token yet, so no thumbnails are looked up");
            return found;
        }

        // Links already asked about this session, with or without a thumbnail, are not asked again.
        List<String> unknown = new ArrayList<>();
        for (String url : urls) {
            Preview known = knownThumbnails.get(url);
            if (known == null) {
                unknown.add(url);
            } else if (known != NO_PREVIEW) {
                found.put(url, known);
            }
        }

        // Reddit answers this for one link at a time, so the page's links are looked up in parallel
        // rather than one after another. Each answer carries the link it belongs to, so that a link
        // Reddit holds nothing for simply drops out without shifting the rest.
        long start = System.currentTimeMillis();
        List<Found> answers = parallel(unknown, new Fetcher<String, Found>() {
            @Override
            public Found fetch(String url) {
                Preview preview = previewFor(url);
                return preview == null ? null : new Found(url, preview);
            }
        }, THUMBNAIL_BUDGET_MS);

        int hits = 0;
        for (Found answer : answers) {
            // Only a definite answer is remembered; a lookup that failed or ran out of time is asked
            // again next time.
            knownThumbnails.put(answer.url, answer.preview);
            if (answer.preview != NO_PREVIEW) {
                found.put(answer.url, answer.preview);
                hits++;
            }
        }
        Log.d(LOG_TAG, "Thumbnails: " + hits + " found, " + (answers.size() - hits) + " none, "
                + (unknown.size() - answers.size()) + " unanswered of " + unknown.size()
                + " looked up (" + (urls.size() - unknown.size()) + " cached) in "
                + (System.currentTimeMillis() - start) + " ms");
        return found;
    }

    /**
     * Decodes the html entities Reddit escapes its urls with.
     *
     * <p>The preview addresses Reddit answers with are escaped for html, and are rejected as they
     * stand.
     */
    private static String unescape(String text) {
        return text
                .replace("&#x27;", "'")
                .replace("&#x2F;", "/")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                // Ampersand last, so an escaped entity is not decoded twice.
                .replace("&amp;", "&");
    }

    /**
     * Whether two links are to the same page, ignoring the differences Reddit and the site may
     * disagree on: the scheme, a leading "www.", a trailing slash and the case of the host.
     */
    private static boolean sameLink(String a, String b) {
        return normalizeLink(a).equals(normalizeLink(b));
    }

    private static String normalizeLink(String link) {
        String result = unescape(link.trim());
        int scheme = result.indexOf("://");
        if (scheme != -1) {
            result = result.substring(scheme + 3);
        }
        int slash = result.indexOf('/');
        String host = (slash == -1 ? result : result.substring(0, slash)).toLowerCase();
        String rest = slash == -1 ? "" : result.substring(slash);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        while (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        return host + rest;
    }

    /**
     * The thumbnail Reddit holds for a single link, {@link #NO_PREVIEW} if it holds none, or
     * {@code null} if Reddit could not be asked.
     */
    private static Preview previewFor(String url) {
        try {
            String body = get(SEARCH_URL + URLEncoder.encode(url, "UTF-8"), token);
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

                // Search also answers with posts whose links only resemble this one, which would
                // give the story another page's picture.
                if (!sameLink(url, post.optString("url", ""))) {
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
                            previewUrl = unescape(source.optString("url", ""));
                            width = source.optInt("width");
                            height = source.optInt("height");
                        }
                    }
                }

                return new Preview(thumbnail, previewUrl, width, height);
            }
            return NO_PREVIEW;
        } catch (Exception e) {
            // Left unanswered, so the link is asked about again next time.
        }
        return null;
    }

    /**
     * Adds the preview Reddit holds for a post's link, where it holds one.
     *
     * <p>The full size image behind the thumbnail is what the app draws a link post's picture from.
     * Without it a post is left with the small thumbnail instead, so it is given in the shape Reddit
     * returns it in and the app already reads.
     *
     * <p>The smaller sizes are given as well as the full size one. The app only takes the picture
     * from a post that offers a choice of sizes, and treats one without as having no picture at all -
     * which leaves the post shown as the media its link is not.
     */
    protected static void addPreview(JSONObject data, Preview preview) throws Exception {
        if (preview == null || preview.url.isEmpty()) {
            return;
        }

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

    /** The host part of a link, as the app shows it under a post's title. */
    protected final String domainOf(String url) {
        try {
            String host = new URL(url).getHost();
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return subreddit();
        }
    }

    /**
     * Reads the page offset out of the cursor Sync passes back, defaulting to the first page.
     *
     * <p>The cursor is the offset into the post list, as put there when the listing was built. An
     * empty cursor marks the end of the list, and anything unrecognised starts again from the
     * beginning.
     */
    protected static int parseOffset(String after) {
        if (after == null) {
            return 0;
        }

        try {
            return Math.max(0, Integer.parseInt(after.trim()));
        } catch (Exception e) {
            return 0;
        }
    }

    // The entry points the patch injects calls to. Each passes to the registered feed, and leaves
    // what it was given untouched when no feed is registered.

    /** The url to request for a page of the feed, or {@code null} for any other feed. */
    public static String buildUrl(String after, String sort) {
        CustomFeedExtension feed = feed();
        if (feed == null) {
            return null;
        }

        pendingOffset = parseOffset(after);

        // A feed's page is not a profile's, so anything a profile left behind is cleared rather than
        // left to claim this response.
        pendingProfile = null;
        pendingSection = null;

        // Anything the feed reads about the account rather than about a post is read again when the
        // feed is opened or refreshed, which is the first page rather than one scrolled onto.
        if (pendingOffset == 0) {
            feed.refreshAccountState();
        }

        return feed.postsUrl(after, sort);
    }

    // Profiles, which the site's own users are shown as.

    /**
     * The users whose names have been seen on the site's own content.
     *
     * <p>The site and Reddit both have users, and the same name may belong to someone on either, so a
     * name is only read as one of the site's when it was seen on the site's own content. Names are
     * noted as that content is served, and the note is what tells the two apart when one is opened.
     *
     * <p>Bounded like the ids served, so a long session cannot grow it without limit.
     */
    protected static final Set<String> feedAuthors =
            Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_REMEMBERED_IDS;
                }
            }));

    /** Notes a name as belonging to one of the site's users, as its content is served. */
    protected static void noteAuthor(String author) {
        if (author != null && !author.isEmpty()) {
            feedAuthors.add(author);
        }
    }

    /**
     * The user a profile names, where it is one of the site's.
     *
     * <p>The app writes a profile as {@code user###<section>###<name>###null}. Only a name seen on
     * the site's own content is taken, so that a Reddit account of the same name still opens as
     * itself.
     *
     * @param name the profile being opened, in the app's own form.
     * @return the user, or {@code null} for a Reddit profile.
     */
    protected static String profileUserIn(String name) {
        if (name == null) {
            return null;
        }

        String[] parts = name.split("###");
        if (parts.length < 3 || !"user".equalsIgnoreCase(parts[0])) {
            return null;
        }

        String user = parts[2].trim();
        return feedAuthors.contains(user) ? user : null;
    }

    /** The part of a profile the app is showing, which is what a page of it lists. */
    protected static String profileSectionIn(String name) {
        String[] parts = name == null ? new String[0] : name.split("###");
        return parts.length < 2 ? "Overview" : parts[1].trim();
    }

    /** The profile whose page is being listed, carried to where the response is translated. */
    protected static volatile String pendingProfile;

    /** What part of that profile is being listed. */
    protected static volatile String pendingSection;

    /**
     * The url to request for a page of one of the site's users.
     *
     * <p>A profile lists its user's own posts and comments through the same request a feed's page is
     * read with, so it is answered here rather than through a request of its own.
     *
     * @param name the profile being listed, in the app's own form.
     * @param after the pagination cursor, which is the offset into what the user submitted.
     * @return the url to request, or {@code null} for anything that is not one of the site's users.
     */
    public static String buildProfileUrl(String name, String after) {
        CustomFeedExtension feed = feed();
        if (feed == null) {
            return null;
        }

        String user = profileUserIn(name);
        if (user == null) {
            return null;
        }

        pendingOffset = parseOffset(after);
        pendingProfile = user;
        pendingSection = profileSectionIn(name);
        return feed.userUrl(user);
    }

    /** The address one of the site's users is read from. */
    protected abstract String userUrl(String user);

    /** The feed to read, given the sort the app is asking for. */
    public static String sortOrDefault(String sort) {
        CustomFeedExtension feed = feed();
        if (feed == null) {
            return sort;
        }

        currentSort = feed.isKnownSort(sort) ? sort.trim() : feed.defaultSort();
        return currentSort;
    }

    /** The icon for the chip naming the current feed, or {@code 0} to leave it to the app. */
    public static int sortIconFor(String sort) {
        CustomFeedExtension feed = feed();
        return feed == null || sort == null ? 0 : feed.sortIcon(sort.trim());
    }

    /** Whether a name refers to the feed this patch adds. */
    public static boolean isFeedName(String name) {
        CustomFeedExtension feed = feed();
        return feed != null && feed.isFeed(name);
    }

    /**
     * The title for the sheet of options shown for a subreddit, which the feed is shown through.
     *
     * <p>The sheet is titled for a subreddit, which the feed is not one of, so it is named as the
     * feed it is. Anything else keeps the title it was given.
     */
    public static String optionsTitle(String name, String title) {
        return isFeedName(name) ? "Feed options" : title;
    }

    /**
     * Whether an option belongs in that sheet, which the ones meaning nothing for the feed are left
     * out of.
     *
     * <p>The options left out act on a subreddit through Reddit - its moderators, its flairs and the
     * custom feeds an account collects subreddits into. None of them has anything to act on here.
     */
    public static boolean showsOption(String name, String option) {
        if (!isFeedName(name)) {
            return true;
        }

        for (String hidden : HIDDEN_FEED_OPTIONS) {
            if (hidden.equals(option)) {
                return false;
            }
        }

        return true;
    }

    /** The options left off the feed's sheet, named as they are listed. */
    private static final String[] HIDDEN_FEED_OPTIONS = {
        "Message mods", "View mods", "Flair", "Add to multi"
    };

    /**
     * The title for the dialog offering to override the sort a subreddit is read with.
     *
     * <p>Named for the feed rather than for a subreddit, in the same way as the sheet it is opened
     * from.
     */
    public static String sortDialogTitle(String name, String title) {
        return isFeedName(name) ? "Update feed sort" : title;
    }

    /**
     * The sorts to offer for a name, which are the app's own for anything but the feed.
     *
     * <p>Reddit's sorts mean nothing for the feed, so the feeds the site serves are offered in their
     * place - the same ones the sort dialog offers, listed in the same order. Both the labels shown
     * and the values stored are these names, so what is picked is stored as the sort it is.
     *
     * <p>Anything else is answered with the sorts it was read with, so the array is replaced in place
     * and only the feed is affected.
     */
    public static String[] sortOptions(String name, String[] sorts) {
        CustomFeedExtension feed = feed();
        return feed != null && feed.isFeed(name) ? feed.sortLabels() : sorts;
    }

    /**
     * The sort a stored override refers to, or {@code null} for one of Reddit's own.
     *
     * <p>The app stores its sorts as codes it decodes back into a name and a time range, and anything
     * it does not recognise it reads as "New". The feed's sorts are stored as the names they are, so
     * they are answered here before that decoding reaches them.
     */
    public static String decodeSort(String stored) {
        CustomFeedExtension feed = feed();
        return feed != null && feed.isKnownSort(stored) ? stored.trim() : null;
    }

    /**
     * The sort to name on the chip, which is the one actually being read for the feed.
     *
     * <p>The chip is labelled from the sort the screen holds, which for the feed may be one of
     * Reddit's that it does not have - the request falls back to the feed's own sort, but nothing
     * corrects what is shown. The sort actually being read is named here instead, so the chip agrees
     * with the posts under it.
     */
    public static String sortLabel(String name, String sort) {
        CustomFeedExtension feed = feed();
        if (feed == null || !feed.isFeed(name)) {
            return sort;
        }

        return feed.isKnownSort(sort) ? sort.trim() : feed.defaultSort();
    }

    /**
     * The time range to name alongside that sort, which the feed's own sorts have none of.
     *
     * <p>Reddit's ranges go with sorts like Top and Controversial, neither of which the feed has, so
     * naming one next to a feed of the site's would read as a range that was applied.
     */
    public static String sortRangeLabel(String name, String range) {
        return isFeedName(name) ? null : range;
    }

    /** The name of the feed this patch adds, as it is listed. */
    public static String feedNameOf() {
        CustomFeedExtension feed = feed();
        return feed == null ? null : feed.feedName();
    }

    /** Whether a name is the feed as it is stored, which is the name prefixed with "multi_". */
    public static boolean isFeedKey(String name) {
        CustomFeedExtension feed = feed();
        return feed != null && feed.feedKey().equals(name);
    }

    /** The title to show for the feed, without the marker the app adds to a custom feed. */
    public static String feedTitle(String name) {
        CustomFeedExtension feed = feed();
        return feed != null && feed.feedKey().equals(name) ? feed.feedName() : null;
    }

    /** The icon to show for the feed, or {@code null} to leave the lookup as it is. */
    public static String iconFor(String name) {
        CustomFeedExtension feed = feed();
        return feed != null && feed.isFeed(name) ? feed.iconUrl() : null;
    }

    /** The url to request for a post's comments, or {@code null} for a Reddit post. */
    public static String buildCommentsUrl(String id) {
        CustomFeedExtension feed = feed();
        if (feed == null || !feed.isFeedId("t3_" + id)) {
            return null;
        }

        pendingStory = id;
        return feed.commentsUrl(id);
    }

    /** Translates a page of the site's posts into the listing Sync parses. */
    public static byte[] rewriteResponse(byte[] data) {
        CustomFeedExtension feed = feed();
        if (feed == null || data == null || !feed.isFeedResponse(data)) {
            return data;
        }

        try {
            byte[] listing = feed.toListing(data, pendingOffset);
            return listing == null ? data : listing;
        } catch (Exception e) {
            // Returning the response untouched leaves Sync to fail the request as it normally would.
            return data;
        }
    }

    /** Translates a post and its comments into the comment tree Sync parses. */
    public static byte[] rewriteComments(byte[] data) {
        CustomFeedExtension feed = feed();
        if (feed == null || data == null || pendingStory == null) {
            return data;
        }

        try {
            byte[] comments = feed.toComments(data);
            return comments == null ? data : comments;
        } catch (Exception e) {
            return data;
        }
    }

    /** The address to read the feed's rules from, or {@code null} to leave the request as it is. */
    public static String guidelinesUrlFor(String name) {
        CustomFeedExtension feed = feed();
        pendingGuidelines = feed != null && feed.isFeed(name);
        return pendingGuidelines ? feed.guidelinesUrl() : null;
    }

    /** Replaces the rules response with the site's own page. */
    public static byte[] rewriteRules(byte[] data) {
        CustomFeedExtension feed = feed();
        if (feed == null || !pendingGuidelines) {
            return data;
        }

        try {
            byte[] rules = feed.toRules(data);
            return rules == null ? data : rules;
        } catch (Exception e) {
            return data;
        }
    }

    /**
     * The address to read the feed's wiki from, or {@code null} to leave the request as it is.
     *
     * <p>The page asked for selects which of the site's pages is served, so that a link from one to
     * another opens in this same sheet rather than leaving the app. The page a link names is carried
     * in the wiki address it was rewritten to, and anything else falls back to the site's FAQ.
     */
    public static String faqUrlFor(String name, String page) {
        CustomFeedExtension feed = feed();
        pendingFaq = feed != null && feed.isFeed(name);
        if (!pendingFaq) {
            return null;
        }

        pendingWikiPage = page == null ? null : page.trim();
        String url = feed.wikiPageUrl(pendingWikiPage);
        return url == null ? feed.faqUrl() : url;
    }

    /**
     * The page the wiki sheet is showing, which the title and the trimming of its heading follow.
     *
     * <p>Held from the request being built to the response being parsed, in the same way as the page
     * offset for a listing.
     */
    protected static volatile String pendingWikiPage;

    /** Replaces the wiki response with the site's own page. */
    public static byte[] rewriteWiki(byte[] data) {
        CustomFeedExtension feed = feed();
        if (feed == null || !pendingFaq) {
            return data;
        }

        try {
            byte[] wiki = feed.toWiki(data);
            return wiki == null ? data : wiki;
        } catch (Exception e) {
            return data;
        }
    }

    /**
     * The title to show above a wiki page.
     *
     * <p>The feed's wiki is a page the site holds rather than one an account wrote, so it is named as
     * that rather than as the feed and page it is read from.
     */
    public static String wikiTitleFor(String title) {
        CustomFeedExtension feed = feed();
        // The title is built from the name read out of the address, which carries its space escaped.
        if (feed == null || title == null || !feed.isFeed(feedNameIn(title))) {
            return title;
        }

        // The page is read out of the title rather than from the request, because the sheet titles
        // itself before it asks for the page - reading it from the request would name whichever page
        // was asked for last.
        String page = feed.wikiPageTitle(wikiPageIn(title));
        return page == null ? feed.wikiTitle() : page;
    }

    /**
     * The page a wiki title was built from, which is everything after the name it follows.
     *
     * <p>The sheet titles itself with the subreddit and page it is reading, joined by a dash.
     */
    private static String wikiPageIn(String title) {
        int separator = title.indexOf(" - ");
        return separator == -1 ? null : title.substring(separator + " - ".length()).trim();
    }

    /**
     * The wiki address serving one of the site's pages, or {@code null} for any other address.
     *
     * <p>The reverse of what is shared: an address on the site is turned back into the wiki page it is
     * read as, so that opening a link to one shows it in the app.
     */
    private String wikiPageOpened(String url) {
        String name = wikiPageNamed(url);
        return name == null ? null : wikiAddress(name);
    }

    /**
     * The page one of the site's addresses is served as, or {@code null} where it serves none.
     *
     * <p>Asked of the feed, which knows the pages it serves and the addresses they are read from.
     */
    protected String wikiPageNamed(String url) {
        return null;
    }

    /** The wiki address one of the feed's pages is read under. */
    protected final String wikiAddress(String page) {
        // The feed's name may hold a space, which would end the address early once it is written as a
        // Markdown link, so it is escaped the way a url carries one.
        return REDDIT_HOSTS[0] + "/r/" + feedKey().replace(" ", "%20") + "/wiki/" + page;
    }

    /**
     * The site's own address for a wiki page of the feed, or {@code null} for any other address.
     *
     * <p>The pages are served as wiki pages of the feed, so a link to one names an address Reddit does
     * not have. What is shared is the page on the site, which is where it was read from.
     */
    private String wikiPageShared(String url) {
        String marker = "/wiki/";
        int start = url.indexOf(marker);
        if (start == -1) {
            return null;
        }

        // Only a wiki address of this feed is corrected; every other subreddit's wiki is left alone.
        String name = url.substring(0, start);
        int subreddit = name.lastIndexOf("/r/");
        if (subreddit == -1 || !isFeed(name.substring(subreddit + "/r/".length()))) {
            return null;
        }

        String page = url.substring(start + marker.length()).trim();
        int query = page.indexOf('?');
        if (query != -1) {
            page = page.substring(0, query);
        }

        return wikiPageShareUrl(page);
    }

    /**
     * The name a wiki title was built from, which is everything before the page it names.
     *
     * <p>The sheet titles itself with the subreddit and page it read, joined by a dash, so the name is
     * what precedes the first of those.
     */
    private static String feedNameIn(String title) {
        int separator = title.indexOf(" - ");
        return separator == -1 ? title : title.substring(0, separator);
    }

    /**
     * The address to list the feed's subreddits from.
     *
     * <p>Reddit answers 404 for a feed that does not exist there, and a failed response is raised as
     * an error before it can be parsed. So the request is pointed at an address that answers
     * successfully, and its response replaced with the feed's contents once parsing is reached.
     */
    public static String feedSubredditsUrl(String name) {
        CustomFeedExtension feed = feed();
        pendingFeedSubreddits = feed != null && feed.feedName().equals(name);
        return pendingFeedSubreddits ? feed.placeholderUrl() : null;
    }

    /**
     * Any address answering with a small, valid body, whose response is replaced regardless.
     *
     * <p>Taken from the site rather than fixed here, so that a feed is not made to depend on a host
     * it otherwise has nothing to do with.
     */
    protected abstract String placeholderUrl();

    /** Replaces the response listing a feed's subreddits, when it is this feed. */
    public static byte[] rewriteFeedSubreddits(byte[] data) {
        CustomFeedExtension feed = feed();
        if (feed == null || !pendingFeedSubreddits) {
            return data;
        }

        try {
            JSONObject subreddit = new JSONObject();
            subreddit.put("name", feed.subreddit());

            JSONArray subreddits = new JSONArray();
            subreddits.put(subreddit);

            JSONObject inner = new JSONObject();
            inner.put("name", feed.feedName());
            inner.put("subreddits", subreddits);

            JSONObject response = new JSONObject();
            response.put("kind", "LabeledMulti");
            response.put("data", inner);

            return response.toString().getBytes("UTF-8");
        } catch (Exception e) {
            return data;
        }
    }

    /**
     * Adds the feed to the account's custom feeds.
     *
     * <p>The feeds response is an array of {@code {"data":{"name":...}}} objects, so the feed is
     * added by appending one more entry rather than by patching the parsing that reads it.
     */
    public static byte[] addFeed(byte[] data) {
        CustomFeedExtension feed = feed();
        if (feed == null || data == null) {
            return data;
        }

        try {
            JSONArray feeds = new JSONArray(new String(data, "UTF-8"));

            // The response is re-read on every refresh, so guard against listing the feed twice.
            for (int i = 0; i < feeds.length(); i++) {
                JSONObject existing = feeds.getJSONObject(i).optJSONObject("data");
                if (existing != null && feed.feedName().equals(existing.optString("name"))) {
                    return data;
                }
            }

            JSONObject added = new JSONObject();
            added.put("name", feed.feedName());
            added.put("display_name", feed.feedName());

            JSONObject entry = new JSONObject();
            entry.put("kind", "LabeledMulti");
            entry.put("data", added);

            feeds.put(entry);

            return feeds.toString().getBytes("UTF-8");
        } catch (Exception e) {
            // Leaving the response untouched simply means the feed is not listed.
            return data;
        }
    }

    /**
     * Whether a feed can be deleted, which the feed this patch adds cannot.
     *
     * <p>The feed is not one the account holds, but one this patch adds to the list, so deleting it
     * would ask Reddit to remove a feed it does not have. It reappears whenever the list is read
     * again, so the option is left off rather than failing.
     */
    public static boolean isDeletable(boolean deletable, String name) {
        return deletable && !isFeedName(name);
    }

    /** The name to open in place of the subreddit named on a post, for a post from this feed. */
    public static String feedFor(String name, Object shownOn) {
        CustomFeedExtension feed = feed();
        if (feed == null) {
            return name;
        }

        return feed.isFeedPost(postShownOn(shownOn)) ? feed.feedKey() : name;
    }

    /**
     * The setting deciding whether the site's own links open in the feed.
     *
     * <p>Listed among the app's own "Links opened in app" settings, which are the same choice made
     * for the kinds of link the app already opens itself.
     */
    public static final String OPEN_LINKS_PREFERENCE = "custom_feed_links_preference";

    /**
     * The screen the setting is shown on, which its settings are kept under.
     *
     * <p>Each of the app's settings screens keeps its own, named after the screen itself.
     */
    private static final String LINKS_SCREEN = "PreferencesLinksFragment";

    /**
     * Whether the site's own links are opened in the feed rather than handed on.
     *
     * <p>The app is offered for these links by its manifest, which is settled when the patch is
     * applied and cannot be changed while it is running. So the choice is made here, where a link is
     * about to be opened: with this off, a link to the site is left to be opened as any other link
     * is, which is the browser.
     *
     * <p>Read from the app's own preferences, which is where the setting is shown and stored, so that
     * the choice made in settings is the one read here.
     */
    private static boolean opensLinks() {
        try {
            // Read from the settings of the screen the setting is shown on, which is where the app
            // keeps the answers given on it. Each screen keeps its own, named after itself, and the
            // app resolves that to the account's settings or its own as it goes.
            return t7.z.g(LINKS_SCREEN).getBoolean(OPEN_LINKS_PREFERENCE, true);
        } catch (Exception e) {
            // A setting that cannot be read is treated as left on, which is how it is shipped.
            return true;
        }
    }

    /** The link to open in place of one of the site's own. */
    public static String linkFor(String url) {
        CustomFeedExtension feed = feed();
        if (feed == null || !opensLinks()) {
            return url;
        }

        return feed.openLink(url);
    }

    /** Corrects a link built for this feed's content before it is shared or copied. */
    public static String shareUrl(String url) {
        CustomFeedExtension feed = feed();
        return feed == null ? url : feed.correctUrl(url);
    }

    /**
     * Corrects copied text, which may be a link to this feed's content.
     *
     * <p>Copying is also used for text that is not a link, such as a comment's body, which is
     * returned unchanged.
     */
    public static CharSequence shareText(CharSequence text) {
        CustomFeedExtension feed = feed();
        if (feed == null || text == null) {
            return text;
        }

        String value = text.toString();

        // A link on its own is corrected as a link, which also covers the feed and the site's own
        // addresses.
        String corrected = feed.correctUrl(value);
        if (!corrected.equals(value)) {
            return corrected;
        }

        // A link offered alongside other text is corrected where it sits, so that what is shown and
        // what is copied both name the site rather than the address this patch serves it under.
        return feed.correctEmbeddedUrls(value);
    }

    /** Notes whether the post a marker is about to be built for is one of the feed's. */
    public static void noteMarkedPost(Object post) {
        CustomFeedExtension feed = feed();
        markingFeedPost = feed != null && feed.isFeedPost(post);
    }

    /**
     * Whether a post is marked as archived or locked in the line under its title.
     *
     * <p>Posts are archived and locked so that the app does not offer to vote on or reply to them,
     * which this feed's content cannot accept through Reddit. Those are means rather than something
     * true of the post, so the markers they would otherwise add are left off.
     */
    public static boolean showsMarker(boolean marked) {
        return marked && !markingFeedPost;
    }

    /**
     * The notice shown above a post's comments, saying why it cannot be voted on or replied to.
     *
     * <p>A post is archived only while there is no account to act on the site as, which is the reason
     * the app cannot offer either - so the notice says that rather than describing the archiving,
     * which is the means.
     */
    public static String noticeFor(String notice, Object post) {
        CustomFeedExtension feed = feed();
        if (feed == null || notice == null || !feed.isFeedPost(post)) {
            return notice;
        }

        return notice.replace(
                "This is an archived post.",
                "You are not logged into " + feed.feedName() + ".");
    }

    /**
     * Remembers the token the posts request is authenticated with.
     *
     * <p>Read from the request being parsed, since Reddit only answers this for a signed-in client
     * and the token is held per request rather than anywhere reachable on its own.
     */
    public static void noteToken(String value) {
        token = value;
    }

    /**
     * Remembers the user agent the app reports, which the token is sent alongside.
     *
     * <p>Handed over with the token, since the token is only accepted from the client it was issued
     * to, which a patch spoofing the client changes.
     */
    public static void noteUserAgent(String value) {
        if (value != null) {
            userAgent = value;
        }
    }

    /**
     * Fails a request that acts on this feed's content.
     *
     * <p>Reddit's write endpoints take the id of the post or comment being acted on, so such a
     * request would ask Reddit to act on an id that means nothing there. Read requests do not name an
     * id this way, so they are unaffected.
     *
     * <p>Failing rather than quietly dropping the request lets Volley report it through the error
     * listener the action was made with, so the app shows the action failing instead of appearing to
     * succeed.
     *
     * @throws AuthFailureError if the request acts on this feed's content.
     */
    public static void checkWriteAllowed(Map<String, String> params) throws AuthFailureError {
        CustomFeedExtension feed = feed();
        if (feed == null || params == null) {
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

            if (!feed.isFeedId(id)) {
                continue;
            }

            // The action is carried out on the site rather than being sent to Reddit, which holds
            // none of this content. The request itself is always failed, so that Volley reports it
            // through the error listener the action was made with rather than the app believing
            // Reddit took it - what actually came of it is said in the message.
            WriteResult result = feed.write(params, id);

            // Said here rather than left to the app, which reports a failed request as its own
            // wording - the write was made on the site, so what came of it is what should be said.
            String message = result.message(feed.feedName());
            if (message != null) {
                toast(message);
            }

            throw new AuthFailureError(result == WriteResult.DONE ? DONE_MESSAGE : message);
        }
    }

    /** Tells the user what came of a write, in the same way the app says anything else. */
    private static void toast(String message) {
        try {
            gb.p.d(message);
        } catch (Exception e) {
            // A message that cannot be shown is not worth failing the write over.
        }
    }

    /**
     * Marks a failure the app is told about only so that it stops waiting, the write having been made.
     *
     * <p>Every write is failed at the request, since Reddit is not being asked to carry it out, so the
     * one that succeeded has to be told apart from the ones that did not.
     */
    public static final String DONE_MESSAGE = "\u0000done";

    /**
     * Carries out on the site the write the request was going to ask Reddit for.
     *
     * <p>What is being asked for is read from the parameters the request was built with, which name
     * the item and what is being done to it in the same way for every write.
     *
     * @param id the item being acted on, as Reddit's fullname for it.
     */
    protected WriteResult write(Map<String, String> params, String id) {
        return WriteResult.UNSUPPORTED;
    }
}
