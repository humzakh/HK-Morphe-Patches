package app.morphe.extension.syncforreddit;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves Hacker News as if it were a subreddit.
 *
 * <p>Everything true of any such feed - telling the feed's content apart from Reddit's, the listing
 * envelopes, link correction, reading pages - is held by {@link CustomFeedExtension}. This holds what
 * Hacker News itself is: where its stories come from, how they are shaped, and what it calls its
 * feeds.
 *
 * @noinspection unused
 */
public class HackerNewsExtension extends CustomFeedExtension {
    private static final String API = "https://hacker-news.firebaseio.com/v0/";

    /** The site stories and comments are read on, as opposed to the API they are fetched from. */
    private static final String NEWS_HOST = "https://news.ycombinator.com/";

    /** The site's own guidelines, shown as the feed's rules. */
    private static final String GUIDELINES_URL = NEWS_HOST + "newsguidelines.html";

    /** The guidelines for Show HN, which are shown while that feed is being read. */
    private static final String SHOW_GUIDELINES_URL = NEWS_HOST + "showhn.html";

    /** The site's own FAQ, shown as the feed's wiki. */
    private static final String FAQ_URL = NEWS_HOST + "newsfaq.html";

    /** The site's own formatting help, which its pages link to. */
    private static final String FORMAT_URL = NEWS_HOST + "formatdoc";

    /**
     * Hacker News' own icon, the orange "Y".
     *
     * <p>The png is used rather than the svg the site also serves, since the app's image loading has
     * no svg support and shows Reddit's own icons as pngs.
     */
    private static final String ICON_URL = NEWS_HOST + "apple-touch-icon.png";

    /** The feed shown when none has been chosen, whatever sort the app defaults its own posts to. */
    public static final String DEFAULT_SORT = "Hot";

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

    /**
     * How many comments are requested at once while a thread loads.
     *
     * <p>Higher than for a page of stories, since a thread is the one place a request is made per
     * item by the hundred, and Hacker News' API is served by Firebase, which is built for this.
     */
    private static final int COMMENT_THREADS = 48;

    /** The longest a thread is waited on, after which it is shown with what has loaded. */
    private static final long THREAD_TIMEOUT_MS = 60_000;

    /** Counts comments loaded for the thread being parsed, to enforce {@link #MAX_COMMENTS}. */
    private static final AtomicInteger fetched = new AtomicInteger();

    @Override
    protected String feedName() {
        return "Hacker News";
    }

    @Override
    protected String subreddit() {
        return "hackernews";
    }

    @Override
    protected String siteUrl() {
        return NEWS_HOST;
    }

    @Override
    protected String iconUrl() {
        return ICON_URL;
    }

    @Override
    protected String linkSlug() {
        return "hn";
    }

    @Override
    protected String defaultSort() {
        return DEFAULT_SORT;
    }

    /**
     * Hacker News numbers its items, while Reddit's ids are base 36 and in practice always contain a
     * letter at their current length.
     */
    @Override
    protected boolean isFeedItemId(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }

