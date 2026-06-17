package app.morphe.extension.syncforreddit;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Outline;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedDispatcher;

import java.lang.reflect.Method;

public class SwipeToReturnExtension {
    private static final long TRANSLUCENCY_DELAY_MS = 100;

    public static void convertToTranslucentDelayed(Activity activity) {
        // 1. Wait to become translucent to ensure the open animation remains the smooth native opaque slide.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Method method = Activity.class.getDeclaredMethod("setTranslucent", boolean.class);
                method.setAccessible(true);
                method.invoke(activity, true);
            } catch (Exception e) {
                // Ignore
            }
        }, TRANSLUCENCY_DELAY_MS);

        // 2. Register Android 14+ Custom Predictive Back Handler
        if (Build.VERSION.SDK_INT >= 34) {
            registerPredictiveBackHandler(activity);
        }
    }

    private static void registerPredictiveBackHandler(Activity activity) {
        OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();

        dispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                new OnBackAnimationCallback() {
                    View rootView;
                    float currentProgress = 0f;
                    float initialTouchY = 0f;

                    private void init() {
                        if (rootView == null) {
                            rootView = activity.getWindow().getDecorView();
                        }
                    }

                    @Override
                    public void onBackStarted(BackEvent backEvent) {
                        init();
                        if (rootView == null) return;
                        currentProgress = 0f;
                        initialTouchY = backEvent.getTouchY();

                        rootView.setClipToOutline(true);
                        rootView.setOutlineProvider(new ViewOutlineProvider() {
                            @Override
                            public void getOutline(View view, Outline outline) {
                                float radius = 48f * activity.getResources().getDisplayMetrics().density * currentProgress;
                                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                            }
                        });
                    }

                    @Override
                    public void onBackProgressed(BackEvent backEvent) {
                        if (rootView == null) return;
                        currentProgress = backEvent.getProgress();

                        // Scale guideline: 90% minimum scale
                        float scale = 1.0f - (currentProgress * 0.10f);
                        rootView.setScaleX(scale);
                        rootView.setScaleY(scale);
                        
                        float density = activity.getResources().getDisplayMetrics().density;
                        
                        // X shift guideline: ((screen width / 20) - 8) dp
                        float maxXShift = (rootView.getWidth() / 20f) - (8f * density);
                        int swipeEdge = backEvent.getSwipeEdge();
                        float xDirection = (swipeEdge == BackEvent.EDGE_LEFT) ? -1f : 1f;
                        rootView.setTranslationX(xDirection * maxXShift * currentProgress);
                        
                        // Y shift guideline: ((available screen height / 20) - 8) dp
                        float maxYShift = (rootView.getHeight() / 20f) - (8f * density);
                        float deltaY = backEvent.getTouchY() - initialTouchY;
                        
                        // Track finger with heavier dampening so it feels physically weighty, capped at maxYShift
                        float yShift = Math.max(-maxYShift, Math.min(maxYShift, deltaY * 0.15f));
                        rootView.setTranslationY(yShift);
                        
                        // Update corner radius
                        rootView.invalidateOutline();
                    }

                    @Override
                    public void onBackInvoked() {
                        if (rootView == null) {
                            activity.finish();
                            return;
                        }

                        // Just fade out and shrink slightly more, without the drastic horizontal slide
                        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
                        animator.setDuration(250);
                        animator.setInterpolator(new android.view.animation.PathInterpolator(0.4f, 0.0f, 0.2f, 1.0f));

                        final float density = activity.getResources().getDisplayMetrics().density;
                        final float startScale = rootView.getScaleX();
                        final float endScale = 0.90f; // Spec minimum scale
                        final float startTranslationX = rootView.getTranslationX();
                        // Slightly push further into the shift direction as it exits
                        final float endTranslationX = startTranslationX * 1.2f; 
                        final float startTranslationY = rootView.getTranslationY();

                        animator.addUpdateListener(animation -> {
                            float fraction = animation.getAnimatedFraction();
                            // Grow again as it exits
                            rootView.setScaleX(startScale + (endScale - startScale) * fraction);
                            rootView.setScaleY(startScale + (endScale - startScale) * fraction);
                            // Slide to the right
                            rootView.setTranslationX(startTranslationX + (endTranslationX - startTranslationX) * fraction);
                            // Center vertically as it exits
                            rootView.setTranslationY(startTranslationY * (1.0f - fraction));
                            // Fade out completely
                            rootView.setAlpha(1.0f - fraction);
                        });

                        animator.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                activity.finish();
                                activity.overridePendingTransition(0, 0);
                                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
                            }
                        });
                        animator.start();
                    }

                    @Override
                    public void onBackCancelled() {
                        if (rootView == null) return;

                        // Animate back to original full screen position
                        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
                        animator.setDuration(250);
                        animator.setInterpolator(new android.view.animation.OvershootInterpolator(0.8f));

                        final float startScale = rootView.getScaleX();
                        final float startTranslationY = rootView.getTranslationY();

                        animator.addUpdateListener(animation -> {
                            float fraction = animation.getAnimatedFraction();
                            rootView.setScaleX(startScale + (1.0f - startScale) * fraction);
                            rootView.setScaleY(startScale + (1.0f - startScale) * fraction);
                            rootView.setTranslationY(startTranslationY * (1.0f - fraction));
                            
                            // Re-interpolate the corners back to 0
                            currentProgress = currentProgress * (1.0f - fraction);
                            rootView.invalidateOutline();
                        });

                        animator.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                rootView.setClipToOutline(false);
                                rootView.setScaleX(1.0f);
                                rootView.setScaleY(1.0f);
                                rootView.setTranslationY(0f);
                            }
                        });
                        animator.start();
                    }
                }
        );
    }
}
