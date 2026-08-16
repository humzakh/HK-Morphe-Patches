package app.template.patches.reddit.customclients.sync.syncforreddit.customfeeds

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/**
 * The setting deciding whether the site's own links open in the feed, matching the extension's
 * `OPEN_LINKS_PREFERENCE`.
 */
private const val OPEN_LINKS_PREFERENCE = "custom_feed_links_preference"

/** The section of the app's settings listing the kinds of link it opens itself. */
private const val LINKS_CATEGORY = "Links opened in app"

/**
 * Offers the app for the feed's own links opened in other apps, and lists the setting deciding
 * whether it takes them.
 *
 * Unnamed, so that it is applied as part of the feed's patch rather than listed as a patch of its
 * own. The link is handed to the same routing a link followed inside the app goes through, which
 * [customFeedPatch] takes.
 *
 * @param feedName the name of the feed, as the setting is titled with.
 * @param hosts the hosts the site is read on, which links to it are written with.
 */
internal fun customFeedLinksPatch(feedName: String, hosts: List<String>) = resourcePatch {
    execute {
        // Listed among the app's own settings for the kinds of link it opens itself, which is the
        // same choice made about the same thing.
        document("res/xml/cat_links.xml").use { document ->
            // The section the app lists the kinds of link it opens itself under, found by the title
            // it is headed with rather than by its place among the others.
            val category = document.getElementsByTagName("PreferenceCategory")
                .let { categories -> (0 until categories.length).map(categories::item) }
                .map { it as Element }
                .firstOrNull { category ->
                    category.getElementsByTagName("*")
                        .let { children -> (0 until children.length).map(children::item) }
                        .any { child ->
                            (child as Element).getAttribute("app:categoryTitle") == LINKS_CATEGORY
                        }
                }
                ?: error("cat_links.xml has no \"$LINKS_CATEGORY\" category to list the setting in")

            // The setting is titled with the feed rather than with the site's hosts, since that is
            // what the links open into.
            document.createElement(
                "com.laurencedawson.reddit_sync.ui.preferences.defaults.SyncCheckBoxPreference"
            ).apply {
                setAttribute("android:title", feedName)
                setAttribute("android:key", OPEN_LINKS_PREFERENCE)
                setAttribute("android:defaultValue", "true")
            }.let(category::appendChild)
        }

        document("AndroidManifest.xml").use { document ->
            // The activity links from other apps are handed to, which passes them to the same routing
            // a link followed inside the app goes through.
            val links = document.getElementsByTagName("activity")
                .let { activities -> (0 until activities.length).map(activities::item) }
                .first { activity ->
                    activity.attributes.getNamedItem("android:name")?.nodeValue ==
                        "com.laurencedawson.reddit_sync.ui.activities.IntentActivity"
                }
                .let { activity ->
                    (activity as Element).getElementsByTagName("intent-filter").item(0) as Element
                }

            // Listed alongside the hosts the activity is already offered for, so one of the site's
            // links is offered to the app in the same way a Reddit one is.
            hosts.forEach { host ->
                listOf("http", "https").forEach { scheme ->
                    val alreadyListed = links.getElementsByTagName("data")
                        .let { data -> (0 until data.length).map(data::item) }
                        .any { data ->
                            data.attributes.getNamedItem("android:host")?.nodeValue == host &&
                                data.attributes.getNamedItem("android:scheme")?.nodeValue == scheme
                        }
                    if (alreadyListed) return@forEach

                    document.createElement("data")
                        .apply {
                            setAttribute("android:host", host)
                            setAttribute("android:scheme", scheme)
                        }
                        .let(links::appendChild)
                }
            }
        }
    }
}