        for (int i = 0; i < id.length(); i++) {
            if (id.charAt(i) < '0' || id.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * The url Sync requests for the Hacker News feed. Only the id list is fetched here; the stories
     * themselves are fetched while parsing, once the page offset is known.
     */
    @Override
    protected String postsUrl(String after, String sort) {
        return API + feedEndpoint(sort) + ".json";
    }

    /**
     * The url Sync requests for a story's comments.
     *
     * <p>Only the story is fetched here. Its comments are fetched while parsing, where they can be
     * fetched concurrently rather than one request at a time.
     *
     * <p>What the id names is not settled here. This runs as the request is built, on the thread the
     * app draws on, where Hacker News cannot be asked; a comment is recognised from the response
     * instead, where the reading is done off that thread.
     */
    @Override
    protected String commentsUrl(String id) {
        return API + "item/" + id + ".json";
    }

    /** Any address answering with a small, valid body, whose response is replaced regardless. */
    @Override
    protected String placeholderUrl() {
        return API + "maxitem.json";
    }

    /**
     * A user, which is what they submitted is named on.
     *
     * <p>Hacker News lists what a user submitted on the user itself rather than on a page of its own,
     * so the page of their posts and comments is taken out of the ids it names.
     */
    @Override
    protected String userUrl(String user) {
        return API + "user/" + user + ".json";
    }

    @Override
    protected String itemUrl(String id) {
        return NEWS_HOST + "item?id=" + id;
    }

    /**
     * Whether Hacker News says an id names a comment.
     *
     * <p>Every item says what it is, so it is read from the item rather than worked out from what it
     * hangs from.
     */
    @Override
    protected Boolean namesComment(String id) {
        try {
            JSONObject item = fetchItem(Integer.parseInt(id));
            return item == null ? null : "comment".equals(item.optString("type"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The item a Hacker News link refers to, or {@code null} if it refers to none.
     *
     * <p>Matched with or without a scheme, since a link may be written either way.
     */
    @Override
    protected String itemIdIn(String url) {
        String value = url.trim();
        String marker = "news.ycombinator.com/item?id=";

        int item = value.indexOf(marker);
        if (item == -1) {
            return null;
        }

        String id = value.substring(item + marker.length());

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

    /** Whether a sort is one of the feeds this patch offers. */
    @Override
    protected boolean isKnownSort(String sort) {
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

    /** The feeds offered in place of Reddit's sorts, in the order the sort dialog lists them. */
    @Override
    protected String[] sortLabels() {
        return new String[] { "Best", "Hot", "New", "Ask", "Show", "Jobs" };
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
     * <p>These match the icons the sort dialog offers them with; both are drawables the app already
     * ships. Best, Hot and New are named after Reddit sorts the app already has icons for.
     */
    @Override
    protected int sortIcon(String sort) {
        if (sort.equalsIgnoreCase("Ask")) {
            // outline_help_outline_24
            return 0x7f08048e;
        }
        if (sort.equalsIgnoreCase("Show")) {
            // ic_trending_up_white_24dp
            return 0x7f0801e1;
        }
        if (sort.equalsIgnoreCase("Jobs")) {
            // outline_bar_chart_24
            return 0x7f0802cf;
        }
        return 0;
    }

    /** Show HN has its own guidelines, which are the useful ones while reading that feed. */
    @Override
    protected String guidelinesUrl() {
        return "Show".equalsIgnoreCase(currentSort) ? SHOW_GUIDELINES_URL : GUIDELINES_URL;
    }

    @Override
    protected String faqUrl() {
        return FAQ_URL;
    }

    /**
     * The pages the wiki sheet serves, by the name links to them are rewritten under.
     *
     * <p>The site's pages link to each other, and to sections within themselves, by file name. Those
     * are rewritten to wiki pages of these names so that following one opens it in this same sheet
     * rather than handing the address to a browser.
     */
    private static final String FAQ_PAGE = "faq";
    private static final String GUIDELINES_PAGE = "guidelines";
    private static final String SHOW_PAGE = "showhn";
    private static final String FORMAT_PAGE = "formatdoc";

    /** The page a subreddit's wiki opens at, which the wiki chip asks for. */
    private static final String WIKI_INDEX_PAGE = "index";

    /**
     * The prefix a page naming one section of the FAQ is written under.
     *
     * <p>The site links to sections of its FAQ, which a page shown whole has nothing to scroll to. The
     * section is served as a page of its own instead, so the link lands on what it named.
     */
    private static final String SECTION_PREFIX = "faq-";

    @Override
    protected String wikiPageUrl(String page) {
        if (GUIDELINES_PAGE.equalsIgnoreCase(page)) {
            return GUIDELINES_URL;
        }
        if (SHOW_PAGE.equalsIgnoreCase(page)) {
            return SHOW_GUIDELINES_URL;
        }
        if (FORMAT_PAGE.equalsIgnoreCase(page)) {
            return FORMAT_URL;
        }
        if (page != null && page.toLowerCase(java.util.Locale.ENGLISH).startsWith(SECTION_PREFIX)) {
            return FAQ_URL;
        }
        // The wiki chip opens the feed's wiki at "index", which is the page every subreddit's wiki
        // opens at, so that is the FAQ as much as the name it is linked to under.
        return isFaqPage(page) ? FAQ_URL : null;
    }

    /** Whether a page names the FAQ, which the wiki opens at under either name. */
    private static boolean isFaqPage(String page) {
        return FAQ_PAGE.equalsIgnoreCase(page) || WIKI_INDEX_PAGE.equalsIgnoreCase(page);
    }

    @Override
    protected String wikiPageTitle(String page) {
        if (GUIDELINES_PAGE.equalsIgnoreCase(page)) {
            return "Hacker News Guidelines";
        }
        if (SHOW_PAGE.equalsIgnoreCase(page)) {
            return "Show HN Rules";
        }
        if (FORMAT_PAGE.equalsIgnoreCase(page)) {
            return "Formatting Options";
        }
        return sectionTitle(sectionOf(page));
    }

    /**
     * The title for a section of the FAQ, named as the question it answers.
     *
     * <p>Titled from here rather than from the heading on the page, because the sheet titles itself
     * before it has asked for the page. Only the sections the site actually links to are named; any
     * other is shown under the FAQ's own title.
     */
    private static String sectionTitle(String section) {
        if (section == null) {
            return null;
        }

        if (section.equalsIgnoreCase("cflag")) {
            return "Flagging a comment";
        }
        if (section.equalsIgnoreCase("dead")) {
            return "Dead posts";
        }
        if (section.equalsIgnoreCase("ring")) {
            return "Asking for upvotes";
        }
        if (section.equalsIgnoreCase("jobads")) {
            return "Job ads";
        }
        return null;
    }

    /**
     * The page one of the site's addresses is served as, so that a link to it opens in the app.
     *
     * <p>The section a link names is kept, so that following one lands on what it named in the same
     * way as a link between the pages themselves.
     */
    @Override
    protected String wikiPageNamed(String url) {
        String path = url.trim();
        for (String host : new String[] { NEWS_HOST, NEWS_HOST.replace("https://", "http://") }) {
            if (path.startsWith(host)) {
                path = path.substring(host.length());
                break;
            }
        }

        if (path.equals(url.trim())) {
            return null;
        }

        String section = null;
        int hash = path.indexOf('#');
        if (hash != -1) {
            section = path.substring(hash + 1);
            path = path.substring(0, hash);
        }

        String page;
        if (path.equals("newsfaq.html")) {
            page = FAQ_PAGE;
        } else if (path.equals("newsguidelines.html")) {
            page = GUIDELINES_PAGE;
        } else if (path.equals("showhn.html")) {
            page = SHOW_PAGE;
        } else if (path.equals("formatdoc")) {
            page = FORMAT_PAGE;
        } else {
            return null;
        }

        // A section of the FAQ is served as a page of its own, which is what a link naming one opens.
        return FAQ_PAGE.equals(page) && section != null && !section.isEmpty()
                ? SECTION_PREFIX + section
                : page;
    }

    /** A section is shared as the part of the FAQ it is, which is where it is read on the site. */
    @Override
    protected String wikiPageShareUrl(String page) {
        String section = sectionOf(page);
        return section == null ? wikiPageUrl(page) : FAQ_URL + "#" + section;
    }

    /** The section a page names, or {@code null} for a whole page. */
    private static String sectionOf(String page) {
        if (page == null) {
            return null;
        }

        String value = page.trim();
        return value.toLowerCase(java.util.Locale.ENGLISH).startsWith(SECTION_PREFIX)
                ? value.substring(SECTION_PREFIX.length())
                : null;
    }

    /** Whether the body is the Hacker News story id array rather than a Reddit response. */
    @Override
    protected boolean isFeedResponse(byte[] data) {
        for (byte b : data) {
            // Skip any leading whitespace to reach the first meaningful character.
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
                continue;
            }

            // The story list is the only bare array received here.
            if (b == '[') {
                return true;
            }

            // A user is an object, as many of Reddit's own responses are, so it is recognised by
            // being the user that was asked for rather than by its shape. Without that, any object
            // arriving while a profile is open would be read as a page of it.
            return b == '{' && pendingProfile != null && isUser(data, pendingProfile);
        }
        return false;
    }

    /** Whether a response is the user that was asked for, rather than some other object. */
    private static boolean isUser(byte[] data, String user) {
        try {
            JSONObject item = new JSONObject(new String(data, "UTF-8"));
            return user.equals(item.optString("id")) && item.has("submitted");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Translates a page of story ids into the listing Sync parses.
     *
     * @param data the raw story id array.
     * @param offset the offset into it this page starts at.
     */
    @Override
    protected byte[] toListing(byte[] data, int offset) {
        try {
            String body = new String(data, "UTF-8");

            // A profile lists what its user submitted, which Hacker News names on the user rather
            // than on a page of its own. A feed's page is the story list as it stands.
            JSONArray ids = body.trim().startsWith("[")
                    ? new JSONArray(body)
                    : new JSONObject(body).optJSONArray("submitted");
            if (ids == null) {
                return null;
            }

            List<Integer> page = new ArrayList<>();
            for (int i = offset; i < ids.length() && page.size() < PAGE_SIZE; i++) {
                page.add(ids.getInt(i));
            }

            List<JSONObject> stories = fetchItems(page);

            // What a user submitted is their comments as well as their posts, and each says which it
            // is. The part of the profile being shown decides which of the two are listed.
            if (pendingProfile != null) {
                boolean comments = "Comments".equalsIgnoreCase(pendingSection);
                boolean posts = "Submitted".equalsIgnoreCase(pendingSection);

                if (comments || posts) {
                    List<JSONObject> shown = new ArrayList<>();
                    for (JSONObject item : stories) {
                        if ("comment".equals(item.optString("type")) == comments) {
                            shown.add(item);
                        }
                    }
                    stories = shown;
                }
            }

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

            // A comment names only what it hangs from, so the story it was written on is found by
            // following that up. Those walks are made for the page at once rather than one after
            // another, which would leave a page of comments as slow as every walk put together.
            Map<String, JSONObject> commentStories = storiesOf(stories);

            JSONArray children = new JSONArray();
            for (JSONObject story : stories) {
                // Each item says which it is, so a comment is listed as the comment it is, under the
                // story it was written on, and everything else as the post it is.
                children.put("comment".equals(story.optString("type"))
                        ? toCommentChild(story, commentStories)
                        : toChild(story, thumbnails));
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

            return root.toString().getBytes("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Translates a Hacker News story into the comment tree Sync parses.
     *
     * <p>Sync expects Reddit's two element comments response: the post, followed by its comments.
     *
     * @param data the raw story response.
     */
    @Override
    protected byte[] toComments(byte[] data) {
        try {
            if (isFeedResponse(data)) {
                return null;
            }

            JSONObject story = new JSONObject(new String(data, "UTF-8"));

            // Reddit posts carry a "subreddit"; a Hacker News item never does, which distinguishes a
            // story response from a Reddit one.
            if (story.has("subreddit") || !pendingStory.equals(String.valueOf(story.optInt("id")))) {
                return null;
            }

            // A link names a comment as readily as a story, and both are written the same way, so what
            // was fetched may be a comment. The story its thread hangs from is read in its place,
            // which is done here rather than where the url was built because that runs on the thread
            // the app draws on, where Hacker News cannot be asked.
            // The comment the thread is opened at, where a comment was linked rather than a story.
            Integer root = null;
            if ("comment".equals(story.optString("type"))) {
                // Noted as the comment the item says it is, so that opening the same link again names
                // it as one from the start rather than being settled here a second time.
                served.add("t1_" + story.optInt("id"));

                List<String> ancestry = ancestryOf(String.valueOf(story.optInt("id")));

                // The item, whatever it hangs from, and the story it belongs to, so a comment always
                // gives at least itself and its story.
                if (ancestry.size() < 2) {
                    return null;
                }

                // The story is the last of the walk, and the comment below it heads the thread shown.
                String storyId = ancestry.get(ancestry.size() - 1);
                root = Integer.valueOf(ancestry.get(ancestry.size() - 2));

                String storyItem = get(API + "item/" + storyId + ".json");
                if (storyItem == null) {
                    return null;
                }

                story = new JSONObject(storyItem);
                pendingStory = storyId;
            }

            // What the account has done is read while the comments load, rather than before them,
            // since it is only needed once the post and its comments are written out.
            //
            // The account's listings are read first, so that the post's own page is read after them
            // and what it says is what stands - they are replaced wholesale as they are read, which
            // would otherwise put back what the post's page had just corrected.
            //
            // What the account has done to this post is then read from its own page, rather than being
            // taken from those listings: a post is opened one at a time, so asking about it costs a
            // single request, and it is right even where it was voted on or saved elsewhere since the
            // feed was last refreshed. Its comments are on the same page, so their votes come too.
            final String storyId = String.valueOf(story.optInt("id"));
            ExecutorService accountReader = Executors.newSingleThreadExecutor();
            Future<?> accountState = accountReader.submit(new Runnable() {
                @Override
                public void run() {
                    readAccountListings();
                    readSaved(storyId);
                }
            });

            fetched.set(0);

            // Only the thread the linked comment belongs to is served, which is what the app shows a
            // link to a Reddit comment as. It is served from the top of that thread rather than from
            // the comment itself, so that every comment in it sits at the depth it belongs to and the
            // one the link named is reached by the replies above it.
            List<Integer> shown = root == null
                    ? childIds(story)
                    : Collections.singletonList(root);
            long start = System.currentTimeMillis();
            Map<Integer, JSONObject> loaded;
            try {
                loaded = loadComments(shown, 0);

                try {
                    accountState.get(THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    // Shown with what was already known about the account, rather than not at all.
                }
            } finally {
                accountReader.shutdownNow();
            }
            Log.d(LOG_TAG, "Comments: " + Math.min(fetched.get(), MAX_COMMENTS) + " requested in "
                    + (System.currentTimeMillis() - start) + " ms");

            // Opening a post rewrites its row, so the thumbnail found when the post list was built is
            // reused; without it the row would be rewritten with none.
            JSONArray postChildren = new JSONArray();
            postChildren.put(toChild(story, knownThumbnails));

            JSONArray comments = buildComments(shown, loaded, "t3_" + pendingStory, 0);

            JSONArray response = new JSONArray();
            response.put(listing(postChildren));
            response.put(listing(comments));

            return response.toString().getBytes("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Replaces the rules response with the site's guidelines.
     *
     * <p>The app renders each rule as a heading followed by its text, so the whole page is returned as
     * a single rule, converted from the site's html to the Markdown the app renders.
     */
    @Override
    protected byte[] toRules(byte[] data) {
        try {
            boolean show = "Show".equalsIgnoreCase(currentSort);
            String title = show ? "Show HN Rules" : "Hacker News Guidelines";

            JSONObject rule = new JSONObject();
            rule.put("short_name", title);
            // The page opens with its own title, which the heading above it already says.
            rule.put("description", withoutHeading(readPage(data), show ? "Show HN" : title));

            JSONArray rules = new JSONArray();
            rules.put(rule);

            JSONObject response = new JSONObject();
            response.put("rules", rules);

            return response.toString().getBytes("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** Replaces the wiki response with the site's FAQ. */
    @Override
    protected byte[] toWiki(byte[] data) {
        try {
            // The page shown is the one the link asked for, which titles it and names the heading it
            // opens with. A link to a section of the FAQ is served that section on its own.
            String section = sectionOf(pendingWikiPage);
            String page = section == null
                    ? readPage(data)
                    : readSection(data, section);

            // A whole page opens with its own name, which the heading above it already says. A section
            // opens with the question it answers, which the heading says in shorter words, so that is
            // dropped too rather than being matched against.
            if (section == null) {
                String title = wikiPageTitle(pendingWikiPage);
                page = withoutHeading(page, title == null ? wikiTitle() : title);
            } else {
                page = withoutLeadingHeading(page);
            }

            JSONObject content = new JSONObject();
            content.put("content_md", page);

            JSONObject response = new JSONObject();
            response.put("kind", "wikipage");
            response.put("data", content);

            return response.toString().getBytes("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads one of the site's pages as Markdown.
     *
     * <p>The pages are a table wrapping a run of paragraphs and bold headings, so the cell holding
     * them is taken and converted the same way comment bodies are.
     */
    private String readPage(byte[] data) throws Exception {
        String html = new String(data, "UTF-8");

        // The page's content sits between the banner image and the footer.
        int start = html.indexOf("</a>");
        int end = html.lastIndexOf("</td>");
        if (start != -1 && end > start) {
            html = html.substring(start + "</a>".length(), end);
        }

        // Headings are bold on their own line; make them headings so they stand out when rendered.
        html = html.replaceAll("(?i)<b>(.*?)</b>", "\n\n**$1**\n");

        // The pages link to each other, and to sections within themselves, by file name. Those are
        // rewritten to the wiki pages this sheet serves them as, so following one opens it here rather
        // than handing the address to a browser. The section a link named is dropped, since a page is
        // shown whole and there is nothing to scroll to.
        //
        // Done before the remaining relative links are made absolute, so that these are not turned
        // into addresses off the site instead.
        // A link naming a section of the FAQ is served that section on its own, so that it lands on
        // what it named rather than at the top of the whole page. Taken before the links to a page as
        // a whole, which the same file names would otherwise match.
        html = linkToWikiSection(html, "newsfaq\\.html");
        html = linkToWikiSection(html, "");

        html = linkToWikiPage(html, "newsfaq\\.html", FAQ_PAGE);
        html = linkToWikiPage(html, "newsguidelines\\.html", GUIDELINES_PAGE);
        html = linkToWikiPage(html, "showhn\\.html", SHOW_PAGE);
        html = linkToWikiPage(html, "formatdoc", FORMAT_PAGE);

        // The pages link to each other by file name, which means nothing once the page is read here.
        // Attributes are quoted with either kind of quote, so both are matched.
        html = html.replaceAll("(?i)href=[\"'](?!https?://)([^\"']+)[\"']", "href=\"" + NEWS_HOST + "$1\"");

        String markdown = HackerNewsMarkdown.fromHtml(html);

        // A backslash the page shows as text, such as the one escaping an asterisk in the formatting
        // help, is read as escaping whatever follows it and disappears. Doubling it leaves one to
        // read. Done before the escaping below, so the backslashes that adds are not doubled too.
        markdown = markdown.replace("\\", "\\\\");

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
     * Drops the title a page opens with, which the heading above it already says.
     *
     * <p>The pages open with their own name as a bold line, and the guidelines follow it with a second
     * one naming the section, so a run of them is taken rather than only the first.
     */
    private static String withoutHeading(String page, String heading) {
        String text = page;
        String bold = "**" + heading + "**";
        if (text.startsWith(bold)) {
            text = text.substring(bold.length()).trim();
        }
        return text;
    }

    /**
     * Points the links naming one of the site's pages at the wiki page it is served as.
     *
     * <p>The section a link named is dropped along with the file name, since the page is shown whole.
     *
     * @param page the file name as a regular expression, which is matched with or without a section.
     */
    private String linkToWikiPage(String html, String page, String name) {
        return html.replaceAll(
                "(?i)href=[\"']([^\"']*/)?" + page + "(#[^\"']*)?[\"']",
                "href=\"" + wikiPageAddress(name) + "\"");
    }

    /**
     * Reads one section of a page, as the run of markup its heading introduces.
     *
     * <p>The sections are marked by an id on the element opening each, so a section is what lies
     * between its own and the next one. Its heading is kept aside to title the sheet showing it, and
     * dropped from the text so it is not said twice.
     *
     * @param section the id naming the section.
     * @return that section as Markdown, or the whole page when it holds no such section.
     */
    private String readSection(byte[] data, String section) throws Exception {
        String html = new String(data, "UTF-8");

        Matcher marks = Pattern
                .compile("(?i)<(?:b|p)[^>]*\\bid=[\"']([^\"']+)[\"'][^>]*>")
                .matcher(html);

        int start = -1;
        while (marks.find()) {
            if (section.equalsIgnoreCase(marks.group(1))) {
                start = marks.start();
                break;
            }
        }

        if (start == -1) {
            return readPage(data);
        }

        // A section runs until the next marked one begins. Not every heading is marked - a question
        // may be followed by further ones that share its subject, which belong with it - so the end is
        // the next marked heading, or where a marked paragraph is reached, the heading that introduces
        // it rather than the paragraph itself.
        int end = html.length();

        Matcher heading = Pattern
                .compile("(?i)<b[^>]*\\bid=[\"'][^\"']+[\"'][^>]*>")
                .matcher(html);
        if (heading.find(start + 1)) {
            end = heading.start();
        }

        Matcher paragraph = Pattern
                .compile("(?i)<p[^>]*\\bid=[\"'][^\"']+[\"'][^>]*>")
                .matcher(html);
        if (paragraph.find(start + 1) && paragraph.start() < end) {
            end = paragraph.start();

            // The marked paragraph continues the question above it, so that question starts the next
            // section rather than this one keeping it.
            Matcher bold = Pattern.compile("(?i)<b\\b[^>]*>").matcher(html);
            int from = start + 1;
            while (bold.find(from) && bold.start() < paragraph.start()) {
                end = bold.start();
                from = bold.start() + 1;
            }
        }

        // Wrapped in the markers the page reader looks for its content between, since a section on its
        // own carries neither the banner it follows nor the footer it precedes.
        return readPage(("</a>" + html.substring(start, end) + "</td>").getBytes("UTF-8"));
    }

    /** Drops whatever bold line a page opens with, whatever it says. */
    private static String withoutLeadingHeading(String page) {
        Matcher heading = Pattern.compile("\\A\\*\\*.*?\\*\\*").matcher(page);
        return heading.lookingAt() ? page.substring(heading.end()).trim() : page;
    }

    /**
     * Points the links naming a section of the FAQ at the page serving that section.
     *
     * @param page the file name as a regular expression, empty for a link within the page being read.
     */
    private String linkToWikiSection(String html, String page) {
        StringBuffer out = new StringBuffer();
        Matcher matcher = Pattern
                .compile("(?i)href=[\"']([^\"']*/)?" + page + "#([A-Za-z0-9_-]+)[\"']")
                .matcher(html);

        while (matcher.find()) {
            String address = wikiPageAddress(SECTION_PREFIX + matcher.group(2));
            matcher.appendReplacement(out, Matcher.quoteReplacement("href=\"" + address + "\""));
        }

        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * The address one of the site's pages is served under.
     *
     * <p>Written as a wiki address of the feed, which is what the app opens in this sheet: it routes a
     * link containing both a Reddit host and a wiki path there, and reads the page out of it.
     */
    private String wikiPageAddress(String name) {
        return wikiAddress(name);
    }

    /**
     * An item and everything it hangs from, ending with the story.
     *
     * <p>Hacker News numbers stories and comments alike and writes links to both the same way, so what
     * an id refers to is only known by asking. A comment names its parent, which is followed up to the
     * story, collecting the thread it belongs to on the way.
     *
     * @param id the item a link named.
     * @return the item, its parents and the story, or empty if any of them could not be read.
     */
    private static List<String> ancestryOf(String id) {
        List<String> ancestry = new ArrayList<>();
        String current = id;

        // Bounded, so that an item whose parents cannot be read does not walk forever.
        for (int step = 0; step < MAX_COMMENT_DEPTH; step++) {
            try {
                String body = get(API + "item/" + current + ".json");
                if (body == null) {
                    return Collections.emptyList();
                }

                JSONObject item = new JSONObject(body);
                ancestry.add(current);

                // A story is the end of the walk; anything else names what it hangs from.
                if (!"comment".equals(item.optString("type"))) {
                    return ancestry;
                }

                long parent = item.optLong("parent");
                if (parent == 0) {
                    return ancestry;
                }

                current = String.valueOf(parent);
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }

        return Collections.emptyList();
    }

    /**
     * Fetches ids and their replies, by id, for {@link #buildComments} to put together.
     *
     * <p>Hacker News serves one comment per request, so a thread costs one request per comment rather
     * than the single request a Reddit thread costs. Each comment's replies are asked for as soon as
     * the comment arrives, rather than a level at a time, so that no request waits on the slowest of
     * its level. The tree is
     * bounded by {@link #MAX_COMMENT_DEPTH} and {@link #MAX_COMMENTS} so that a large thread cannot
     * spend an unbounded number of requests.
     */
    private Map<Integer, JSONObject> loadComments(List<Integer> ids, int depth) throws Exception {
        final Map<Integer, JSONObject> comments = new ConcurrentHashMap<>();
        final ExecutorService executor = Executors.newFixedThreadPool(COMMENT_THREADS);
        // Every request in flight is a party, so the thread has loaded once none are left.
        final Phaser loading = new Phaser(1);

        try {
            for (Integer id : ids) {
                fetchComment(id, depth, comments, executor, loading);
            }
            loading.awaitAdvanceInterruptibly(
                    loading.arrive(), THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Shown with what has loaded, rather than not at all.
        } finally {
            executor.shutdownNow();
        }

        return comments;
    }

    /** Requests one comment, and then each of its replies as soon as it has arrived. */
    private static void fetchComment(
            final int id,
            final int depth,
            final Map<Integer, JSONObject> comments,
            final ExecutorService executor,
            final Phaser loading
    ) {
        if (depth > MAX_COMMENT_DEPTH || fetched.incrementAndGet() > MAX_COMMENTS) {
            return;
        }

        loading.register();
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject comment = fetchItem(id);
                        if (comment != null) {
                            comments.put(id, comment);
                            for (Integer reply : childIds(comment)) {
                                fetchComment(reply, depth + 1, comments, executor, loading);
                            }
                        }
                    } finally {
                        loading.arriveAndDeregister();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // The thread stopped being waited on, so nothing more is asked for.
            loading.arriveAndDeregister();
        }
    }

    /**
     * Puts fetched comments together into the tree Sync parses, in the order their ids are ranked.
     *
     * <p>A comment that was not fetched, because it failed to load or a limit was reached, is left
     * out along with its replies.
     */
    private JSONArray buildComments(
            List<Integer> ids,
            Map<Integer, JSONObject> comments,
            String parent,
            int depth
    ) throws Exception {
        JSONArray children = new JSONArray();

        for (Integer commentId : ids) {
            JSONObject comment = comments.get(commentId);
            if (comment == null) {
                continue;
            }

            // Deleted comments keep their replies, which are still worth showing.
            String author = comment.optString("by", "[deleted]");
            String text = comment.optString("text", "");

            String id = String.valueOf(comment.optInt("id"));
            served.add("t1_" + id);
            noteAuthor(author);

            JSONObject data = new JSONObject();
            data.put("id", id);
            data.put("name", "t1_" + id);
            data.put("parent_id", parent);
            data.put("link_id", "t3_" + pendingStory);
            data.put("subreddit", subreddit());
            data.put("subreddit_id", subredditId());
            data.put("author", author);
            data.put("body", HackerNewsMarkdown.fromHtml(text));
            data.put("created_utc", comment.optLong("time"));
            data.put("depth", depth);

            // Sharing a comment builds reddit.com followed by this, which was empty for Hacker News
            // comments. The full Hacker News address is stored so the reddit.com prefix can be
            // stripped back off when the link is shared.
            data.put("permalink", itemUrl(id));

            // Hacker News does not expose per comment scores.
            data.put("score", 1);
            data.put("score_hidden", true);
            data.put("likes", likesOf(id));
            data.put("saved", isSaved(id));
            data.put("edited", false);
            // Archived and locked stop the app offering to vote or reply, which it can do once an
            // account has been given to act on the site as.
            data.put("locked", !hasAccount());
            data.put("archived", !hasAccount());
            data.put("gilded", 0);
            data.put("controversiality", 0);
            data.put("distinguished", JSONObject.NULL);
            data.put("stickied", false);

            JSONArray replies = buildComments(childIds(comment), comments, "t1_" + id, depth + 1);
            // Reddit sends an empty string rather than an empty listing for a comment with no replies.
            data.put("replies", replies.length() == 0 ? "" : listing(replies));

            JSONObject child = new JSONObject();
            child.put("kind", "t1");
            child.put("data", data);
            children.put(child);
        }

        return children;
    }

    /**
     * The story an item belongs to, given the item itself.
     *
     * <p>Each item says what it is, so a story is recognised as soon as one is read. A comment names
     * only what it hangs from, which may be another comment, so that is followed up until the story
     * is reached.
     *
     * <p>Takes the item rather than its id, since the caller already has it - reading it again would
     * be a request spent asking what is already to hand.
     */
    private static JSONObject storyOf(JSONObject item) {
        JSONObject current = item;

        // Bounded, so that an item whose parents cannot be read does not walk forever.
        for (int step = 0; step < MAX_COMMENT_DEPTH; step++) {
            if (!"comment".equals(current.optString("type"))) {
                return current;
            }

            long parent = current.optLong("parent");
            if (parent == 0) {
                return null;
            }

            current = fetchItem((int) parent);
            if (current == null) {
                return null;
            }
        }

        return null;
    }

    /**
     * The story each of a page's comments was written on, by the comment it belongs to.
     *
     * <p>Each item says whether it is a comment, so which of them need a story is known without
     * asking. The walks for a page are made together rather than one comment after another.
     */
    private static Map<String, JSONObject> storiesOf(List<JSONObject> items) {
        List<JSONObject> comments = new ArrayList<>();
        for (JSONObject item : items) {
            if ("comment".equals(item.optString("type"))) {
                comments.add(item);
            }
        }

        Map<String, JSONObject> stories = new LinkedHashMap<>();
        for (JSONObject found : parallel(comments, new Fetcher<JSONObject, JSONObject>() {
            @Override
            public JSONObject fetch(JSONObject comment) {
                JSONObject story = storyOf(comment);
                if (story == null) {
                    return null;
                }

                // Carried back with the comment it was found for, so that each is matched to its own
                // rather than by position.
                try {
                    JSONObject found = new JSONObject();
                    found.put("comment", String.valueOf(comment.optInt("id")));
                    found.put("story", story);
                    return found;
                } catch (Exception e) {
                    return null;
                }
            }
        })) {
            stories.put(found.optString("comment"), found.optJSONObject("story"));
        }
        return stories;
    }

    /**
     * Maps one of a user's comments onto the Reddit comment fields a profile lists.
     *
     * <p>Reddit lists a profile's comments alongside its posts, each named with the post it was
     * written on, and Sync reads them as comments from the same listing.
     */
    private JSONObject toCommentChild(JSONObject comment, Map<String, JSONObject> stories)
            throws Exception {
        String id = String.valueOf(comment.optInt("id"));

        JSONObject story = stories.get(id);
        String storyId = story == null ? null : String.valueOf(story.optInt("id"));

        served.add("t1_" + id);
        noteAuthor(comment.optString("by", ""));

        String text = comment.optString("text", "");

        JSONObject data = new JSONObject();
        data.put("id", id);
        data.put("name", "t1_" + id);
        data.put("subreddit", subreddit());
        data.put("subreddit_id", subredditId());
        data.put("author", comment.optString("by", "[deleted]"));
        data.put("body", text.isEmpty() ? "" : HackerNewsMarkdown.fromHtml(text));
        data.put("created_utc", comment.optLong("time"));

        // The post the comment was written on, which the profile names each comment with. A comment
        // whose story could not be read is still listed, under the site's own name.
        data.put("link_id", storyId == null ? "" : "t3_" + storyId);
        data.put("link_title", story == null ? feedName() : story.optString("title", feedName()));
        data.put("link_author", story == null ? "" : story.optString("by", ""));
        data.put("link_url", storyId == null ? siteUrl() : REDDIT_HOSTS[0] + permalink(storyId));

        // The address the comment is served under, so that sharing it names the site rather than the
        // address this patch serves it at.
        data.put("permalink", itemUrl(id));

        // Hacker News does not expose per comment scores.
        data.put("score", 1);
        data.put("score_hidden", true);
        data.put("likes", likesOf(id));
        data.put("saved", isSaved(id));
        data.put("edited", false);
        // Archived and locked stop the app offering to vote or reply, which it can do once an account
        // has been given to act on the site as.
        data.put("locked", !hasAccount());
        data.put("archived", !hasAccount());
        data.put("gilded", 0);
        data.put("controversiality", 0);
        data.put("distinguished", JSONObject.NULL);
        data.put("stickied", false);
        data.put("num_comments", 0);

        JSONObject child = new JSONObject();
        child.put("kind", "t1");
        child.put("data", data);
        return child;
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

    /** Fetches each story, preserving the ranking order of the ids. */
    private static List<JSONObject> fetchItems(List<Integer> ids) {
        return parallel(ids, new Fetcher<Integer, JSONObject>() {
            @Override
            public JSONObject fetch(Integer id) {
                return fetchItem(id);
            }
        });
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

    /**
     * Maps one Hacker News story onto the Reddit post fields Sync reads, with the thumbnail Reddit
     * holds for its link when there is one.
     */
    private JSONObject toChild(JSONObject story, Map<String, Preview> thumbnails) throws Exception {
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

        // Noted as one of the site's users, so that tapping the name opens their profile here rather
        // than the Reddit account that happens to share it.
        noteAuthor(story.optString("by", ""));

        JSONObject data = new JSONObject();
        data.put("id", hnId);
        data.put("name", "t3_" + hnId);
        data.put("subreddit", subreddit());
        data.put("subreddit_id", subredditId());
        data.put("title", story.optString("title", ""));
        data.put("author", story.optString("by", "[deleted]"));
        data.put("created_utc", story.optLong("time"));
        data.put("score", story.optInt("score"));
        data.put("num_comments", story.optInt("descendants"));
        data.put("url", url);
        data.put("domain", isSelf ? "self." + subreddit() : domainOf(url));
        data.put("permalink", permalink(hnId));
        data.put("is_self", isSelf);
        data.put("selftext", selftext);
        data.put("selftext_raw", selftext);

        // What Reddit holds for the same link, where it has anything; a story it has never seen is
        // left without, which the app renders as a post with no image.
        Preview preview = isSelf ? null : thumbnails.get(story.optString("url", ""));
        data.put("thumbnail", isSelf ? "self" : preview == null ? "" : preview.thumbnail);
        addPreview(data, preview);

        // Sync reads these for the post footer and action states. Hacker News exposes no equivalent,
        // so they are set to the values that render a plain, unvoted, unsaved post.
        data.put("likes", likesOf(hnId));
        data.put("saved", isSaved(hnId));
        data.put("hidden", false);
        data.put("visited", false);
        data.put("stickied", false);
        data.put("over_18", false);
        data.put("spoiler", false);
        // Archived and locked stop the app offering to vote or reply, which it can do once an account
        // has been given to act on the site as.
        data.put("locked", !hasAccount());
        data.put("archived", !hasAccount());
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

    /**
     * The items the account has marked as favourites, which is what a saved post or comment is here.
     *
     * <p>The site says whether one of its items is a favourite only on the account's own favourites
     * pages, not on the item itself, so those are read once and remembered. Read for posts and
     * comments separately, which the site lists on separate pages.
     */
    private static final Set<String> favorites =
            Collections.synchronizedSet(new java.util.HashSet<String>());

    /** Whether the account's listings have been read, which is done once per refresh rather than per item. */
    private static volatile boolean accountListingsRead;

    /** The address the account's favourite posts and comments are listed on. */
    private static String favoritesUrl(boolean comments) {
        return NEWS_HOST + "favorites?id=" + accountName() + (comments ? "&comments=t" : "");
    }

    /** The address the account's upvoted posts and comments are listed on. */
    private static String upvotedUrl(boolean comments) {
        return NEWS_HOST + "upvoted?id=" + accountName() + (comments ? "&comments=t" : "");
    }

    /**
     * Reads the account's favourites, so that a saved item can be shown as saved.
     *
     * <p>Read once and kept, since a listing asks about every item on it and the answer only changes
     * when something is saved here - which is noted as it is done rather than by reading again.
     */
    private static void readAccountListings() {
        if (accountListingsRead || !hasAccount()) {
            return;
        }

        accountListingsRead = true;

        // Read into sets of their own and swapped in, so that what is shown never briefly holds
        // neither the old answer nor the new one while the pages are being read.
        //
        // Swapped in only where they were read in full: a listing the site would not answer says
        // nothing about what the account has done, so what is already known is kept rather than
        // being cleared for an answer that was never given.
        //
        // A failed read is not asked for again here. These are read once per refresh but asked about
        // once per item, so retrying on the spot would sign in and re-read the listings for every
        // post in the feed while the site kept saying no. The next refresh asks again instead.
        //
        // The site lists what the account has upvoted in the same way as its favourites, so those are
        // read here too and every post in a listing shows its arrow without its page having to be
        // opened.
        //
        // The four listings are read at the same time rather than one after another. Each is its own
        // chain of pages, read a page at a time behind its "More" link, so that is the most the reads
        // can overlap. Signed in first, so that the four do not each sign in at once.
        if (session() == null) {
            return;
        }

        List<String> listings = Arrays.asList(
                favoritesUrl(false), favoritesUrl(true), upvotedUrl(false), upvotedUrl(true));
        final Map<String, Set<String>> readFrom = new ConcurrentHashMap<>();
        parallel(listings, new Fetcher<String, Boolean>() {
            @Override
            public Boolean fetch(String url) {
                Set<String> read = new java.util.HashSet<String>();
                if (!readListingInto(url, read)) {
                    return null;
                }
                readFrom.put(url, read);
                return Boolean.TRUE;
            }
        }, THREAD_TIMEOUT_MS);

        Set<String> favoritePosts = readFrom.get(favoritesUrl(false));
        Set<String> favoriteComments = readFrom.get(favoritesUrl(true));
        if (favoritePosts != null && favoriteComments != null) {
            synchronized (favorites) {
                favorites.clear();
                favorites.addAll(favoritePosts);
                favorites.addAll(favoriteComments);
            }
        }

        Set<String> upvotedPosts = readFrom.get(upvotedUrl(false));
        Set<String> upvotedComments = readFrom.get(upvotedUrl(true));
        if (upvotedPosts == null || upvotedComments == null) {
            return;
        }
        Set<String> upvoted = new java.util.HashSet<String>(upvotedPosts);
        upvoted.addAll(upvotedComments);

        synchronized (votes) {
            // Only the upvotes read here are replaced. A downvote is not listed anywhere, so one noted
            // from a post's own page is kept rather than being dropped for not appearing here.
            for (java.util.Iterator<Map.Entry<String, Integer>> known = votes.entrySet().iterator();
                    known.hasNext(); ) {
                if (known.next().getValue() > 0) {
                    known.remove();
                }
            }

            for (String id : upvoted) {
                votes.put(id, 1);
            }
        }
    }

    /**
     * Reads the account's favourites again the next time one is asked about.
     *
     * <p>They are read once per page of the feed rather than per item, so that a listing asking about
     * thirty posts costs the same as one - and read again when the feed is opened or refreshed, so a
     * favourite added on the site itself shows up here.
     */
    @Override
    protected void refreshAccountState() {
        accountListingsRead = false;

        // The votes are read again along with the favourites, so what was read before is left in place
        // rather than being cleared here - it is replaced as the listings are read.
    }

    /**
     * Reads the ids of everything one of the account's own listings holds.
     *
     * <p>The site lists thirty at a time and offers the rest behind a "More" link, so the pages are
     * followed rather than only the first being read. Bounded, so that a link that somehow leads back
     * on itself cannot be followed forever.
     *
     * <p>Read as the account, since these listings are shown only to the account they belong to,
     * unless it has said its favourites may be public.
     *
     * @return whether the listing was actually read. A listing the site answered as though signed
     *     out holds none of the account's items, but says nothing about what the account has done -
     *     which is not the same as it having done nothing, so the two are told apart here rather
     *     than an empty answer being taken for an emptied listing.
     */
    private static boolean readListingInto(String from, Set<String> read) {
        String url = from;
        for (int page = 0; page < MAX_ACCOUNT_PAGES && url != null; page++) {
            String body = getAsAccount(url, session(), ACCOUNT_PAGE);
            if (body == null) {
                return false;
            }

            // The site answers a listing asked for without a session with its sign-in form, under an
            // ordinary 200 rather than a refusal, so the form is what says the session has lapsed.
            // Recognised by the field the form is built from rather than by the words around it,
            // which are prose and could be reworded.
            if (isSignInPage(body)) {
                forgetSession();
                return false;
            }

            Matcher rows = Pattern
                    .compile("(?i)<tr[^>]*\\bclass=[\"'][^\"']*\\bathing\\b[^\"']*[\"'][^>]*\\bid=[\"']?(\\d+)")
                    .matcher(body);
            while (rows.find()) {
                read.add(rows.group(1));
            }

            url = moreLinkIn(body);
        }

        return true;
    }

    /**
     * Whether a page the site answered with is its sign-in form rather than what was asked for.
     *
     * <p>Asked of the account's own listings, which the site answers this way once a session has
     * lapsed - with an ordinary 200, so that nothing else marks it out as a refusal.
     */
    private static boolean isSignInPage(String page) {
        return Pattern
                .compile("(?i)<input[^>]*\\bname=[\"']acct[\"']")
                .matcher(page)
                .find();
    }

    /** How many pages of one of the account's listings are followed, far past what one is likely to hold. */
    private static final int MAX_ACCOUNT_PAGES = 20;

    /** How much of one of those pages is read, which is a listing rather than a page of comments. */
    private static final int ACCOUNT_PAGE = 262144;

    /**
     * The address the "More" link at the foot of a listing points at, or {@code null} where it has
     * none.
     *
     * <p>The link is written relative to the site, so it is made absolute the same way the pages'
     * other links are.
     */
    private static String moreLinkIn(String page) {
        Matcher more = Pattern
                .compile("(?i)<a[^>]*\\bhref=[\"']([^\"']+)[\"'][^>]*\\bclass=[\"']morelink[\"']")
                .matcher(page);
        if (!more.find()) {
            return null;
        }

        String href = more.group(1).replace("&amp;", "&");
        return href.startsWith("http") ? href : NEWS_HOST + href;
    }

    /** The address the account signs in at, which hands back the session its pages are read with. */
    private static final String LOGIN_URL = NEWS_HOST + "login";

    /** The session the site's pages are read as, kept so that each read does not sign in again. */
    private static volatile String session;

    /**
     * The session to read the site's pages as, signing in for one where there is none.
     *
     * <p>Kept until it stops working, which is noticed by a page coming back as though signed out.
     */
    private static String session() {
        String current = session;
        if (current != null) {
            return current;
        }

        Map<String, String> fields = credentials();
        if (fields == null) {
            return null;
        }

        fields.put("goto", "news");
        current = signIn(LOGIN_URL, fields);
        session = current;
        return current;
    }

    /** Forgets the session, so that the next page read signs in again. */
    private static void forgetSession() {
        session = null;
    }

    /**
     * How much of an item's page is read for what the account has done to it.
     *
     * <p>Whether it is a favourite is said within the first few thousand characters, but how the
     * account voted is said against every comment as well as against the post, so enough of the page
     * is read to carry a thread's comments rather than only its head. A page longer than this is one
     * whose later comments keep the votes the listings gave them.
     */
    private static final int ITEM_PAGE = 1048576;

    /**
     * Reads from the site whether one item is a favourite, and remembers the answer.
     *
     * <p>Asked of the item's own page, which says so only for the account that asks - so it is asked
     * for as that account. Done for a single item when it is opened, rather than for a listing, which
     * reads the account's favourites in one go instead.
     *
     * @return whether it could be read, the answer being remembered rather than returned.
     */
    static boolean readSaved(String id) {
        Map<String, String> fields = credentials();
        if (fields == null) {
            return false;
        }

        String session = session();
        if (session == null) {
            return false;
        }

        String page = getAsAccount(NEWS_HOST + "item?id=" + id, session, ITEM_PAGE);
        if (page == null) {
            return false;
        }

        // The link offering to make it a favourite is written as "favorite", and as "un-favorite" for
        // one it already is, so which is offered says which it is.
        Matcher link = Pattern
                .compile("(?i)<a[^>]*\\bhref=[\"']fave\\?id=" + Pattern.quote(id)
                        + "[^\"']*[\"'][^>]*>([^<]*)</a>")
                .matcher(page);
        if (!link.find()) {
            // A page with no such link is one the site answered as though signed out, so the session
            // is dropped and the next read signs in again.
            forgetSession();
            return false;
        }

        noteSaved(id, link.group(1).trim().toLowerCase(java.util.Locale.ENGLISH).startsWith("un"));

        // The same page says how the account voted, on this item and on the comments under it, so
        // that is read from it rather than asked for separately.
        readVotesIn(page);
        return true;
    }

    /**
     * How the account has voted on an item, where it has.
     *
     * <p>The site says so only on a page read as that account, in the same way as a favourite, so the
     * two are read together from the one page rather than a page each.
     */
    private static final Map<String, Integer> votes =
            Collections.synchronizedMap(new java.util.HashMap<String, Integer>());

    /**
     * How a vote reads as Reddit writes it, which is what the app draws the arrows from.
     *
     * <p>Reddit says an upvote as true, a downvote as false and no vote as nothing at all.
     */
    private static Object likesOf(String id) {
        int vote = voteOn(id);
        if (vote == 0) {
            return JSONObject.NULL;
        }
        return vote > 0 ? Boolean.TRUE : Boolean.FALSE;
    }

    /** How the account voted on an item: 1 up, -1 down, 0 not at all. */
    static int voteOn(String id) {
        if (!hasAccount()) {
            return 0;
        }

        // Read together with the favourites, since the site lists both the same way and a listing asks
        // about each of its items in turn.
        readAccountListings();

        Integer vote = votes.get(id);
        return vote == null ? 0 : vote;
    }

    /** Notes how an item was voted on, so a listing read afterwards agrees with what was done. */
    private static void noteVoted(String id, int vote) {
        if (vote == 0) {
            votes.remove(id);
        } else {
            votes.put(id, vote);
        }
    }

    /**
     * Reads out of a page how the account has voted on the items it lists.
     *
     * <p>The site offers to take a vote back where one was cast, under the one id either way - what it
     * reads is what says which way the vote went, "unvote" for an upvote and "undown" for a downvote.
     * Read for every item the page carries rather than only the one asked about, since a thread's
     * comments are all on it.
     *
     * <p>What the page says stands for every item it carries, including the ones it says nothing
     * about: an item shown with a vote still to cast is one the account has not voted on, whatever an
     * earlier listing said. So the page's own items are forgotten before its votes are read back in,
     * which is what lets a vote taken back on the site show as taken back here. Only the items the
     * page actually carries are forgotten, so a thread longer than is read keeps what the listings
     * gave the comments below the cut.
     */
    private static void readVotesIn(String page) {
        // Every item on the page carries one of these, whether or not it has been voted on: "up_" for
        // a vote still to cast, "un_" for one already cast that can be taken back. Together they are
        // the items the page is the answer for.
        Matcher shown = Pattern
                .compile("(?i)<a[^>]*\\bid=[\"'](?:up|un)_(\\d+)[\"']")
                .matcher(page);

        Set<String> covered = new java.util.HashSet<String>();
        while (shown.find()) {
            covered.add(shown.group(1));
        }

        Matcher taken = Pattern
                .compile("(?i)<a[^>]*\\bid=[\"']un_(\\d+)[\"'][^>]*>([^<]*)</a>")
                .matcher(page);

        Map<String, Integer> cast = new java.util.HashMap<String, Integer>();
        while (taken.find()) {
            String label = taken.group(2).trim().toLowerCase(java.util.Locale.ENGLISH);
            cast.put(taken.group(1), label.startsWith("undown") ? -1 : 1);
        }

        // Swapped in together, so that what is shown never holds neither the page's answer nor the
        // one it replaces while the two are being reconciled.
        synchronized (votes) {
            votes.keySet().removeAll(covered);
            votes.putAll(cast);
        }
    }

    /** Whether one of the site's items is one the account has saved. */
    static boolean isSaved(String id) {
        if (!hasAccount()) {
            return false;
        }

        readAccountListings();
        return favorites.contains(id);
    }

    /** Notes an item as saved or not, so a listing read afterwards agrees with what was done. */
    private static void noteSaved(String id, boolean saved) {
        if (saved) {
            favorites.add(id);
        } else {
            favorites.remove(id);
        }
    }

    /**
     * Carries out on Hacker News the write the request was going to ask Reddit for.
     *
     * <p>Which write it is is read from the parameters: a vote names the direction it is cast in, a
     * reply carries the text of it, and a save is what is left. Reddit's fullname for the item is
     * numbered the same way the site numbers its own, so the number is taken out of it.
     */
    @Override
    protected WriteResult write(Map<String, String> params, String id) {
        String item = id.indexOf('_') == -1 ? id : id.substring(id.indexOf('_') + 1);

        String text = params.get("text");
        if (text != null) {
            return comment(item, text);
        }

        String direction = params.get("dir");
        if (direction != null) {
            // Reddit casts a vote as 1, -1 and 0, which are the site's up, down and taking it back.
            if ("1".equals(direction)) {
                return vote(item, "up");
            }
            if ("-1".equals(direction)) {
                return vote(item, "down");
            }
            return vote(item, null);
        }

        // Saving is the only write left that names an item, and the request says which way round it
        // is by the address it was going to be sent to rather than by a parameter.
        return favorite(item, pendingSave);
    }

    /**
     * Whether the save being made is a save rather than the undoing of one.
     *
     * <p>Sync sends both to different addresses with the same parameters, so which it is is noted from
     * the address as it is built, in the same way the page offset for a listing is.
     */
    static volatile boolean pendingSave = true;

    @Override
    protected void notedSave(boolean saved) {
        pendingSave = saved;
    }

    // Acting on Hacker News as the account the settings name.

    /**
     * The site offers no token to act with, so each write is sent with the account's own name and
     * password, which is how its own pages and the other clients of it do the same thing.
     */
    private static final String VOTE_URL = NEWS_HOST + "vote";
    private static final String FAVORITE_URL = NEWS_HOST + "fave";
    private static final String COMMENT_URL = NEWS_HOST + "comment";

    /**
     * Votes on one of the site's items.
     *
     * @param id the item voted on.
     * @param direction the vote to cast, or {@code null} to take one back.
     * @return what came of it, which the app reports.
     */
    static WriteResult vote(String id, String direction) {
        Map<String, String> fields = credentials();
        if (fields == null) {
            return WriteResult.NO_ACCOUNT;
        }

        fields.put("id", id);
        fields.put("how", direction == null ? "un" : direction);

        WriteResult result = resultOf(post(VOTE_URL, fields), direction);

        // Noted so that a listing read afterwards agrees with what was just done.
        if (result == WriteResult.DONE) {
            noteVoted(id, direction == null ? 0 : ("down".equals(direction) ? -1 : 1));
        }

        return result;
    }

    /**
     * Marks one of the site's items as a favourite, which is what saving it is here.
     *
     * @param id the item saved.
     * @param saved whether it is being saved or unsaved.
     */
    static WriteResult favorite(String id, boolean saved) {
        Map<String, String> fields = credentials();
        if (fields == null) {
            return WriteResult.NO_ACCOUNT;
        }

        fields.put("id", id);
        // The site takes the removal of a favourite as the same request, marked as undone.
        if (!saved) {
            fields.put("un", "t");
        }

        WriteResult result = resultOf(post(FAVORITE_URL, fields), null);

        // Noted so that a listing read afterwards agrees with what was just done, rather than the
        // favourites having to be read again.
        if (result == WriteResult.DONE) {
            noteSaved(id, saved);
        }

        return result;
    }

    /**
     * Replies to one of the site's items.
     *
     * @param parent the item being replied to.
     * @param text the reply, as it was written.
     */
    static WriteResult comment(String parent, String text) {
        Map<String, String> fields = credentials();
        if (fields == null) {
            return WriteResult.NO_ACCOUNT;
        }

        fields.put("parent", parent);
        fields.put("text", text);

        return resultOf(post(COMMENT_URL, fields), null);
    }

    /** The account's details, as every write is sent with, or {@code null} where none was given. */
    private static Map<String, String> credentials() {
        if (!hasAccount()) {
            return null;
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("acct", accountName());
        fields.put("pw", accountPassword());
        return fields;
    }

    /**
     * What the site's answer to a write says came of it.
     *
     * <p>A write that was taken is answered by sending the browser onward. One that was refused is
     * answered with a page saying so, which is the same answer for a wrong password as for a vote the
     * account is not allowed to cast - so the two are told apart by what was being asked for.
     */
    private static WriteResult resultOf(String location, String direction) {
        if (location == null) {
            return WriteResult.FAILED;
        }

        if (!NOT_REDIRECTED.equals(location)) {
            return WriteResult.DONE;
        }

        // Downvoting is the one write the site refuses for an account that is otherwise fine, since
        // it offers those arrows only above a certain karma.
        return "down".equals(direction) ? WriteResult.NOT_ALLOWED : WriteResult.REFUSED;
    }

    // Acting on Hacker News as the account the settings name.
    //
    // The site offers no token to act with, so the account's own name and password are kept and
    // sent with each write, which is what its own pages and its other clients do.

    /** The screen the account's details are asked for on, which keeps the answers given on it. */
    private static final String ACCOUNT_SCREEN = "PreferencesCustomFeedAccountFragment";

    /** The settings the account's details are kept under, matching the patch's own names. */
    public static final String USERNAME_PREFERENCE = "custom_feed_username";
    public static final String PASSWORD_PREFERENCE = "custom_feed_password";

    /** The account's name, or {@code null} where none has been given. */
    protected static String accountName() {
        return setting(USERNAME_PREFERENCE);
    }

    /** The account's password, or {@code null} where none has been given. */
    protected static String accountPassword() {
        return setting(PASSWORD_PREFERENCE);
    }

    /** Whether an account has been given to act as. */
    protected static boolean hasAccount() {
        String name = accountName();
        String password = accountPassword();
        return name != null && !name.isEmpty() && password != null && !password.isEmpty();
    }

    /** One of the account's details, or {@code null} where it has not been given. */
    private static String setting(String key) {
        try {
            String value = t7.z.g(ACCOUNT_SCREEN).getString(key, null);
            return value == null || value.trim().isEmpty() ? null : value.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Shows one of the account's details on the row listing it, and asks for it again when tapped.
     *
     * <p>The app shows every settings dialog through one of its own, which only knows how to show a
     * list of choices, so a setting asking for text cannot be one of the kinds the app opens a dialog
     * for. These are plain rows instead, which ask for their value themselves.
     *
     * <p>The row is handed over already found, rather than being looked up here: the preference
     * library the app is built on is obfuscated, so the screen holding it cannot be named from here.
     *
     * @param row the row listing the setting, which the patch finds on the screen.
     * @param key the setting the row is listing.
     */
    @Override
    protected void bindSetting(Object row, String key) {
        if (!(row instanceof androidx.preference.Preference)) {
            return;
        }

        CustomFeedExtension feed = this;

        final androidx.preference.Preference preference = (androidx.preference.Preference) row;
        final boolean secret = PASSWORD_PREFERENCE.equals(key);
        final String title = feed.feedName() + (secret ? " password" : " username");
        final String settingKey = key;

        preference.D0(summaryOf(settingKey, secret, feed));
        preference.A0(new androidx.preference.Preference.d() {
            @Override
            public boolean a(androidx.preference.Preference tapped) {
                askFor(tapped, settingKey, title, secret, feed);
                return true;
            }
        });
    }

    /** What a setting shows for its value, which never shows the password itself. */
    private static String summaryOf(String key, boolean secret, CustomFeedExtension feed) {
        String value = setting(key);
        if (value == null) {
            return secret
                    ? "Sent to " + feed.feedName() + " with each vote, save or reply"
                    : "The account votes, saves and replies are made as";
        }

        return secret ? "Set" : value;
    }

    /** Asks for one of the account's details, and keeps what is given. */
    private static void askFor(
            final androidx.preference.Preference preference,
            final String key,
            String title,
            final boolean secret,
            final CustomFeedExtension feed) {
        final android.content.Context context = preference.k();

        final android.widget.EditText input = new android.widget.EditText(context);
        input.setSingleLine(true);
        if (secret) {
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        input.setText(setting(key));

        // Padded so the field is not against the edges of the dialog.
        int padding = Math.round(24 * context.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout wrapper = new android.widget.FrameLayout(context);
        wrapper.setPadding(padding, padding / 2, padding, 0);
        wrapper.addView(input);

        new android.app.AlertDialog.Builder(context)
                .setTitle(title)
                .setView(wrapper)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        store(key, input.getText().toString());
                        preference.D0(summaryOf(key, secret, feed));
                    }
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Clear", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        store(key, null);
                        preference.D0(summaryOf(key, secret, feed));
                    }
                })
                .show();
    }

    /** Keeps one of the account's details, or forgets it where nothing was given. */
    private static void store(String key, String value) {
        try {
            android.content.SharedPreferences.Editor editor = t7.z.g(ACCOUNT_SCREEN).edit();
            if (value == null || value.trim().isEmpty()) {
                editor.remove(key);
            } else {
                editor.putString(key, value.trim());
            }
            editor.apply();
        } catch (Exception e) {
            // A setting that cannot be kept leaves the account as it was.
        }
    }

    /**
     * Signs in and answers the session the site hands back, or {@code null} if it would not sign in.
     *
     * <p>The site's pages are read as a browser reads them, which means carrying the session it gives
     * out at sign in rather than sending the account's details with each page - those are only taken
     * on the forms that act on something.
     */
    protected static String signIn(String url, Map<String, String> fields) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            byte[] encoded = formBody(fields).getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(encoded.length);
            connection.getOutputStream().write(encoded);
            connection.getOutputStream().close();

            // A sign in that was taken sends the browser onward and hands back the session with it;
            // one that was refused answers the page saying so, with no session on it.
            connection.getResponseCode();
            for (int i = 0; ; i++) {
                String name = connection.getHeaderFieldKey(i);
                String value = connection.getHeaderField(i);
                if (name == null && value == null) {
                    return null;
                }
                if (name != null && name.equalsIgnoreCase("Set-Cookie") && value != null) {
                    // Kept as the pair the site names its session with, without what it says about
                    // how long it lasts.
                    int end = value.indexOf(';');
                    return end == -1 ? value : value.substring(0, end);
                }
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Reads a url as the signed in account, only as far as it is needed.
     *
     * <p>The site's pages carry what is being looked for near the top and then run to a megabyte of
     * comments, so the body is read up to a limit and the rest left unread - the connection is closed
     * without it, rather than the whole page being pulled down to look at its first few lines.
     *
     * @param session the session to read as, as it was handed back at sign in.
     * @param limit how many characters of the answer to read.
     */
    protected static String getAsAccount(String url, String session, int limit) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            if (session != null) {
                connection.setRequestProperty("Cookie", session);
            }

            InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            byte[] buffer = new byte[4096];
            int read;
            while (out.size() < limit && (read = in.read(buffer)) != -1) {
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

    /** The fields of a form, written the way a form is sent. */
    private static String formBody(Map<String, String> fields) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (field.getValue() == null) {
                continue;
            }
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(field.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(field.getValue(), "UTF-8"));
        }
        return body.toString();
    }

    protected static String post(String url, Map<String, String> fields) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            byte[] encoded = formBody(fields).getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(encoded.length);
            connection.getOutputStream().write(encoded);
            connection.getOutputStream().close();

            int status = connection.getResponseCode();
            String location = connection.getHeaderField("Location");

            // The site answers a taken write by sending the browser onward, and a refused one by
            // answering the page itself. Where it went is answered so the caller can tell which.
            return status == HttpURLConnection.HTTP_MOVED_TEMP && location != null
                    ? location
                    : NOT_REDIRECTED;
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Answered in place of a redirect, for a request the site answered with a page instead. */
    protected static final String NOT_REDIRECTED = "";
}
