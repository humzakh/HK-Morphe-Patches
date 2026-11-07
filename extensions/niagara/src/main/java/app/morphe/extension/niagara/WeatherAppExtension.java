package app.morphe.extension.niagara;

import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Asks which app to open when the forecast is long pressed, in place of opening OpenWeather.
 *
 * @noinspection unused
 */
public final class WeatherAppExtension {
    private static final String TAG = "WeatherApp";

    private static final String PREFERENCES = "morphe_weather_app";
    private static final String KEY_COMPONENT = "component";

    private WeatherAppExtension() {
    }

    /**
     * Called in place of Niagara launching OpenWeather.
     */
    public static void open(Intent intent, Context context, View view) {
        try {
            Context viewContext = view != null ? view.getContext() : context;
            ComponentName saved = savedApp(context);
            if (saved != null) {
                launch(context, view, saved);
            } else {
                showPicker(viewContext, view);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to open weather app", e);
        }
    }

    /**
     * The saved app, or null if none is saved or it was uninstalled.
     */
    private static ComponentName savedApp(Context context) {
        String flattened = preferences(context).getString(KEY_COMPONENT, null);
        if (flattened == null) return null;

        ComponentName component = ComponentName.unflattenFromString(flattened);
        try {
            if (component != null) {
                context.getPackageManager().getActivityInfo(component, 0);
                return component;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        preferences(context).edit().remove(KEY_COMPONENT).apply();
        return null;
    }

    private static void showPicker(Context context, View view) {
        PackageManager packageManager = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);

        List<App> apps = new ArrayList<>();
        for (ResolveInfo info : packageManager.queryIntentActivities(launcher, 0)) {
            if (info.activityInfo.packageName.equals(context.getPackageName())) continue;

            App app = new App();
            app.component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
            app.label = info.loadLabel(packageManager).toString();
            app.icon = info.loadIcon(packageManager);
            apps.add(app);
        }
        // Weather apps first, then alphabetical.
        Collections.sort(apps, (a, b) -> {
            if (a.isWeather() != b.isWeather()) return a.isWeather() ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });

        int iconSize = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32,
                context.getResources().getDisplayMetrics()));
        int iconPadding = iconSize / 2;
        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int theme = night
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;

        AlertDialog.Builder builder = new AlertDialog.Builder(context, theme);
        ArrayAdapter<App> adapter = new ArrayAdapter<App>(builder.getContext(),
                android.R.layout.select_dialog_singlechoice, apps) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                App app = getItem(position);
                row.setText(app.label);
                app.icon.setBounds(0, 0, iconSize, iconSize);
                row.setCompoundDrawablesRelative(app.icon, null, null, null);
                row.setCompoundDrawablePadding(iconPadding);
                return row;
            }
        };

        int[] selected = {-1};
        AlertDialog dialog = builder
                .setTitle("Open in app")
                .setSingleChoiceItems(adapter, -1, (d, which) -> {
                    selected[0] = which;
                    AlertDialog alert = (AlertDialog) d;
                    alert.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    alert.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                })
                .setNeutralButton("Just once", (d, which) ->
                        launch(context, view, apps.get(selected[0]).component))
                .setPositiveButton("Always", (d, which) -> {
                    ComponentName component = apps.get(selected[0]).component;
                    preferences(context).edit().putString(KEY_COMPONENT, component.flattenToString()).apply();
                    launch(context, view, component);
                })
                .create();
        dialog.setOnShowListener(d -> {
            Button always = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button once = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            always.setEnabled(false);
            once.setEnabled(false);
        });
        dialog.show();
    }

    /**
     * Launches the app as Niagara launches apps, zooming from the view pressed.
     */
    private static void launch(Context context, View view, ComponentName component) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        if (view == null) {
            context.startActivity(intent);
            return;
        }

        ActivityOptions options = ActivityOptions.makeScaleUpAnimation(
                view, 0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        context.startActivity(intent, options.toBundle());
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static final class App {
        ComponentName component;
        String label;
        Drawable icon;

        boolean isWeather() {
            return label.toLowerCase(Locale.ROOT).contains("weather")
                    || component.getPackageName().toLowerCase(Locale.ROOT).contains("weather");
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
