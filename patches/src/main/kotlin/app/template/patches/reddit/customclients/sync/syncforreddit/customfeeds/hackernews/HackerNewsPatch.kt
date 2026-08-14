package app.template.patches.reddit.customclients.sync.syncforreddit.customfeeds.hackernews

import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/syncforreddit/HackerNewsExtension;"

/**
 * The feeds Hacker News serves, offered in place of Reddit's sorts, each with the icon it is shown
 * with.
 *
 * Order is the order they are listed in. Matches the feeds `HackerNewsExtension` fetches.
 *
 * The icons are drawables the app already draws in these same dialogs, so none have to be added and
 * each is known to load. The patch is compatible with a single app version, whose ids these are; the
 * drawable each one is is named alongside it.
 */
private val HACKER_NEWS_SORTS = listOf(
    // ic_star_outline_white_24dp
    Sort("Best", 0x7f0801d8),
    // outline_whatshot_24
    Sort("Hot", 0x7f080793),
    // outline_new_24
    Sort("New", 0x7f08056e),
    // outline_help_outline_24
    Sort("Ask", 0x7f08048e),
    // ic_trending_up_white_24dp
    Sort("Show", 0x7f0801e1),
    // outline_bar_chart_24
    Sort("Jobs", 0x7f0802cf)
)

/** A feed offered in the sort dialog, and the drawable it is shown with. */
private data class Sort(val label: String, val icon: Int)

private const val FEED_NAME = "Hacker News"

private const val FEED = "multi_$FEED_NAME"

/**
 * The subreddit posts in the feed report themselves as belonging to, which identifies a post's
 * comments as coming from Hacker News. Matches `HackerNewsExtension.SUBREDDIT`.
 */
private const val SUBREDDIT = "hackernews"

