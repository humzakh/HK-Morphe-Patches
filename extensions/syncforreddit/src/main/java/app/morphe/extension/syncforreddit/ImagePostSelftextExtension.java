package app.morphe.extension.syncforreddit;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.WeakHashMap;

@SuppressLint("ResourceType")
public class ImagePostSelftextExtension {
    private static final WeakHashMap<TextView, String> rawMarkdownMap = new WeakHashMap<>();

    public static boolean isExpandedView(TextView textView) {
        return textView.getMaxLines() == Integer.MAX_VALUE;
    }

    public static nc.a getOptions(TextView textView, xa.d post) {
        String rawMarkdown = post.P0();
        if (rawMarkdown != null) {
            rawMarkdownMap.put(textView, rawMarkdown);
        }

        if (isExpandedView(textView)) {
            // Expanded view: apply long click listeners and render markdown
            boolean isEnabled = true;
            try {
                isEnabled = com.laurencedawson.reddit_sync.singleton.SettingsSingleton.x().postsLongPress;
            } catch (Exception ignored) {}
            
            if (isEnabled) {
                applyLongClickListener(textView);
                textView.setLongClickable(true);
            } else {
                textView.setOnLongClickListener(null);
                textView.setOnTouchListener(null);
                textView.setLongClickable(false);
            }
            return nc.a.b();
        } else {
            // Feed view: completely remove listeners so we don't intercept touches
            textView.setOnLongClickListener(null);
            textView.setOnTouchListener(null);
            textView.setLongClickable(false);
            textView.setClickable(false);
            return nc.a.c();
        }
    }

    private static void applyLongClickListener(final TextView textView) {
        final Context context = textView.getContext();
        final float[] lastTouch = new float[2];

        textView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    lastTouch[0] = event.getX();
                    lastTouch[1] = event.getY();
                }
                // Always return false so we don't interfere with native clicking/scrolling/links
                return false;
            }
        });

        textView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                // Only apply in expanded view
                if (!isExpandedView(textView)) {
                    return false;
                }

                try {
                    int x = (int) lastTouch[0];
                    int y = (int) lastTouch[1];
                    x -= textView.getTotalPaddingLeft();
                    y -= textView.getTotalPaddingTop();
                    x += textView.getScrollX();
                    y += textView.getScrollY();

                    android.text.Layout layout = textView.getLayout();
                    if (layout != null) {
                        int line = layout.getLineForVertical(y);
                        int off = layout.getOffsetForHorizontal(line, x);
                        float lineWidth = layout.getLineWidth(line);

                        if (x <= lineWidth) {
                            CharSequence textSeq = textView.getText();
                            if (textSeq instanceof android.text.Spanned) {
                                android.text.Spanned spanned = (android.text.Spanned) textSeq;

                                Object[] clickableSpans = spanned.getSpans(off, off, android.text.style.ClickableSpan.class);
                                if (clickableSpans != null && clickableSpans.length > 0) {
                                    return false; // User long-pressed a link, let native link handler take it
                                }

                                try {
                                    Object[] nbASpans = spanned.getSpans(off, off, Class.forName("nb.a"));
                                    if (nbASpans != null && nbASpans.length > 0) {
                                        return false; // Native link
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }

                return showSelectTextDialog(context, textView);
            }
        });
    }

    private static boolean showSelectTextDialog(final Context context, TextView textView) {
        View dialogView = View.inflate(context, 0x7f0c0061, null);
        final TextView dialogTextView = dialogView.findViewById(0x7f090466);
        String text = rawMarkdownMap.containsKey(textView) ? rawMarkdownMap.get(textView) : textView.getText().toString();
        if (text.isEmpty()) {
            return false;
        }
        dialogTextView.setText(text);

        new AlertDialog.Builder(context)
            .setTitle("Select text...")
            .setView(dialogView)
            .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    int start = dialogTextView.getSelectionStart();
                    int end = dialogTextView.getSelectionEnd();
                    if (start != end) {
                        String selected = dialogTextView.getText().toString().substring(start, end);
                        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("text", selected);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(context, "Text copied: " + selected, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, "No text copied", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .show();

        return true;
    }
}
