package app.template.patches.reddit.customclients.sync.syncforreddit.fix.ultra

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.string.replaceStringPatch
import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

val restoreCommentShortcutPatch = bytecodePatch(
    name = "Fix \"Restore Comment\"",
    description = "Fixes the \"Restore Comment\" feature (requires Sync Ultra) by fetching from an alternative API. Also adds a more accessible button for this feature."
) {
    compatibleWith(*SyncForRedditCompatible)

    dependsOn(
        // patch the Restore Comment feature to fetch from Arctic Shift
        replaceStringPatch(
            "https://api.pushshift.io/reddit/comment/search/",
            "https://arctic-shift.photon-reddit.com/api/comments/ids",
            comparison = StringComparisonType.EQUALS
        )
    )

    execute {
        var commentClassType = "Lxa/d;"

        CommentHolderBindFingerprint.method.apply {
            commentClassType = this.parameters.first().type
        }

        CommentHolderTFingerprint.method.addInstructions(
            0,
            """
                invoke-virtual {p0}, Ltb/a;->j()$commentClassType
                move-result-object v0
                
                invoke-virtual {v0}, $commentClassType->e()Ljava/lang/String;
                move-result-object v0
                
                const-string v1, "[removed]"
                invoke-virtual {v1, v0}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
                move-result v1
                if-nez v1, :is_deleted
                
                const-string v1, "[deleted]"
                invoke-virtual {v1, v0}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :not_deleted
                
                :is_deleted
                const v0, 0x7f080642
                goto :set_icon
                
                :not_deleted
                const v0, 0x7f08025e
                
                :set_icon
                iget-object v1, p0, Lcom/laurencedawson/reddit_sync/ui/viewholders/comments/CommentHolder;->mButtonProfile:Lcom/laurencedawson/reddit_sync/ui/views/buttons/ProfileButton;
                invoke-virtual {v1, v0}, Landroidx/appcompat/widget/AppCompatImageButton;->setImageResource(I)V
            """.trimIndent()
        )

        CommentHolderOnProfileClickedFingerprint.method.addInstructions(
            0,
            """
                invoke-virtual {p0}, Ltb/a;->j()$commentClassType
                move-result-object v0
                
                invoke-virtual {v0}, $commentClassType->e()Ljava/lang/String;
                move-result-object v1
                
                const-string v0, "[removed]"
                invoke-virtual {v0, v1}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
                move-result v0
                if-nez v0, :is_removed
                
                const-string v0, "[deleted]"
                invoke-virtual {v0, v1}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :not_removed
                
                :is_removed
                invoke-virtual {p0}, Ltb/a;->j()$commentClassType
                move-result-object v0
                new-instance v1, Lr8/h;
                invoke-direct {v1, v0}, Lr8/h;-><init>($commentClassType)V
                invoke-static {v1}, Lm8/a;->a(Lcom/android/volley/Request;)V
                return-void
                
                :not_removed
            """.trimIndent()
        )
    }
}