val hackerNewsPatch = bytecodePatch(
    name = "Hacker News Feed",
    description = "Integrates Hacker News (news.ycombinator.com) as a custom feed.",
    default = true,
) {
    extendWith("extensions/syncforreddit.mpe")

    // Offers the app for Hacker News links opened elsewhere. Applied as part of this patch rather
    // than listed on its own, since it is the same feature from the other side.
    dependsOn(hackerNewsLinksPatch)

    compatibleWith(*SyncForRedditCompatible)

    execute {
        // The cursor for the next page of posts is not passed to the url builder, but kept on the
        // request state it is given. It is resolved from where the response parsing stores it, which
        // is the "after" the previous page ended with.
        val afterField = parsePostsNetworkResponseFingerprint.method.let { method ->
            val afterIndex = method.instructions.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "after"
            }

            // Read out of the listing, then stored on the state a few instructions later.
            method.instructions
                .drop(afterIndex)
                .first { it.opcode == Opcode.IPUT_OBJECT }
                .let { (it as ReferenceInstruction).reference as FieldReference }
        }

        // Point the Hacker News feed at the Hacker News API. p1 is the feed being opened, p3 the
        // request state carrying the cursor and p4 the chosen sort; every other feed and subreddit
        // falls through to Reddit.
        //
        // p4 rather than p5: the sort picked from the dialog is carried as the "access" the posts are
        // read through, while p5 carries the time range that goes with Reddit's own sorts and is
        // empty here.
        //
        // This method declares enough locals to push its parameters out of the range the four bit
        // register forms can address, so parameters are moved into low registers with
        // move-object/from16 before being used.
        postsRequestBuildUrlFingerprint.method.addInstructions(
            0,
            """
                move-object/from16 v0, p1

                const-string v1, "$FEED"
                invoke-virtual { v0, v1 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0

                if-eqz v0, :not_hackernews

                move-object/from16 v0, p3
                iget-object v0, v0, ${afterField.definingClass}->${afterField.name}:${afterField.type}

                move-object/from16 v1, p4
                invoke-static { v1 }, $EXTENSION_CLASS_DESCRIPTOR->sortOrDefault(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v1

                invoke-static { v0, v1 }, $EXTENSION_CLASS_DESCRIPTOR->buildUrl(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                return-object v0

                :not_hackernews
                nop
            """
        )

        // Hacker News serves no images, so thumbnails are taken from what Reddit holds for the same
        // links - the request the app already makes for "Other Discussions". Reddit answers that only
        // for a signed in client, and the token is held on the request rather than anywhere reachable
        // on its own, so it is read off the request being parsed and handed over here.
        parsePostsNetworkResponseFingerprint.method.apply {
            // The token is the field the request builds its authorization header from, so it is read
            // out of that rather than picked from the fields by shape.
            val tokenField = mutableClassDefBy(mutableClassDefBy(definingClass).superclass!!)
                .methods
                .first { it.name == "getHeaders" }
                .instructions
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
                .first { it.type == "Ljava/lang/String;" }

            // The method declares enough locals to push "this" out of the range the four bit register
            // forms can address, so it is moved down before the field is read off it.
            addInstructions(
                0,
                """
                    move-object/from16 v0, p0
                    iget-object v0, v0, ${tokenField.definingClass}->${tokenField.name}:${tokenField.type}
                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->noteToken(Ljava/lang/String;)V
                """
            )
        }

        // Translate the Hacker News response into Reddit's listing format before it is parsed, so the
        // existing parsing runs unchanged and the post list, database and post view keep working
        // without being patched.
        parsePostsNetworkResponseFingerprint.method.rewriteResponseBytes("rewriteResponse")

        // List the feed alongside the account's own custom feeds, so it is reachable from the custom
        // feeds section rather than by typing a name.
        parseMultiredditsNetworkResponseFingerprint.method.rewriteResponseBytes("addFeed")

        // Tapping the name shown on a post opens the subreddit its posts report, which does not exist
        // on Reddit. The feed is opened instead, which is where those posts came from.
        //
        // Only the name shown on a post is taken this way, rather than the method every way of opening
        // a subreddit runs through, so that a link written out as the subreddit still opens what it
        // names rather than the feed.
        openPostSubredditFingerprint.method.redirectSubredditToFeed()

        // Leave the option to delete a custom feed off the Hacker News feed. The feed is not one the
        // account holds but one this patch adds to the list, so deleting it would ask Reddit to remove
        // a feed it does not have, and it would be listed again as soon as the list was read.
        deletableFeedFingerprint.method.apply {
            // The name is read off the holder into a register the method already has, which the answer
            // is then returned in.
            val nameField = instructions
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
                .first { it.type == "Ljava/lang/String;" }

            addInstructions(
                instructions.count() - 1,
                """
                    iget-object v1, p0, ${nameField.definingClass}->${nameField.name}:${nameField.type}
                    invoke-static { v0, v1 }, $EXTENSION_CLASS_DESCRIPTOR->isDeletable(ZLjava/lang/String;)Z
                    move-result v0
                """
            )
        }

        // The same name shown above a post's comments, which the screen opens from its own handlers.
        // Everything else the screen opens the name from, such as its sidebar, is left alone.
        mutableClassDefBy(commentsScreenFingerprint.originalClassDef)
            .methods
            .filter { method ->
                method.parameters == listOf("Landroid/view/View;") &&
                    method.instructions.any {
                        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { call ->
                            call.definingClass == "Ly7/a;" && call.name == "T"
                        } == true
                    }
            }
            .forEach { it.redirectSubredditToFeed() }

        // Serve the site's own guidelines as the feed's rules, and its FAQ as the feed's wiki, in
        // place of the Reddit pages that do not exist for it. The Show feed has its own guidelines,
        // which are served in place of the general ones while it is being read.
        //
        // As with the feed's subreddits, the request is pointed at a page that answers rather than
        // letting it fail, since a failed response never reaches the parsing that replaces it.
        rulesBuildUrlFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->guidelinesUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0

                if-eqz v0, :not_hackernews
                return-object v0

                :not_hackernews
                nop
            """
        )

        parseRulesNetworkResponseFingerprint.method.rewriteResponseBytes("rewriteRules")

        wikiBuildUrlFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->faqUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0

                if-eqz v0, :not_hackernews
                return-object v0

                :not_hackernews
                nop
            """
        )

        parseWikiNetworkResponseFingerprint.method.rewriteResponseBytes("rewriteWiki")

        // Title the feed's wiki as the site's FAQ, which is what it holds, rather than as the feed and
        // page it is read from. The title is rewritten where it is finished being built, so every
        // other subreddit's wiki keeps the title it always had.
        wikiTitleFingerprint.method.apply {
            val titleIndex = instructions.indexOfFirst { instruction ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    ((instruction as ReferenceInstruction).reference as MethodReference).let {
                        it.name == "toString" && it.definingClass == "Ljava/lang/StringBuilder;"
                    }
            }
            val titleRegister = getInstruction<OneRegisterInstruction>(titleIndex + 1).registerA

            addInstructions(
                titleIndex + 2,
                """
                    invoke-static { v$titleRegister }, $EXTENSION_CLASS_DESCRIPTOR->wikiTitle(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$titleRegister
                """
            )
        }

        // Open a link to a Hacker News discussion as the post this patch serves it as, rather than
        // handing it to a browser. The link is reported as that post's address, which the app then
        // opens as it opens any other post link.
        //
        // Taken where the address is settled rather than where a link is routed, because a link is
        // only routed through the app when it is a Reddit one, and a Hacker News link is handed
        // straight to a browser before it reaches that.
        tappedLinkFingerprint.method.apply {
            // The address is returned from more than one place, so each is rewritten.
            instructions
                .withIndex()
                .filter { (_, instruction) -> instruction.opcode == Opcode.RETURN_OBJECT }
                .map { (index, _) -> index }
                .reversed()
                .forEach { returnIndex ->
                    val addressRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA

                    addInstructions(
                        returnIndex,
                        """
                            invoke-static { v$addressRegister }, $EXTENSION_CLASS_DESCRIPTOR->linkFor(Ljava/lang/String;)Ljava/lang/String;
                            move-result-object v$addressRegister
                        """
                    )
                }
        }

        // Leave the archived and locked markers off a story. Stories are archived and locked to stop
        // the app offering to vote or reply through Reddit, which is a means rather than something
        // true of the story, so saying so under every title would be misleading.
        postMarkersFingerprint.method.dropMarkers("d", "m0")

        // Name Hacker News in the notice shown above a story's comments, in place of the archiving it
        // is served with. The post is read before the notice is built, because the notice is built
        // into the register the state was passed in and so replaces it.
        commentsNoticeFingerprint.method.apply {
            val noticeIndex = instructions.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                    "This is an archived post.\nYou will not be able to vote or comment"
            }
            val noticeRegister = getInstruction<OneRegisterInstruction>(noticeIndex).registerA

            // The state is passed in the register the notice is about to be built into, so the post is
            // taken out of it first, into a local the method has free at this point.
            addInstructions(
                noticeIndex,
                """
                    invoke-interface { p1 }, Lya/c;->e0()Lxa/d;
                    move-result-object v0
                """
            )

            addInstructions(
                noticeIndex + 3,
                """
                    invoke-static { v$noticeRegister, v0 }, $EXTENSION_CLASS_DESCRIPTOR->noticeFor(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;
                    move-result-object v$noticeRegister
                """
            )
        }


        // Give the chip naming the current sort the same icon the dialog offers it with. The app only
        // knows Reddit's own sorts here and shows a cross for anything else, which the feeds added
        // below would otherwise get.
        //
        // The method is compiled with no locals, so the sort is passed straight through and the icon
        // returned into a parameter register.
        sortChipIconFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->sortIcon(Ljava/lang/String;)I
                move-result p1

                if-eqz p1, :not_hackernews
                return p1

                :not_hackernews
                nop
            """
        )

        // Offer the feeds Hacker News serves in place of Reddit's sorts, which mean nothing here.
        sortOptionsFingerprint.method.apply {
            // The option type, the method adding one and the field holding the current sort are taken
            // from the options the method already builds, rather than being named directly.
            //
            // The app builds two kinds of option: one that opens a second dialog asking for a time
            // range, used for sorts like Top, and one that applies as soon as it is picked, used for
            // sorts like New. Hacker News' feeds have no time range, so the second kind is used --
            // the one taking an icon, a label and whether it is the current choice.
            val optionType = instructions
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .first {
                    it.name == "<init>" &&
                        it.parameterTypes.map(CharSequence::toString) ==
                        listOf("I", "Ljava/lang/String;", "Z")
                }

            val addOption = instructions
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .first { it.parameterTypes.size == 1 && it.parameterTypes[0] == it.returnType }

            val currentSortField = instructions
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
                .first { it.type == "Ljava/lang/String;" && it.definingClass == definingClass }

            val options = HACKER_NEWS_SORTS.joinToString("\n") { sort ->
                """
                    new-instance v0, ${optionType.definingClass}
                    const v1, ${"0x%08x".format(sort.icon)}
                    const-string v2, "${sort.label}"
                    iget-object v3, p0, ${currentSortField.definingClass}->${currentSortField.name}:${currentSortField.type}
                    invoke-static { v2, v3 }, Lorg/apache/commons/lang3/StringUtils;->equalsIgnoreCase(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z
                    move-result v3
                    invoke-direct { v0, v1, v2, v3 }, ${optionType.definingClass}-><init>(${optionType.parameterTypes.joinToString("")})V
                    invoke-virtual { p0, v0 }, ${addOption.definingClass}->${addOption.name}(${addOption.parameterTypes[0]})${addOption.returnType}
                """.trimIndent()
            }

            // The name of what is being sorted, read the same way the method already reads it.
            val subredditName = getInstruction<ReferenceInstruction>(0).reference as MethodReference

            // The instruction the untouched path continues from, taken before anything is inserted.
            val original = getInstruction(0)

            addInstructionsWithLabels(
                0,
                buildString {
                    appendLine("invoke-virtual { p0 }, ${subredditName.definingClass}->${subredditName.name}()${subredditName.returnType}")
                    appendLine("move-result-object v0")
                    appendLine("invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->isFeedName(Ljava/lang/String;)Z")
                    appendLine("move-result v0")
                    appendLine("if-eqz v0, :not_hackernews")
                    appendLine(options)
                    appendLine("return-void")
                },
                ExternalLabel("not_hackernews", original)
            )
        }

        // Custom feeds are not given an icon, being collections rather than a single source. This one
        // is a single source, so it is treated as a subreddit for the purpose of showing one.
        //
        // Only this decision is changed, rather than the check for whether a name is a custom feed,
        // which the rest of the app relies on to treat the feed as a feed.
        showsSubredditIconFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "$FEED"
                invoke-virtual { p0, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0

                if-eqz v0, :not_hackernews

                const/4 v0, 0x1
                return v0

                :not_hackernews
                nop
            """
        )

        // Show Hacker News' own icon for the feed. Icons are looked up by name and loaded from a url,
        // so the site's icon is given here rather than being bundled with the patch.
        subredditIconFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->iconUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0

                if-eqz v0, :not_hackernews
                return-object v0

                :not_hackernews
                nop
            """
        )

        // Show the feed's title without the "[M]" the app marks custom feeds with, since this one
        // reads as a source rather than as a feed the account owns. p0 is the name being titled;
        // every other feed keeps its marker.
        subredditTitleFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "$FEED"
                invoke-virtual { p0, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0

                if-eqz v0, :not_hackernews

                const-string v0, "$FEED_NAME"
                return-object v0

                :not_hackernews
                nop
            """
        )

        // Opening a custom feed lists the subreddits it contains. The Hacker News feed does not exist
        // on Reddit, so that request fails and the app reports the feed could not be loaded.
        //
        // The request is pointed at an address that answers successfully instead, because a failed
        // response never reaches the parsing below: Volley raises the error status before parsing,
        // so replacing the response there would never run.
        multiSubredditsBuildUrlFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p2 }, $EXTENSION_CLASS_DESCRIPTOR->feedSubredditsUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0

                if-eqz v0, :not_hackernews
                return-object v0

                :not_hackernews
                nop
            """
        )

        parseMultiSubredditsNetworkResponseFingerprint.method.rewriteResponseBytes("rewriteFeedSubreddits")

        // Point a story's comments at the Hacker News API. p1 is the post id, which is what a story is
        // recognised by - Reddit has a subreddit of the same name, whose posts must still be read from
        // Reddit.
        //
        // This method keeps its parameters in low registers, so unlike the posts url builder they can
        // be used directly.
        commentsRequestBuildUrlFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->buildCommentsUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0

                if-eqz v0, :not_hackernews
                return-object v0

                :not_hackernews
                nop
            """
        )

        // Translate the story into the comment tree Sync parses, in the same way as the post list.
        parseCommentsNetworkResponseFingerprint.method.rewriteResponseBytes("rewriteComments")

        // Correct links to Hacker News content before they leave the app. Links are built from the
        // post's subreddit and id, which for this feed produce a reddit.com address that does not
        // exist, so they are rewritten to the story or comment they refer to.
        //
        // Every dialog builds its own link and then hands it to one of these, so correcting them here
        // covers sharing and copying both posts and comments. Sharing a post goes through a different
        // method than sharing a comment, so both are corrected.
        listOf(shareLinkFingerprint, sharePostLinkFingerprint).forEach { fingerprint ->
            fingerprint.method.addInstructions(
                0,
                """
                    invoke-static { p2 }, $EXTENSION_CLASS_DESCRIPTOR->shareUrl(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p2
                """
            )
        }

        // Copying takes a CharSequence rather than a String, so it is converted before being checked.
        copyLinkFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->shareText(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
                move-result-object p1
            """
        )

        // Stop write actions on Hacker News content from being sent to Reddit. Voting, saving and
        // replying all name the post or comment they act on in the request parameters, and Reddit
        // would be asked to act on an id that means nothing there.
        //
        // Failing here rather than silently dropping the request lets Volley report the failure
        // through the error listener the action was made with, so the app shows the action failing
        // instead of appearing to succeed.
        requestParamsFingerprint.method.apply {
            // The method returns the parameter map straight from a field, so the field is read
            // directly rather than by calling the method being patched.
            val paramsField = (getInstruction(0) as ReferenceInstruction).reference as FieldReference

            // The method is compiled with a single local, so only that register is used here and the
            // error is built by the extension rather than in place.
            addInstructions(
                0,
                """
                    iget-object v0, p0, ${paramsField.definingClass}->${paramsField.name}:${paramsField.type}

                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->checkWriteAllowed(Ljava/util/Map;)V
                """
            )
        }
    }
}

/**
 * Drops the markers naming a story as archived and locked, leaving the rest of the line as it is.
 *
 * Each marker is added behind a test of the post, so each of those answers is corrected in turn, from
 * the last backwards so the instructions added do not move the tests still to be corrected.
 *
 * The post is read from the register it was passed in, which the method reuses as scratch part way
 * through. The correction is inserted between the test and the register being reused, so the post is
 * still there to be read.
 */
context(_: BytecodePatchContext)
private fun MutableMethod.dropMarkers(vararg markers: String) {
    // The method is static, so its parameters occupy the registers after the locals in order, and the
    // post is the one of them it is passed as.
    val postRegister = implementation!!.registerCount - parameters.size +
        parameters.indexOfFirst { it.type == "Lxa/d;" }

    // Every register the method has is written before the markers are built, so the post cannot be
    // kept aside in one. It is read from where it is passed instead, which each test still holds when
    // it runs, even where the test then answers over it.

    markers.map { marker ->
        instructions.indexOfFirst { instruction ->
            instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                ((instruction as ReferenceInstruction).reference as MethodReference).name == marker
        }
    }.sortedDescending().forEach { testIndex ->
        val moveResultIndex = testIndex + 1
        val markedRegister = getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

        // The correction goes in front of the test rather than after it. One of the tests answers into
        // the register the post was passed in, so afterwards there is no post left to ask about, while
        // in front of it the post is still there. The answer is noted for the correction that follows
        // the test to read back.
        //
        // Instructions are only added, never replaced, so anything branching to the test now lands on
        // the correction that precedes it - an invoke, which a branch may target, rather than the
        // move-result that may not.
        // Added from the back forwards, so that the earlier insertion does not move the later one.
        addInstructions(
            moveResultIndex + 1,
            """
                invoke-static { v$markedRegister }, $EXTENSION_CLASS_DESCRIPTOR->showsMarker(Z)Z
                move-result v$markedRegister
            """
        )

        addInstructions(
            testIndex,
            "invoke-static { v$postRegister }, $EXTENSION_CLASS_DESCRIPTOR->noteMarkedPost(Ljava/lang/Object;)V"
        )
    }
}

/**
 * Opens the Hacker News feed in place of the subreddit the method was about to open, for a story.
 *
 * The name is rewritten in the register it is passed in, just before it is handed over, so only this
 * way of opening a subreddit is affected and every other one still opens what it names.
 *
 * Both methods this is applied to are compiled with every register they have in use, so the post is
 * not fetched here. The holder or screen the name was tapped on is handed over as it stands, and the
 * post read from it there. The feed is opened only for a story, so the subreddit Reddit has of the
 * same name opens as it always did.
 */
context(_: BytecodePatchContext)
private fun MutableMethod.redirectSubredditToFeed() {
    val openIndex = instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_STATIC &&
            ((instruction as ReferenceInstruction).reference as MethodReference).let {
                it.definingClass == "Ly7/a;" && it.name == "T"
            }
    }
    val nameRegister = getInstruction<FiveRegisterInstruction>(openIndex).registerD

    addInstructions(
        openIndex,
        """
            invoke-static { v$nameRegister, p0 }, $EXTENSION_CLASS_DESCRIPTOR->feedFor(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;
            move-result-object v$nameRegister
        """
    )
}

/**
 * Passes the response bytes through [extensionMethod] before the method parses them.
 *
 * `NetworkResponse.data` is final, so rather than replacing the field, the register the bytes were
 * read into is rewritten just after the read. Only that register is used, so no register has to be
 * claimed from a method that may already be near its limit.
 */
context(_: BytecodePatchContext)
private fun MutableMethod.rewriteResponseBytes(extensionMethod: String) {
    val dataIndex = instructions.indexOfFirst {
        it.opcode == Opcode.IGET_OBJECT &&
            ((it as ReferenceInstruction).reference as FieldReference).name == "data"
    }
    val dataRegister = getInstruction<TwoRegisterInstruction>(dataIndex).registerA

    addInstructions(
        dataIndex + 1,
        """
            invoke-static { v$dataRegister }, $EXTENSION_CLASS_DESCRIPTOR->$extensionMethod([B)[B
            move-result-object v$dataRegister
        """
    )
}
