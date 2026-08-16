package app.morphe.extension.syncforreddit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

            JSONArray postChildren = new JSONArray();
            // Opening a post rewrites its row, so the thumbnail found when the post list was built is
            // reused; without it the row would be rewritten with none.
            postChildren.put(toChild(story, knownThumbnails));

            fetched.set(0);

            // Only the thread the linked comment belongs to is served, which is what the app shows a
            // link to a Reddit comment as. It is served from the top of that thread rather than from
            // the comment itself, so that every comment in it sits at the depth it belongs to and the
            // one the link named is reached by the replies above it.
            List<Integer> shown = root == null
                    ? childIds(story)
                    : Collections.singletonList(root);
            JSONArray comments = fetchComments(shown, "t3_" + pendingStory, 0);

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

            JSONObject rule = new JSONObject();
            rule.put("short_name", show ? "Show HN Guidelines" : "Hacker News Guidelines");
            rule.put("description", readPage(data));

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
            String page = readPage(data);

            // The page opens with its own title, which the header above it already says.
            String heading = "**" + wikiTitle() + "**";
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
            return null;
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
     * Fetches ids and their replies, as Reddit comment objects.
     *
     * <p>Hacker News serves one comment per request, so a thread costs one request per comment rather
     * than the single request a Reddit thread costs. Each level is fetched concurrently, and the tree
     * is bounded by {@link #MAX_COMMENT_DEPTH} and {@link #MAX_COMMENTS} so that a large thread cannot
     * spend an unbounded number of requests.
     */
    private JSONArray fetchComments(List<Integer> ids, String parent, int depth) throws Exception {
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
        data.put("likes", JSONObject.NULL);
        data.put("saved", false);
        data.put("edited", false);
        data.put("locked", true);
        data.put("archived", true);
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
}
