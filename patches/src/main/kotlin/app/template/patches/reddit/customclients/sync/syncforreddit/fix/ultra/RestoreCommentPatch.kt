package app.template.patches.reddit.customclients.sync.syncforreddit.fix.ultra

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.string.replaceStringPatch
import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

val restoreCommentPatch = bytecodePatch(
    name = "Fix \"Restore Comment\"",
    description = "Fixes the \"Restore Comment\" feature (requires Sync Ultra) by fetching from an alternative API. Also includes UI changes to make this feature more accessible."
) {
    compatibleWith(*SyncForRedditCompatible)

    dependsOn(
        syncUltraPatch,
        // patch the Restore Comment feature to fetch from Arctic Shift
        replaceStringPatch(
            "https://api.pushshift.io/reddit/comment/search/",
            "https://arctic-shift.photon-reddit.com/api/comments/ids",
            comparison = StringComparisonType.EQUALS
        )
    )

    execute {
        val commentClassType = CommentHolderBindFingerprint.method.parameters.first().type

        // replace Profile action bar item with Restore Comment for deleted comments
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

        // replace Profile swipe action with Restore Comment for deleted comments
        CommentSwipeActionProfileFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x4
                if-ne p2, v0, :not_profile_swipe
                
                invoke-virtual {p1}, $commentClassType->e()Ljava/lang/String;
                move-result-object v0
                
                const-string v1, "[removed]"
                invoke-virtual {v1, v0}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
                move-result v1
                if-nez v1, :is_removed_swipe
                
                const-string v1, "[deleted]"
                invoke-virtual {v1, v0}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :not_profile_swipe
                
                :is_removed_swipe
                new-instance v0, Lr8/h;
                invoke-direct {v0, p1}, Lr8/h;-><init>($commentClassType)V
                invoke-static {v0}, Lm8/a;->a(Lcom/android/volley/Request;)V
                return-void
                
                :not_profile_swipe
            """.trimIndent()
        )

        // replace Profile action bar icon with Restore Comment icon for deleted comments
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

        // replace Profile swipe action icon with Restore Comment icon for deleted comments
        CommentSwipeActionDrawFingerprint.method.addInstructions(
            0,
            """
                move-object/from16 v0, p3
                instance-of v1, v0, Ltb/a;
                if-eqz v1, :skip_dynamic_icon
                
                check-cast v0, Ltb/a;
                invoke-virtual {v0}, Ltb/a;->j()Lxa/d;
                move-result-object v0
                
                invoke-virtual {v0}, Lxa/d;->e()Ljava/lang/String;
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
                const v4, -0x3af3ec
                goto :update_icons
                
                :not_deleted
                const v0, 0x7f08025e
                const v4, -0xbb7501
                
                :update_icons
                move-object/from16 v2, p0
                
                invoke-static {}, Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;->x()Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton${'$'}Settings;
                move-result-object v1
                iget v1, v1, Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton${'$'}Settings;->commentNavigationFirst:I
                const/4 v3, 0x4
                if-ne v1, v3, :check_second
                iget-object v1, v2, Lgb/m;->d:Landroid/content/Context;
                invoke-virtual {v1}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;
                move-result-object v1
                invoke-virtual {v1, v0}, Landroid/content/res/Resources;->getDrawable(I)Landroid/graphics/drawable/Drawable;
                move-result-object v1
                invoke-virtual {v1}, Landroid/graphics/drawable/Drawable;->mutate()Landroid/graphics/drawable/Drawable;
                move-result-object v1
                const/4 v3, -0x1
                invoke-virtual {v1, v3}, Landroid/graphics/drawable/Drawable;->setTint(I)V
                iput-object v1, v2, Lgb/m;->i:Landroid/graphics/drawable/Drawable;
                iput v4, v2, Lgb/m;->l:I
                
                :check_second
                invoke-static {}, Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;->x()Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton${'$'}Settings;
                move-result-object v1
                iget v1, v1, Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton${'$'}Settings;->commentNavigationSecond:I
                const/4 v3, 0x4
                if-ne v1, v3, :check_third
                iget-object v1, v2, Lgb/m;->d:Landroid/content/Context;
                invoke-virtual {v1}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;
                move-result-object v1
                invoke-virtual {v1, v0}, Landroid/content/res/Resources;->getDrawable(I)Landroid/graphics/drawable/Drawable;
                move-result-object v1
                invoke-virtual {v1}, Landroid/graphics/drawable/Drawable;->mutate()Landroid/graphics/drawable/Drawable;
                move-result-object v1
                const/4 v3, -0x1
                invoke-virtual {v1, v3}, Landroid/graphics/drawable/Drawable;->setTint(I)V
                iput-object v1, v2, Lgb/m;->j:Landroid/graphics/drawable/Drawable;
                iput v4, v2, Lgb/m;->m:I
                
                :check_third
                invoke-static {}, Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;->x()Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton${'$'}Settings;
                move-result-object v1
                iget v1, v1, Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton${'$'}Settings;->commentNavigationThird:I
                const/4 v3, 0x4
                if-ne v1, v3, :skip_dynamic_icon
                iget-object v1, v2, Lgb/m;->d:Landroid/content/Context;
                invoke-virtual {v1}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;
                move-result-object v1
                invoke-virtual {v1, v0}, Landroid/content/res/Resources;->getDrawable(I)Landroid/graphics/drawable/Drawable;
                move-result-object v1
                invoke-virtual {v1}, Landroid/graphics/drawable/Drawable;->mutate()Landroid/graphics/drawable/Drawable;
                move-result-object v1
                const/4 v3, -0x1
                invoke-virtual {v1, v3}, Landroid/graphics/drawable/Drawable;->setTint(I)V
                iput-object v1, v2, Lgb/m;->k:Landroid/graphics/drawable/Drawable;
                iput v4, v2, Lgb/m;->n:I
                
                :skip_dynamic_icon
            """.trimIndent()
        )

        // replace comment text notifying the user if Restore Comment failed
        RestoreCommentParseNetworkResponseFingerprint.method.apply {
            val bodyExtractionIndex = this.instructions.indexOfFirst { instr ->
                try {
                    val getRefMethod = instr.javaClass.methods.firstOrNull { it.name == "getReference" }
                    if (getRefMethod != null) {
                        val ref = getRefMethod.invoke(instr)
                        if (ref != null) {
                            val getDefClassMethod = ref.javaClass.methods.firstOrNull { it.name == "getDefiningClass" }
                            val getNameMethod = ref.javaClass.methods.firstOrNull { it.name == "getName" }
                            if (getDefClassMethod != null && getNameMethod != null) {
                                val definingClass = getDefClassMethod.invoke(ref) as? String
                                val name = getNameMethod.invoke(ref) as? String
                                if (definingClass == "Lwc/p;" && name == "a") {
                                    return@indexOfFirst true
                                }
                            }
                        }
                    }
                } catch (e: Exception) { }
                false
            }
            if (bodyExtractionIndex != -1) {
                this.addInstructions(
                    bodyExtractionIndex + 2,
                    """
                        const-string v10, "^(\\[removed\\]|\\[deleted\\])$"
                        const-string v11, "[failed to restore comment]"
                        invoke-virtual {v5, v10, v11}, Ljava/lang/String;->replaceFirst(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v5
                    """.trimIndent()
                )
            }
        }
    }
}
