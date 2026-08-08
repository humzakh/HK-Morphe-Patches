package app.morphe.extension.syncforreddit;

import android.content.Context;

/**
 * Builds the caption text to show below an inline image, or null if none should be shown.
 *
 * @noinspection unused
 */
public class InlineImageCaptionExtension {
    private static final String PACK_DELIMITER = "\u0001";

    /**
     * @param packedLinkTextAndUrl the link's display text and its URL joined with
     *                             {@link #PACK_DELIMITER}. Packed into one argument because the
     *                             call site can only spare a single free register to carry data
     *                             across the surrounding method's existing bytecode.
     */
    public static String buildCaption(String packedLinkTextAndUrl) {
        int delimiterIndex = packedLinkTextAndUrl.indexOf(PACK_DELIMITER);
        String linkText = packedLinkTextAndUrl.substring(0, delimiterIndex);
        String url = packedLinkTextAndUrl.substring(delimiterIndex + 1);

        if (linkText.isEmpty() || linkText.equalsIgnoreCase(url)) {
            return null;
        }
        return linkText;
    }

    /** Opens the tapped inline image, passing its caption through as the viewer's title. */
    public static boolean openImageWithCaption(Context context, String url, String caption) {
        return y7.b.c(context, null, null, url, null, caption, null, false, false, false);
    }
}
