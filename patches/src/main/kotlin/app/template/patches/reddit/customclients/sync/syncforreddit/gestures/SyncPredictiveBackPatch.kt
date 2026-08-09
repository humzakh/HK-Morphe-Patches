package app.template.patches.reddit.customclients.sync.syncforreddit.gestures

import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Node

val syncPredictiveBackPatch = resourcePatch(
    name = "Predictive back gesture (Reddit Sync)",
    description = "Enables the predictive back gesture with fixes for Reddit Sync.",
    default = false
) {
    compatibleWith(*SyncForRedditCompatible)

    execute {
        val flag = "android:enableOnBackInvokedCallback"

        // Activities that should not have the predictive back gesture enabled
        val excludedActivities = setOf(
            "com.laurencedawson.reddit_sync.ui.activities.MainActivity",
            "com.laurencedawson.reddit_sync.ui.activities.media.SingleImageActivity",
            "com.laurencedawson.reddit_sync.ui.activities.media.MultiImageActivity"
        )

        document("AndroidManifest.xml").use { document ->
            fun Node.setFlag(enabled: Boolean) {
                val existing = attributes.getNamedItem(flag)
                if (existing == null) {
                    document.createAttributeNS("http://schemas.android.com/apk/res/android", flag).apply {
                        value = enabled.toString()
                    }.let(attributes::setNamedItem)
                } else {
                    existing.nodeValue = enabled.toString()
                }
            }

            document.getElementsByTagName("application").item(0)?.setFlag(true)

            val activities = document.getElementsByTagName("activity")
            for (i in 0 until activities.length) {
                val node = activities.item(i)
                val nameAttr = node.attributes.getNamedItem("android:name")?.nodeValue
                if (nameAttr in excludedActivities) {
                    node.setFlag(false)
                }
            }
        }
    }
}
