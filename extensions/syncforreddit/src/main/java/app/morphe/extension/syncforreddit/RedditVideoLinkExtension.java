package app.morphe.extension.syncforreddit;

import java.util.regex.Pattern;

public class RedditVideoLinkExtension {
    private static final Pattern STRANGE_VIDEO_PATTERN = Pattern.compile("(https?://)?(www\\.)?reddit\\.com/link/[^/]+/video/([^/?]+)(?:/player)?", Pattern.CASE_INSENSITIVE);

    public static String fixVideoLink(String text) {
        if (text == null) {
            return null;
        }

        return STRANGE_VIDEO_PATTERN.matcher(text).replaceAll("https://v.redd.it/$3");
    }
}
