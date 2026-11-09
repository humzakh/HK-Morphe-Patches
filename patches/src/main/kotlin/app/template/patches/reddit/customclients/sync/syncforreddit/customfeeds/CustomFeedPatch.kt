package app.template.patches.reddit.customclients.sync.syncforreddit.customfeeds

import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible
import app.template.patches.reddit.customclients.sync.syncforreddit.api.getUserAgentFingerprint

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
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
 * The options left off the feed's sheet of subreddit options, named as they are listed.
 *
 * Each acts on a subreddit through Reddit - its moderators, its flairs, and the custom feeds an
 * account collects subreddits into - so none has anything to act on here. Matched against the
 * extension's own list, which decides whether an option is shown.
 */
private val HIDDEN_OPTIONS = listOf("Message mods", "View mods", "Flair", "Add to multi")

/**
 * The settings the account's details are kept under, matching the extension's own names and the ones
 * the account patch lists them by.
 */
private val ACCOUNT_SETTINGS = listOf("custom_feed_username", "custom_feed_password")

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

    // Asks for the account the feed's writes are made as, which the site offers no other way of being
    // acted on by. Applied as part of this patch for the same reason as the links above.
    dependsOn(customFeedAccountPatch(feedName))

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

            // The token belongs to whichever client the app signs in as, which another patch may have
            // changed, so the user agent sent with it is the one the app itself reports rather than a
            // copy of the original.
            val userAgentMethod = getUserAgentFingerprint.method

            // The method declares enough locals to push "this" out of the range the four bit register
            // forms can address, so it is moved down before the field is read off it.
            addInstructions(
                0,
                """
                    move-object/from16 v0, p0
                    iget-object v0, v0, ${tokenField.definingClass}->${tokenField.name}:${tokenField.type}
                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->noteToken(Ljava/lang/String;)V

                    invoke-static { }, ${userAgentMethod.definingClass}->${userAgentMethod.name}()Ljava/lang/String;
                    move-result-object v0
                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->noteUserAgent(Ljava/lang/String;)V
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

        // The page asked for is passed along with the feed, so that a link from one of the site's pages
        // to another is served in the same sheet rather than leaving the app. p1 is the subreddit or
        // feed and p2 the page within it.
        wikiBuildUrlFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1, p2 }, $EXTENSION_CLASS_DESCRIPTOR->faqUrlFor(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0

                if-eqz v0, :not_custom_feed
                return-object v0

                :not_custom_feed
                nop
            """
        )

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

        // The sheets showing one of the feed's pages share through a method taking only the link, so
        // the same correction is made there. That is where a page's own address is corrected to the
        // one it is read from on the site.
        shareOnlyLinkFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->shareUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object p1
            """
        )

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

        // Leave the chip offering to join a subreddit off the feed. The feed is shown through a
        // subreddit's chips, because it is treated as a subreddit for the sake of showing an icon,
        // and there is nothing on Reddit to join here.
        //
        // The chip is hidden where it is filled in, which is the one place it is made visible and the
        // one every way of reaching it runs through - opening the feed and subscribing elsewhere both
        // come back through here.
        // The name being shown is read the way these methods already read it. Only some of them do,
        // so it is taken from the one that does and used for all of them.
        val chipGroupName = watchChipFingerprint.method.instructions
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .first { it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }

        listOf(joinChipFingerprint, flairChipFingerprint, watchChipFingerprint)
            .forEach { it.method.hideChipOnFeed(chipGroupName) }

        // Title the sheet of options as the feed rather than as a subreddit, which it is shown
        // through but is not one of.
        subredditOptionsTitleFingerprint.method.apply {
            val nameMethod = sheetNameMethod(subredditOptionsTitleFingerprint.originalClassDef)

            val titleIndex = instructions.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                    "Subreddit options"
            }
            val titleRegister = getInstruction<OneRegisterInstruction>(titleIndex).registerA

            // The method is compiled with the single register the title is built in, so the name is
            // read into it and the title passed from where it already is - the answer is returned in
            // the same register either way.
            addInstructions(
                titleIndex + 1,
                """
                    invoke-virtual { p0 }, ${nameMethod.definingClass}->${nameMethod.name}()${nameMethod.returnType}
                    move-result-object p0

                    invoke-static { p0, v$titleRegister }, $EXTENSION_CLASS_DESCRIPTOR->optionsTitle(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$titleRegister
                """
            )
        }

        // Leave the options that act on a subreddit through Reddit off the feed's sheet. Each option
        // is registered as it is built, so the registering is skipped for the ones left out and the
        // option is built but never listed.
        //
        // The option is still assigned to the field the sheet dispatches on, which compares by
        // identity, so one that was never listed simply never matches and the dispatch is unaffected.
        // The registering call answers the option it was given back into the register it was passed
        // in, so skipping the call leaves that register holding what the field is then stored from.
        subredditOptionsFingerprint.method.apply {
            val nameMethod = sheetNameMethod(subredditOptionsFingerprint.originalClassDef)

            // Taken from the back forwards, so that each insertion does not move the ones still to be
            // made.
            HIDDEN_OPTIONS.map { option ->
                option to instructions.indexOfFirst {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == option
                }
            }.sortedByDescending { (_, index) -> index }.forEach { (option, labelIndex) ->
                val labelRegister = getInstruction<OneRegisterInstruction>(labelIndex).registerA

                // The register the option's icon was built from, which is finished with by this
                // point. Reused rather than claiming another, since the method is compiled with only
                // the registers it uses and anything past them is where its parameters are.
                val scratchRegister = getInstruction<OneRegisterInstruction>(labelIndex - 1).registerA

                // The call registering the option, which is the next one made after it is built.
                val registerIndex = instructions
                    .drop(labelIndex)
                    .indexOfFirst { instruction ->
                        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                            ((instruction as ReferenceInstruction).reference as MethodReference)
                                .parameterTypes.size == 1
                    } + labelIndex

                addInstructionsWithLabels(
                    registerIndex,
                    """
                        invoke-virtual { p0 }, ${nameMethod.definingClass}->${nameMethod.name}()${nameMethod.returnType}
                        move-result-object v$labelRegister

                        const-string v$scratchRegister, "$option"

                        invoke-static { v$labelRegister, v$scratchRegister }, $EXTENSION_CLASS_DESCRIPTOR->showsOption(Ljava/lang/String;Ljava/lang/String;)Z
                        move-result v$labelRegister

                        if-eqz v$labelRegister, :skips_option
                    """,
                    // Past the answer the skipped call would have been read from as well as the call
                    // itself, since a move-result may not be branched to. The register it would have
                    // answered into is the one the option was passed in, so it already holds it.
                    ExternalLabel("skips_option", getInstruction(registerIndex + 2))
                )
            }
        }

        // Title the dialog offering to override the sort as the feed, in the same way as the sheet it
        // is opened from.
        sortOverrideDialogFingerprint.method.apply {
            val nameMethod = sheetNameMethod(sortOverrideDialogFingerprint.originalClassDef)

            val titleIndex = instructions.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                    "Update subreddit sort"
            }
            val titleRegister = getInstruction<OneRegisterInstruction>(titleIndex).registerA

            // The name is read into a register of its own, so that the title it is answered against
            // is still there to be passed. The method builds the dialog one call at a time, so the
            // register past the two the title is built with is free at this point.
            val nameRegister = titleRegister + 1

            addInstructions(
                titleIndex + 1,
                """
                    invoke-virtual { p0 }, ${nameMethod.definingClass}->${nameMethod.name}()${nameMethod.returnType}
                    move-result-object v$nameRegister

                    invoke-static { v$nameRegister, v$titleRegister }, $EXTENSION_CLASS_DESCRIPTOR->sortDialogTitle(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$titleRegister
                """
            )
        }

        // Offer the feeds the site serves in place of Reddit's sorts, which mean nothing here.
        //
        // The dialog reads two arrays: the labels it shows and the values it stores for them, picked
        // by the same index. Both are answered with the feed's own sorts, so what is picked is stored
        // as the sort it is rather than as one of Reddit's codes.
        sortOverrideOptionsFingerprint.method.apply {
            // The name being sorted, which the method reads for itself before each array. This runs
            // on the listener the dialog was opened with rather than on the sheet, so the sheet is
            // taken off the field holding it first, the same way the method itself does.
            val nameMethod = instructions
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .first { it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }

            // The field the sheet is held on, taken as the one it is read off just before the name is
            // asked for.
            val nameIndex = instructions.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? MethodReference) == nameMethod
            }
            val sheetField = instructions
                .take(nameIndex)
                .last { it.opcode == Opcode.IGET_OBJECT }
                .let { (it as ReferenceInstruction).reference as FieldReference }

            // Both arrays are read the same way, so each is answered where it is read. Taken from the
            // back forwards, so that the earlier insertion does not move the later one.
            instructions
                .withIndex()
                .filter { (_, instruction) ->
                    instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                        ((instruction as ReferenceInstruction).reference as MethodReference).name ==
                        "getStringArray"
                }
                .map { (index, _) -> index }
                .sortedDescending()
                .forEach { arrayIndex ->
                    val arrayRegister =
                        getInstruction<OneRegisterInstruction>(arrayIndex + 1).registerA

                    // The register the array's resource id was passed in, which is finished with once
                    // the array has been read. Reused rather than claiming another, since every
                    // register this method has is otherwise in use.
                    val scratchRegister =
                        getInstruction<FiveRegisterInstruction>(arrayIndex).registerD

                    // The array is answered back into the register it was read into, so no register
                    // has to be claimed from a method whose own are all in use. Anything but the feed
                    // is answered with the array it was read with, leaving it as it was.
                    addInstructions(
                        arrayIndex + 2,
                        """
                            iget-object v$scratchRegister, p0, ${sheetField.definingClass}->${sheetField.name}:${sheetField.type}
                            invoke-virtual { v$scratchRegister }, ${nameMethod.definingClass}->${nameMethod.name}()${nameMethod.returnType}
                            move-result-object v$scratchRegister

                            invoke-static { v$scratchRegister, v$arrayRegister }, $EXTENSION_CLASS_DESCRIPTOR->sortOptions(Ljava/lang/String;[Ljava/lang/String;)[Ljava/lang/String;
                            move-result-object v$arrayRegister
                        """
                    )
                }
        }

        // Read one of the feed's own sorts back as the sort it is. The app decodes what it stored into
        // a sort and a time range, reading anything it does not recognise as "New", which the feed's
        // sorts would otherwise be flattened to.
        decodeSortFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->decodeSort(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0

                if-eqz v0, :not_custom_feed

                const/4 v1, 0x0
                invoke-static { v0, v1 }, Landroid/util/Pair;->create(Ljava/lang/Object;Ljava/lang/Object;)Landroid/util/Pair;
                move-result-object v0
                return-object v0

                :not_custom_feed
                nop
            """
        )

        // Name the sort actually being read on the chip. The screen holds the sort it was opened
        // with, which for this feed may be one of Reddit's that it does not have: the request falls
        // back to the feed's own sort, but nothing corrects what is shown, so the chip would name a
        // sort the posts under it were not read with.
        //
        // The time range is dropped along with it, since the feed's sorts have none and naming one
        // would read as a range that had been applied.
        sortChipFingerprint.method.apply {
            // The accessor holding the name being shown, taken from the chip group this is declared on
            // rather than by shape - the method builds a string of its own, whose toString would
            // otherwise be picked as a no argument method answering a String.
            val nameMethod = mutableClassDefBy(definingClass)
                .methods
                .first {
                    it.parameters.isEmpty() &&
                        it.returnType == "Ljava/lang/String;" &&
                        AccessFlags.PROTECTED.isSet(it.accessFlags)
                }

            addInstructions(
                0,
                """
                    invoke-virtual { p0 }, $definingClass->${nameMethod.name}()${nameMethod.returnType}
                    move-result-object v0

                    invoke-static { v0, p1 }, $EXTENSION_CLASS_DESCRIPTOR->sortLabel(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p1

                    invoke-virtual { p0 }, $definingClass->${nameMethod.name}()${nameMethod.returnType}
                    move-result-object v0

                    invoke-static { v0, p2 }, $EXTENSION_CLASS_DESCRIPTOR->sortRangeLabel(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p2
                """
            )
        }

        // Ask for the account's details from the screen they are listed on. They are plain rows, which
        // the app does not open a dialog for, so each is given the input it asks for its value with
        // once the screen has been built.
        //
        // Each row is found here rather than in the extension: the preference library the app is built
        // on is obfuscated, so neither the screen nor the method finding a row on it can be named from
        // there. Both are read from how the method already finds the rows it sets up itself.
        linksSettingsFingerprint.method.apply {
            val findPreference = instructions
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .first {
                    it.parameterTypes.map(CharSequence::toString) ==
                        listOf("Ljava/lang/CharSequence;") &&
                        it.returnType == "Landroidx/preference/Preference;"
                }

            // The method is compiled with no locals of its own, so its parameter registers are all
            // there is to work in. The screen is the one thing that must survive, and it is passed in
            // p0, so each row is looked up and handed over before the next one writes over anything -
            // p0 itself is only read, never written.
            addInstructions(
                instructions.count() - 1,
                ACCOUNT_SETTINGS.joinToString("\n\n") { key ->
                    """
                        const-string p1, "$key"
                        invoke-virtual { p0, p1 }, ${findPreference.definingClass}->${findPreference.name}(${findPreference.parameterTypes[0]})${findPreference.returnType}
                        move-result-object p1

                        const-string p2, "$key"
                        invoke-static { p1, p2 }, $EXTENSION_CLASS_DESCRIPTOR->bindAccountSetting(Ljava/lang/Object;Ljava/lang/String;)V
                    """.trimIndent()
                }
            )
        }

        // Note which way round a save is, which its parameters do not say. p1 is the flag the address
        // is chosen by, which is set for the undoing of a save.
        saveBuildUrlFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->noteSave(Z)V
            """
        )

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

/**
 * Leaves the chip this method fills in off the feed, showing it as it was for anything else.
 *
 * The chip is taken from the method rather than named, since each of these methods reads only its
 * own.
 *
 * @param nameMethod the accessor holding the subreddit or feed being shown.
 *
 * The chip is shown again on the way out rather than only being hidden. The app builds one chip group
 * and shows it for every subreddit, so a chip hidden for the feed would stay hidden for the
 * subreddits opened after it - the app's own paths never have to undo this, since what they hide a
 * chip for does not change between subreddits.
 *
 * Each of these methods is compiled with the two registers this needs, so the chip is read into one
 * of them before the answer is tested rather than claiming another.
 */
context(_: BytecodePatchContext)
private fun MutableMethod.hideChipOnFeed(nameMethod: MethodReference) {
    val chipField = instructions
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
        .first { it.definingClass == definingClass }

    addInstructions(
        0,
        """
            invoke-virtual { p0 }, ${nameMethod.definingClass}->${nameMethod.name}()${nameMethod.returnType}
            move-result-object v0

            invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->isFeedName(Ljava/lang/String;)Z
            move-result v0

            iget-object v1, p0, ${chipField.definingClass}->${chipField.name}:${chipField.type}

            if-eqz v0, :not_custom_feed

            const/16 v0, 0x8
            invoke-virtual { v1, v0 }, Landroid/widget/CheckBox;->setVisibility(I)V
            return-void

            :not_custom_feed
            const/4 v0, 0x0
            invoke-virtual { v1, v0 }, Landroid/widget/CheckBox;->setVisibility(I)V
        """
    )
}

/**
 * The accessor holding the subreddit or feed the sheet was opened for.
 *
 * Read from the method dispatching the sheet's options, which reads it the same way for each of them,
 * rather than being named.
 */
context(context: BytecodePatchContext)
private fun sheetNameMethod(classDef: ClassDef): MethodReference =
    context.mutableClassDefBy(classDef)
        .methods
        .first { it.name == "r0" }
        .instructions
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .first { it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }
