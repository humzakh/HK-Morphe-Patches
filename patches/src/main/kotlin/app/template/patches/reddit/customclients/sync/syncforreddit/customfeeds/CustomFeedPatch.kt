package app.template.patches.reddit.customclients.sync.syncforreddit.customfeeds

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

/**
 * The extension holding everything true of any feed served in place of Reddit, which the app is
 * pointed at. The feed a patch was applied for is named on it, and reached through it.
 */
private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/syncforreddit/CustomFeedExtension;"

/**
 * A feed offered in the sort dialog in place of Reddit's sorts, and the drawable it is shown with.
 *
 * The icons are drawables the app already draws in these same dialogs, so none has to be added and
 * each is known to load. A patch is compatible with a single app version, whose ids these are.
 *
 * @param label the name the feed is offered under, which the extension reads back as the sort.
 * @param icon the drawable shown beside it.
 */
data class FeedSort(val label: String, val icon: Int)

/**
 * Serves a site that is not Reddit as a custom feed.
 *
 * Sync builds every screen from Reddit's listing JSON, so rather than teaching the UI about a second
 * content source, the site is translated into the listing shape Sync already parses. This patches the
 * places the app decides what to request, what a response means, and what a name or link refers to;
 * everything downstream of the parsing - the post list, the database and the post view - then works
 * without being patched.
 *
 * What a particular site is stays in its extension, which subclasses `CustomFeedExtension`. This
 * takes only what has to be written into the bytecode: the names the feed is recognised by, and the
 * feeds offered in place of Reddit's sorts.
 *
 * @param name the patch name, as it is listed.
 * @param description the patch description, as it is listed.
 * @param feedName the display name of the feed, matching the extension's `feedName()`. The only
 * thing written into the bytecode, since the app compares against it before the extension is
 * reached; everything else the feed is is read from the extension.
 * @param extensionClass the extension serving the feed, which the app is pointed at.
 * @param hosts the hosts the site is read on, which the app is offered for.
 * @param sorts the feeds offered in place of Reddit's sorts, in the order they are listed.
 * @param block anything further the feed needs, applied after everything above.
 */
