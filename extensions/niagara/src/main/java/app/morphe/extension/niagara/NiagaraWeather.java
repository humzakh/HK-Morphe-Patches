package app.morphe.extension.niagara;

import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers Niagara's weather server request from a patch-provided source, by converting the source's
 * {@link Forecast} into the server's response format. Niagara parses and caches the response itself.
 * <p>
 * Units: Celsius, epoch seconds, OpenWeatherMap condition codes.
 *
 * @noinspection unused
 */
public final class NiagaraWeather {
    private static final String TAG = "NiagaraWeather";

    static final class Point {
        long time;
        double temperature;
        double feelsLike;
        double precipitationChance; // 0 to 1
        int conditionCode;
        boolean daylight;
        String description;
    }

    static final class Day {
        long time; // Noon
        double minTemperature;
        double maxTemperature;
        double precipitationChance; // 0 to 1
        int conditionCode;
        long sunrise; // 0 if none
        long sunset;
        String description;
    }

    static final class Forecast {
        Point current;
        final List<Point> hours = new ArrayList<>(); // From now
        final List<Day> days = new ArrayList<>(); // From today
    }

    private NiagaraWeather() {
    }

    /**
     * Called in place of Niagara's request to its weather server, off the main thread.
     *
     * @param request The OkHttp request, whose URL carries the lat, long and lang query parameters.
     * @return The server's response body, or null to send the request to the server.
     */
    public static String fetchResponse(Object request) {
        try {
            String url = request.toString();
            String latitude = queryParameter(url, "lat");
            String longitude = queryParameter(url, "long");

            Forecast forecast = fetchFromSource(Double.parseDouble(latitude), Double.parseDouble(longitude));
            if (forecast == null) return null;

            return response(forecast, latitude, longitude, queryParameter(url, "lang"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch weather", e);
            return null;
        }
    }

    /**
     * A query parameter of the URL in OkHttp's {@code Request{method=GET, url=...}} string.
     */
    private static String queryParameter(String request, String name) {
        Matcher matcher = Pattern.compile("[?&]" + name + "=([^&,}\\s]*)").matcher(request);
        return matcher.find() ? Uri.decode(matcher.group(1)) : null;
    }

    private static String response(Forecast forecast, String latitude, String longitude, String language)
            throws JSONException {
        Point current = forecast.current;
        List<Point> hours = forecast.hours;
        List<Day> days = forecast.days;

        // Niagara interpolates between points, so pin the current reading until just before the next
        // hourly forecast, dropping the forecasts before it.
        long currentTime = current.time;
        for (Point hour : hours) {
            if (hour.time > current.time) {
                currentTime = Math.max(current.time, hour.time - 60);
                break;
            }
        }

        JSONObject currentJson = new JSONObject()
                .put("dt", currentTime)
                .put("temp", current.temperature)
                .put("feels_like", current.feelsLike)
                .put("weather", conditions(current.conditionCode, current.daylight, current.description));

        JSONArray hourly = new JSONArray();
        for (Point hour : hours) {
            if (hour.time <= currentTime) continue;

            hourly.put(new JSONObject()
                    .put("dt", hour.time)
                    .put("temp", hour.temperature)
                    .put("feels_like", hour.feelsLike)
                    .put("pop", hour.precipitationChance)
                    .put("weather", conditions(hour.conditionCode, hour.daylight, hour.description)));
        }

        JSONArray daily = new JSONArray();
        for (Day day : days) {
            JSONObject dayJson = new JSONObject()
                    .put("dt", day.time)
                    .put("temp", new JSONObject()
                            .put("day", day.maxTemperature)
                            .put("min", day.minTemperature)
                            .put("max", day.maxTemperature)
                            .put("night", day.minTemperature)
                            .put("eve", day.maxTemperature)
                            .put("morn", day.minTemperature))
                    .put("pop", day.precipitationChance)
                    .put("weather", conditions(day.conditionCode, true, day.description));
            if (day.sunrise > 0) dayJson.put("sunrise", day.sunrise);
            if (day.sunset > 0) dayJson.put("sunset", day.sunset);
            daily.put(dayJson);
        }

        JSONObject forecastJson = new JSONObject()
                .put("current", currentJson)
                .put("hourly", hourly)
                .put("daily", daily);

        return new JSONObject()
                .put("timestamp", current.time)
                .put("provider", "external")
                .put("lat", latitude)
                .put("long", longitude)
                .put("lang", language)
                .put("forecast", forecastJson)
                .toString();
    }

    private static JSONArray conditions(int conditionCode, boolean daylight, String description) throws JSONException {
        int id = niagaraConditionCode(conditionCode);
        JSONObject condition = new JSONObject()
                .put("id", id)
                .put("icon", iconPrefix(id) + (daylight ? "d" : "n"));
        if (description != null && !description.isEmpty()) condition.put("description", description);
        return new JSONArray().put(condition);
    }

    /**
     * Niagara crashes on codes it doesn't draw, so the legacy 9xx codes are mapped to current ones.
     */
    private static int niagaraConditionCode(int code) {
        if ((code >= 200 && code < 400) || (code >= 500 && code < 800) || (code >= 800 && code <= 809)) {
            return code;
        }

        switch (code) {
            case 900: // Tornado
            case 901: // Tropical storm
            case 902: // Hurricane
                return 781;
            case 905: // Windy
                return 771;
            case 906: // Hail
                return 611;
            default: // Including cold (903) and hot (904)
                return 800;
        }
    }

    /**
     * OpenWeatherMap icon without its d/n suffix. Niagara only reads the suffix.
     */
    private static String iconPrefix(int code) {
        if (code < 300) return "11";
        if (code < 500) return "09";
        if (code == 511) return "13";
        if (code < 600) return "10";
        if (code < 700) return "13";
        if (code < 800) return "50";
        if (code == 800) return "01";
        if (code == 801) return "02";
        if (code == 802) return "03";
        return "04";
    }

    // Placeholder, filled in by the source patch.

    static Forecast fetchFromSource(double latitude, double longitude) throws Exception {
        return null;
    }
}
