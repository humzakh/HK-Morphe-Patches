package app.template.patches.reddit.customclients.sync.syncforreddit.customfeeds.hackernews

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/** The hosts Hacker News is read on, which links to it are written with. */
private val HACKER_NEWS_HOSTS = listOf("news.ycombinator.com")

/**
 * Offers the app for Hacker News links opened in other apps.
 *
 * Unnamed, so that it is applied as part of the Hacker News patch rather than listed as a patch of
 * its own. The link is handed to the same routing a link followed inside the app goes through, which
 * the Hacker News patch takes.
 */
internal val hackerNewsLinksPatch = resourcePatch {
    execute {
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

            // Listed alongside the hosts the activity is already offered for, so a Hacker News link is
            // offered to the app in the same way a Reddit one is.
            HACKER_NEWS_HOSTS.forEach { host ->
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
