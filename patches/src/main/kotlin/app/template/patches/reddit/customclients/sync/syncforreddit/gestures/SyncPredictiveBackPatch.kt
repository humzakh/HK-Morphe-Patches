package app.template.patches.reddit.customclients.sync.syncforreddit.gestures

import app.morphe.patcher.patch.resourcePatch
import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

@Suppress("unused")
val syncPredictiveBackPatch = resourcePatch(
    name = "Predictive back gesture (Reddit Sync)",
    description = "Enables the predictive back gesture with fixes for Reddit Sync.",
    default = true
) {
    compatibleWith(*SyncForRedditCompatible)

    execute {
        val flag = "android:enableOnBackInvokedCallback"
        
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0)
            if (application != null) {
                if (application.attributes.getNamedItem(flag) == null) {
                    document.createAttributeNS("http://schemas.android.com/apk/res/android", flag).apply {
                        value = "true"
                    }.let(application.attributes::setNamedItem)
                } else {
                    application.attributes.getNamedItem(flag).nodeValue = "true"
                }
            }

            val activities = document.getElementsByTagName("activity")
            for (i in 0 until activities.length) {
                val node = activities.item(i)
                val nameAttr = node.attributes.getNamedItem("android:name")?.nodeValue
                if (nameAttr == "com.laurencedawson.reddit_sync.ui.activities.MainActivity") {
                    if (node.attributes.getNamedItem(flag) == null) {
                        document.createAttributeNS("http://schemas.android.com/apk/res/android", flag).apply {
                            value = "false"
                        }.let(node.attributes::setNamedItem)
                    } else {
                        node.attributes.getNamedItem(flag).nodeValue = "false"
                    }
                }
            }
        }
    }
}
