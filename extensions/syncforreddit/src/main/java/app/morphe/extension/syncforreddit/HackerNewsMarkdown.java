package app.morphe.extension.syncforreddit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the HTML Hacker News returns into the Markdown Sync renders.
 *
 * <p>Hacker News serves a small, fixed subset of HTML - paragraphs, links, italics and preformatted
 * code - so the conversion is a handful of replacements rather than a parser.
 *
 * @noinspection unused
 */
public class HackerNewsMarkdown {
    private static final Pattern PARAGRAPH = Pattern.compile("(?i)<p>");
    private static final Pattern ITALIC = Pattern.compile("(?i)<i>(.*?)</i>", Pattern.DOTALL);
    private static final Pattern CODE = Pattern.compile("(?i)<pre>\\s*<code>(.*?)</code>\\s*</pre>", Pattern.DOTALL);
    /** Hacker News quotes attributes with either kind of quote, so both are matched. */
    private static final Pattern LINK =
            Pattern.compile("(?i)<a[^>]*href=[\"']([^\"']*)[\"'][^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern REMAINING_TAG = Pattern.compile("<[^>]+>");

    public static String fromHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        String text = html;

        // Code first, so that markup inside a block is not itself converted.
        text = replace(CODE, text, new Replacer() {
            @Override
            public String replace(Matcher matcher) {
                StringBuilder indented = new StringBuilder("\n");
                for (String line : unescape(matcher.group(1)).split("\n")) {
                    indented.append("    ").append(line).append("\n");
                }
                return indented.toString();
            }
        });

        text = replace(LINK, text, new Replacer() {
            @Override
            public String replace(Matcher matcher) {
                String href = matcher.group(1);
                String label = stripTags(matcher.group(2)).trim();
                // Hacker News truncates long link text, so a bare url is left to render on its own.
                return label.isEmpty() || href.startsWith(label.replace("...", ""))
                        ? href
                        : "[" + label + "](" + href + ")";
            }
        });

        text = replace(ITALIC, text, new Replacer() {
            @Override
            public String replace(Matcher matcher) {
                return "*" + matcher.group(1) + "*";
            }
        });

        // Hacker News separates paragraphs with an opening <p> and no closing tag.
        text = PARAGRAPH.matcher(text).replaceAll("\n\n");

        text = stripTags(text);
        return unescape(text).trim();
    }

    private static String stripTags(String text) {
        return REMAINING_TAG.matcher(text).replaceAll("");
    }

    /** Decodes the html entities Hacker News escapes its text with. */
    private static String unescape(String text) {
        return text
                .replace("&#x27;", "'")
                .replace("&#x2F;", "/")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                // Ampersand last, so an escaped entity is not decoded twice.
                .replace("&amp;", "&");
    }

    private interface Replacer {
        String replace(Matcher matcher);
    }

    /** Applies [replacer] to every match, quoting the result so it is inserted literally. */
    private static String replace(Pattern pattern, String text, Replacer replacer) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacer.replace(matcher)));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