fun customFeedPatch(
    name: String,
    description: String,
    feedName: String,
    extensionClass: String,
    hosts: List<String>,
    sorts: List<FeedSort>,
    block: BytecodePatchContext.() -> Unit = {},
) = bytecodePatch(
    name = name,
    description = description,
    default = true,
) {
    extendWith("extensions/syncforreddit.mpe")

    // Offers the app for the site's links opened elsewhere. Applied as part of this patch rather than
    // listed on its own, since it is the same feature from the other side.
    dependsOn(customFeedLinksPatch(feedName, hosts))

    compatibleWith(*SyncForRedditCompatible)

    execute {
        // The feed the app serves, which the extension is told the name of. The extension holds
        // everything generic and reaches the feed's own through this, so the feed is named once here
        // rather than being written into each of the injections below.
        nameFeedExtension(extensionClass)

        // The custom feed the site is served as, as Sync stores it.
        val feed = "multi_$feedName"

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

        // Point the feed at the site's own API. p1 is the feed being opened, p3 the request state
        // carrying the cursor and p4 the chosen sort; every other feed and subreddit falls through to
        // Reddit.
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

                const-string v1, "$feed"
                invoke-virtual { v0, v1 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0

                if-eqz v0, :not_custom_feed

                move-object/from16 v0, p3
                iget-object v0, v0, ${afterField.definingClass}->${afterField.name}:${afterField.type}

                move-object/from16 v1, p4
                invoke-static { v1 }, $EXTENSION_CLASS_DESCRIPTOR->sortOrDefault(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v1

                invoke-static { v0, v1 }, $EXTENSION_CLASS_DESCRIPTOR->buildUrl(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                return-object v0

                :not_custom_feed
                move-object/from16 v0, p1

                move-object/from16 v1, p3
                iget-object v1, v1, ${afterField.definingClass}->${afterField.name}:${afterField.type}

                invoke-static { v0, v1 }, $EXTENSION_CLASS_DESCRIPTOR->buildProfileUrl(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0

                if-eqz v0, :not_custom_profile
                return-object v0

                :not_custom_profile
                nop
            """
        )

        // A site that serves no images of its own can still show one for a link that has also been
        // submitted to Reddit, which is the request the app already makes for "Other Discussions".
        // Reddit answers that only for a signed in client, and the token is held on the request rather
        // than anywhere reachable on its own, so it is read off the request being parsed and handed
        // over here.
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

        // Translate the site's response into Reddit's listing format before it is parsed, so the
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

        // Leave the option to delete a custom feed off this feed. It is not one the account holds but
        // one this patch adds to the list, so deleting it would ask Reddit to remove a feed it does
        // not have, and it would be listed again as soon as the list was read.
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

        // Serve the site's own pages as the feed's rules and wiki, in place of the Reddit pages that
        // do not exist for it.
        //
        // As with the feed's subreddits, the request is pointed at a page that answers rather than
        // letting it fail, since a failed response never reaches the parsing that replaces it.
        rulesBuildUrlFingerprint.method.returnFromExtension("guidelinesUrlFor", parameter = 1)

        parseRulesNetworkResponseFingerprint.method.rewriteResponseBytes("rewriteRules")

        wikiBuildUrlFingerprint.method.returnFromExtension("faqUrlFor", parameter = 1)

        parseWikiNetworkResponseFingerprint.method.rewriteResponseBytes("rewriteWiki")

        // Title the feed's wiki as the page the site holds, rather than as the feed and page it is
        // read from. The title is rewritten where it is finished being built, so every other
        // subreddit's wiki keeps the title it always had.
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
                    invoke-static { v$titleRegister }, $EXTENSION_CLASS_DESCRIPTOR->wikiTitleFor(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$titleRegister
                """
            )
        }

        // Open a link to one of the site's discussions as the post this patch serves it as, rather
        // than handing it to a browser. The link is rewritten into that post's address, which the app
        // then routes as it routes any other post link.
        //
        // Taken where a link is routed rather than where its address is read, because the address is
        // read to show as well as to open - long pressing one offers it as it is written, which is the
        // site's address rather than the one this patch serves it under.
        //
        // This method declares enough locals to push its parameters out of the range the four bit
        // register forms can address, so the link is moved into a low register before being used.
        openLinkFingerprint.method.addInstructions(
            0,
            """
                move-object/from16 v0, p3

                invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->linkFor(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0

                move-object/from16 p3, v0
            """
        )

        // Leave the archived and locked markers off a post. Posts are archived and locked to stop the
        // app offering to vote or reply through Reddit, which is a means rather than something true of
        // the post, so saying so under every title would be misleading.
        postMarkersFingerprint.method.dropMarkers("d", "m0")

        // Name the site in the notice shown above a post's comments, in place of the archiving it is
        // served with. The post is read before the notice is built, because the notice is built into
        // the register the state was passed in and so replaces it.
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
                invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->sortIconFor(Ljava/lang/String;)I
                move-result p1

                if-eqz p1, :not_custom_feed
                return p1

                :not_custom_feed
                nop
            """
        )

        // Offer the feeds the site serves in place of Reddit's sorts, which mean nothing here.
        sortOptionsFingerprint.method.apply {
            // The option type, the method adding one and the field holding the current sort are taken
            // from the options the method already builds, rather than being named directly.
            //
            // The app builds two kinds of option: one that opens a second dialog asking for a time
            // range, used for sorts like Top, and one that applies as soon as it is picked, used for
            // sorts like New. These feeds have no time range, so the second kind is used -- the one
            // taking an icon, a label and whether it is the current choice.
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

            val options = sorts.joinToString("\n") { sort ->
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
                    appendLine("if-eqz v0, :not_custom_feed")
                    appendLine(options)
                    appendLine("return-void")
                },
                ExternalLabel("not_custom_feed", original)
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
                const-string v0, "$feed"
                invoke-virtual { p0, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0

                if-eqz v0, :not_custom_feed

                const/4 v0, 0x1
                return v0

                :not_custom_feed
                nop
            """
        )

        // Show the site's own icon for the feed. Icons are looked up by name and loaded from a url, so
        // the site's icon is given here rather than being bundled with the patch.
        subredditIconFingerprint.method.returnFromExtension("iconFor", parameter = 1)

        // Show the feed's title without the "[M]" the app marks custom feeds with, since this one
        // reads as a source rather than as a feed the account owns. p0 is the name being titled;
        // every other feed keeps its marker.
        subredditTitleFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "$feed"
                invoke-virtual { p0, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0

                if-eqz v0, :not_custom_feed

                const-string v0, "$feedName"
                return-object v0

                :not_custom_feed
                nop
            """
        )

        // Opening a custom feed lists the subreddits it contains. This feed does not exist on Reddit,
        // so that request fails and the app reports the feed could not be loaded.
        //
        // The request is pointed at an address that answers successfully instead, because a failed
        // response never reaches the parsing below: Volley raises the error status before parsing, so
        // replacing the response there would never run.
        multiSubredditsBuildUrlFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p2 }, $EXTENSION_CLASS_DESCRIPTOR->feedSubredditsUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0

                if-eqz v0, :not_custom_feed
                return-object v0

                :not_custom_feed
                nop
            """
        )

        parseMultiSubredditsNetworkResponseFingerprint.method.rewriteResponseBytes("rewriteFeedSubreddits")

        // Point a post's comments at the site. p1 is the post id, which is what one of the feed's
        // posts is recognised by - Reddit may have a subreddit of the same name, whose posts must
        // still be read from Reddit.
        //
        // This method keeps its parameters in low registers, so unlike the posts url builder they can
        // be used directly.
        commentsRequestBuildUrlFingerprint.method.returnFromExtension("buildCommentsUrl", parameter = 1)

        // Translate the post into the comment tree Sync parses, in the same way as the post list.
        parseCommentsNetworkResponseFingerprint.method.rewriteResponseBytes("rewriteComments")

        // Correct links to the site's content before they leave the app. Links are built from the
        // post's subreddit and id, which for this feed produce a reddit.com address that does not
        // exist, so they are rewritten to the post or comment they refer to.
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

        // The sheets offering a post or comment's link copy it through this rather than through the
        // method above, so the same correction is made here.
        copyTextFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->shareText(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
                move-result-object p0
            """
        )

        // The sheets offering a comment's link, and the links written in a post, copy through this.
        copyUrlFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->shareUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object p1
            """
        )

        // Opening a post's link outside the app hands over the address it is served under, which is
        // not one Reddit has. The site's page is opened instead.
        openExternallyFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->shareUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object p1
            """
        )

        // Stop write actions on the feed's content from being sent to Reddit. Voting, saving and
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

        // Anything the feed needs beyond what every feed does.
        block()
    }
}

/**
 * Names [extensionClass] as the feed the app serves.
 *
 * The patch calls the generic extension rather than the feed's own, so nothing would otherwise load
 * it. The name is written into the method the extension reads it from, which constructs it on first
 * use.
 */
context(_: BytecodePatchContext)
private fun nameFeedExtension(extensionClass: String) {
    // Written as the Java name of the class, which is what it is loaded by.
    val name = extensionClass.removePrefix("L").removeSuffix(";").replace('/', '.')

    feedClassFingerprint.method.addInstructions(
        0,
        """
            const-string v0, "$name"
            return-object v0
        """
    )
}

/**
 * Answers a url builder from the extension, leaving the request as it is when it answers nothing.
 *
 * The parameter holding the name being requested is named rather than found by shape: these builders
 * take several Strings each - the subreddit, the page, the sort - and only the caller knows which of
 * them names what is being asked for.
 *
 * @param parameter the parameter holding the subreddit, feed or post being requested.
 */
context(_: BytecodePatchContext)
private fun MutableMethod.returnFromExtension(extensionMethod: String, parameter: Int) {
    addInstructions(
        0,
        """
            invoke-static { p$parameter }, $EXTENSION_CLASS_DESCRIPTOR->$extensionMethod(Ljava/lang/String;)Ljava/lang/String;
            move-result-object v0

            if-eqz v0, :not_custom_feed
            return-object v0

            :not_custom_feed
            nop
        """
    )
}

/**
 * Drops the markers naming a post as archived and locked, leaving the rest of the line as it is.
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
 * Opens the feed in place of the subreddit the method was about to open, for one of its posts.
 *
 * The name is rewritten in the register it is passed in, just before it is handed over, so only this
 * way of opening a subreddit is affected and every other one still opens what it names.
 *
 * Both methods this is applied to are compiled with every register they have in use, so the post is
 * not fetched here. The holder or screen the name was tapped on is handed over as it stands, and the
 * post read from it there. The feed is opened only for one of its own posts, so a subreddit Reddit
 * has of the same name opens as it always did.
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
