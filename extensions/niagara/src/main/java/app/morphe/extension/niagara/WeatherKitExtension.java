package app.morphe.extension.niagara;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Apple WeatherKit weather source, using the key supplied as patch options.
 *
 * @noinspection unused
 */
public class WeatherKitExtension {
    private static final long TOKEN_LIFETIME_SECONDS = 3600;
    private static final int TIMEOUT_MS = 15_000;

    private static final long HOURLY_FORECAST_HOURS = 48; // As far ahead as Niagara shows

    private static String token;
    private static long tokenExpiresAt;

    static NiagaraWeather.Forecast fetch(double latitude, double longitude) throws Exception {
        return toForecast(request(latitude, longitude));
    }

    private static PrivateKey privateKey() throws Exception {
        byte[] der = Base64.decode(privateKeyBase64(), Base64.DEFAULT);
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static JSONObject request(double latitude, double longitude) throws Exception {
        String url = String.format(Locale.US,
                "https://weatherkit.apple.com/api/v1/weather/%s/%.4f/%.4f"
                        + "?dataSets=currentWeather,forecastHourly,forecastDaily&timezone=%s&hourlyEnd=%s",
                Locale.getDefault().toLanguageTag(), latitude, longitude,
                URLEncoder.encode(TimeZone.getDefault().getID(), "UTF-8"),
                Instant.now().plus(HOURLY_FORECAST_HOURS, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS));

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Authorization", "Bearer " + token());

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                InputStream error = connection.getErrorStream();
                String body = error != null ? new String(readAll(error), StandardCharsets.UTF_8) : "";
                throw new IOException("WeatherKit answered " + status + (body.isEmpty() ? "" : ": " + body));
            }

            return new JSONObject(new String(readAll(connection.getInputStream()), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Signed JWT, cached until shortly before it expires.
     */
    private static synchronized String token() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        if (token != null && now < tokenExpiresAt - 60) return token;

        String header = base64Url(new JSONObject()
                .put("alg", "ES256")
                .put("kid", keyId())
                .put("id", teamId() + "." + servicesId())
                .toString().getBytes(StandardCharsets.UTF_8));
        String payload = base64Url(new JSONObject()
                .put("iss", teamId())
                .put("sub", servicesId())
                .put("iat", now)
                .put("exp", now + TOKEN_LIFETIME_SECONDS)
                .toString().getBytes(StandardCharsets.UTF_8));

        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey());
        signature.update((header + "." + payload).getBytes(StandardCharsets.UTF_8));

        token = header + "." + payload + "." + base64Url(derToRaw(signature.sign()));
        tokenExpiresAt = now + TOKEN_LIFETIME_SECONDS;
        return token;
    }

    /**
     * Converts Java's DER-encoded ECDSA signature to the raw r||s form JWTs use.
     */
    private static byte[] derToRaw(byte[] der) {
        int offset = (der[1] & 0xff) == 0x81 ? 3 : 2;
        int rLength = der[offset + 1];
        int rStart = offset + 2;
        int sLength = der[rStart + rLength + 1];
        int sStart = rStart + rLength + 2;

        byte[] raw = new byte[64];
        copyInteger(der, rStart, rLength, raw, 0);
        copyInteger(der, sStart, sLength, raw, 32);
        return raw;
    }

    /**
     * Copies a DER integer right-aligned into 32 bytes, dropping leading zeros.
     */
    private static void copyInteger(byte[] der, int start, int length, byte[] raw, int at) {
        while (length > 32 && der[start] == 0) {
            start++;
            length--;
        }
        System.arraycopy(der, start, raw, at + 32 - length, length);
    }

    private static String base64Url(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static NiagaraWeather.Forecast toForecast(JSONObject weather) throws JSONException {
        NiagaraWeather.Forecast result = new NiagaraWeather.Forecast();

        JSONObject currentWeather = weather.getJSONObject("currentWeather");
        NiagaraWeather.Point current = new NiagaraWeather.Point();
        current.time = seconds(currentWeather.getString("asOf"));
        current.temperature = currentWeather.getDouble("temperature");
        current.feelsLike = currentWeather.optDouble("temperatureApparent", current.temperature);
        current.conditionCode = conditionCode(currentWeather.optString("conditionCode"));
        current.daylight = currentWeather.optBoolean("daylight", true);
        current.description = describe(currentWeather.optString("conditionCode"));
        result.current = current;

        JSONObject forecastHourly = weather.optJSONObject("forecastHourly");
        JSONArray hourly = forecastHourly != null ? forecastHourly.optJSONArray("hours") : null;
        if (hourly != null) {
            for (int i = 0; i < hourly.length(); i++) {
                JSONObject forecast = hourly.getJSONObject(i);
                NiagaraWeather.Point hour = new NiagaraWeather.Point();
                hour.time = seconds(forecast.getString("forecastStart"));
                hour.temperature = forecast.getDouble("temperature");
                hour.feelsLike = forecast.optDouble("temperatureApparent", hour.temperature);
                hour.precipitationChance = forecast.optDouble("precipitationChance", 0);
                hour.conditionCode = conditionCode(forecast.optString("conditionCode"));
                hour.daylight = forecast.optBoolean("daylight", true);
                result.hours.add(hour);
            }
        }

        // WeatherKit days start at midnight; Niagara's are at noon.
        JSONObject forecastDaily = weather.optJSONObject("forecastDaily");
        JSONArray daily = forecastDaily != null ? forecastDaily.optJSONArray("days") : null;
        if (daily != null) {
            for (int i = 0; i < daily.length(); i++) {
                JSONObject forecast = daily.getJSONObject(i);
                NiagaraWeather.Day day = new NiagaraWeather.Day();
                day.time = seconds(forecast.getString("forecastStart")) + 12 * 3600;
                day.minTemperature = forecast.getDouble("temperatureMin");
                day.maxTemperature = forecast.getDouble("temperatureMax");
                day.precipitationChance = forecast.optDouble("precipitationChance", 0);
                day.conditionCode = conditionCode(forecast.optString("conditionCode"));
                day.description = describe(forecast.optString("conditionCode"));
                if (forecast.has("sunrise")) day.sunrise = seconds(forecast.getString("sunrise"));
                if (forecast.has("sunset")) day.sunset = seconds(forecast.getString("sunset"));
                result.days.add(day);
            }
        }

        return result;
    }

    private static long seconds(String isoTime) {
        return Instant.parse(isoTime).getEpochSecond();
    }

    /**
     * Maps WeatherKit conditions to OpenWeatherMap codes. Unlisted conditions fall back to keyword
     * matching.
     */
    private static int conditionCode(String condition) {
        if (condition == null || condition.isEmpty()) return 800;

        switch (condition) {
            case "Clear":
            case "Hot":
            case "Frigid":
                return 800;
            case "MostlyClear":
                return 801;
            case "PartlyCloudy":
                return 802;
            case "MostlyCloudy":
                return 803;
            case "Cloudy":
                return 804;
            case "Haze":
                return 721;
            case "HeavyRain":
                return 502;
            case "Flurries":
            case "SunFlurries":
                return 600;
            case "HeavySnow":
            case "Blizzard":
            case "BlowingSnow":
                return 602;
            case "MixedRainAndSnow":
            case "MixedRainfall":
                return 616;
            case "IsolatedThunderstorms":
            case "ScatteredThunderstorms":
                return 210;
            case "SevereThunderstorm":
            case "StrongStorms":
                return 212;
            case "Tornado":
            case "Hurricane":
            case "TropicalStorm":
                return 781;
        }

        if (condition.contains("Thunder") || condition.contains("Storm")) return 211;
        if (condition.contains("Snow") || condition.contains("Flurr")) return 601;
        if (condition.contains("Sleet") || condition.contains("Wintry") || condition.contains("Mixed")
                || condition.contains("Hail")) return 611;
        if (condition.contains("Freezing")) return 511;
        if (condition.contains("Drizzle")) return 300;
        if (condition.contains("Rain") || condition.contains("Shower")) return 501;
        if (condition.contains("Fog")) return 741;
        if (condition.contains("Smok")) return 711;
        if (condition.contains("Dust")) return 761;
        if (condition.contains("Wind") || condition.contains("Breez")) return 771;
        if (condition.contains("Cloud")) return 804;
        return 800;
    }

    /**
     * "PartlyCloudy" -> "Partly cloudy".
     */
    private static String describe(String condition) {
        if (condition == null || condition.isEmpty()) return null;

        StringBuilder words = new StringBuilder();
        for (int i = 0; i < condition.length(); i++) {
            char c = condition.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                words.append(' ').append(Character.toLowerCase(c));
            } else {
                words.append(c);
            }
        }
        return words.toString();
    }

    // Placeholders, filled in by the patch from its options.

    private static String teamId() {
        return null;
    }

    private static String keyId() {
        return null;
    }

    private static String servicesId() {
        return null;
    }

    private static String privateKeyBase64() {
        return null;
    }
}
