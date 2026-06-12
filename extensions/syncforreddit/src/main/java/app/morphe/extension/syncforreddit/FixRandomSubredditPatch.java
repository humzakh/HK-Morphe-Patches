package app.morphe.extension.syncforreddit;

import org.json.JSONObject;

/**
 * Fixes the random subreddit button by fetching from an alternative API.
 *
 * @noinspection unused
 */
public class FixRandomSubredditPatch {
    public static String parseRandomResponse(byte[] data) {
        try {
            String responseBody = new String(data, "UTF-8");
            JSONObject json = new JSONObject(responseBody);
            String urlPath = json.getString("url");
            return urlPath.replaceAll("^/r/", "").replaceAll("/$", "");
        } catch (Exception e) {
            return null;
        }
    }
}
