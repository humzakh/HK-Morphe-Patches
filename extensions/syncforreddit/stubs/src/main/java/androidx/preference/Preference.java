package androidx.preference;

import android.content.Context;

/**
 * One row of the app's settings.
 *
 * <p>From the preference library the app is built on, which is already in the app. The library is
 * obfuscated there, so the methods are named as they are in the app rather than as the library
 * declares them: {@code k} is {@code getContext}, {@code D0} is {@code setSummary} and {@code A0} is
 * {@code setOnPreferenceClickListener}.
 */
public class Preference {
    /** getContext. */
    public Context k() {
        return null;
    }

    /** setSummary. */
    public void D0(CharSequence summary) {
    }

    /** setOnPreferenceClickListener. */
    public void A0(d listener) {
    }

    /** OnPreferenceClickListener, told when one of the rows is tapped. */
    public interface d {
        /** onPreferenceClick. */
        boolean a(Preference preference);
    }
}
