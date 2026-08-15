package app.template.patches.reddit.customclients.sync.syncforreddit.customfeeds.hackernews

import app.template.patches.reddit.customclients.sync.syncforreddit.customfeeds.FeedSort
import app.template.patches.reddit.customclients.sync.syncforreddit.customfeeds.customFeedPatch

/**
 * The feeds Hacker News serves, offered in place of Reddit's sorts, each with the icon it is shown
 * with.
 *
 * Order is the order they are listed in. Matches the feeds `HackerNewsExtension` fetches, which reads
 * each back by the label it is offered under.
 *
 * The icons are drawables the app already draws in these same dialogs, so none has to be added and
 * each is known to load. The patch is compatible with a single app version, whose ids these are; the
 * drawable each one is is named alongside it.
 */
private val HACKER_NEWS_SORTS = listOf(
    // ic_star_outline_white_24dp
    FeedSort("Best", 0x7f0801d8),
    // outline_whatshot_24
    FeedSort("Hot", 0x7f080793),
    // outline_new_24
    FeedSort("New", 0x7f08056e),
    // outline_help_outline_24
    FeedSort("Ask", 0x7f08048e),
    // ic_trending_up_white_24dp
    FeedSort("Show", 0x7f0801e1),
    // outline_bar_chart_24
    FeedSort("Jobs", 0x7f0802cf)
)

@Suppress("unused")
val hackerNewsPatch = customFeedPatch(
    name = "Hacker News Feed",
    description = "Integrates Hacker News (news.ycombinator.com) into Reddit Sync as a custom feed.",
    feedName = "Hacker News",
    extensionClass = "Lapp/morphe/extension/syncforreddit/HackerNewsExtension;",
    hosts = listOf("news.ycombinator.com"),
    sorts = HACKER_NEWS_SORTS,
)
