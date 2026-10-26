package app.template.patches.reddit.customclients.sync.syncforreddit.customfeeds

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/**
 * The settings the account's details are kept under, matching the extension's own names.
 */
private const val USERNAME_PREFERENCE = "custom_feed_username"
private const val PASSWORD_PREFERENCE = "custom_feed_password"

/** The section of the app's settings the feed's own settings are listed in. */
private const val LINKS_CATEGORY = "Links opened in app"

/**
 * Asks for the account the feed's writes are made as.
 *
 * The site offers no token to act with, so a write is sent with the account's own name and password,
 * which is what the site's own pages and its other clients do. Both are asked for here and kept in the
 * app's own settings, in the same way the app keeps every other answer given on a settings screen.
 *
 * Unnamed, so that it is applied as part of the feed's patch rather than listed as a patch of its own.
 *
 * @param feedName the name of the feed, as the settings are titled with.
 */
internal fun customFeedAccountPatch(feedName: String) = resourcePatch {
    execute {
        document("res/xml/cat_links.xml").use { document ->
            // Listed alongside the feed's other setting, which is the section the app already shows
            // what this patch adds in.
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
                ?: error("cat_links.xml has no \"$LINKS_CATEGORY\" category to list the settings in")

            // Listed as the app's own plain rows rather than as settings the app opens a dialog for.
            // The app shows every such dialog through one of its own, which only knows how to show a
            // list of choices and fails on anything else, so these ask for their value themselves.
            fun textSetting(key: String, title: String, summary: String) =
                document.createElement(
                    "com.laurencedawson.reddit_sync.ui.preferences.defaults.SyncPreference"
                ).apply {
                    setAttribute("android:key", key)
                    setAttribute("android:title", title)
                    setAttribute("android:summary", summary)
                }

            category.appendChild(
                textSetting(
                    USERNAME_PREFERENCE,
                    "$feedName username",
                    "The account votes, saves and replies are made as"
                )
            )

            category.appendChild(
                textSetting(
                    PASSWORD_PREFERENCE,
                    "$feedName password",
                    "Kept on this device, and sent to $feedName with each vote, save or reply"
                )
            )
        }
    }
}
