package app.morphe.extension.syncforreddit;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Method;

public class SwipeToReturnExtension {
    public static void convertToTranslucentDelayed(Activity activity) {
        // Wait 100ms for the default system opaque slide animation to completely finish.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Method method = Activity.class.getDeclaredMethod("setTranslucent", boolean.class);
                method.setAccessible(true);
                method.invoke(activity, true);
            } catch (Exception e) {
                // Ignore reflection exceptions
            }
        }, 100);
    }
}
