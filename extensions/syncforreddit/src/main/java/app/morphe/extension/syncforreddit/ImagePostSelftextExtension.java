package app.morphe.extension.syncforreddit;

import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import android.annotation.SuppressLint;

@SuppressLint("ResourceType")
public class ImagePostSelftextExtension {
    public static void applyLongClickListener(final TextView textView) {
        final Context context = textView.getContext();
        final android.view.GestureDetector gestureDetector = new android.view.GestureDetector(context,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(android.view.MotionEvent e) {
                        try {
                            int x = (int) e.getX();
                            int y = (int) e.getY();
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
                                        
                                        Object[] nbASpans = null;
                                        try {
                                            nbASpans = spanned.getSpans(off, off, Class.forName("nb.a"));
                                        } catch (Exception ignored) {}
                                        
                                        Object[] clickableSpans = spanned.getSpans(off, off, android.text.style.ClickableSpan.class);
                                        
                                        if ((nbASpans != null && nbASpans.length > 0) || (clickableSpans != null && clickableSpans.length > 0)) {
                                            // A link was tapped, HtmlTextView handles this natively
                                            return true;
                                        }
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }

                        // Tapped on non-link text. Trigger click on a parent to open the post.
                        View parent = (View) textView.getParent();
                        while (parent != null) {
                            if (parent.isClickable() || parent.hasOnClickListeners()) {
                                parent.performClick();
                                break;
                            }
                            if (parent.getParent() instanceof View) {
                                parent = (View) parent.getParent();
                            } else {
                                break;
                            }
                        }
                        return true;
                    }

                    @Override
                    public void onLongPress(android.view.MotionEvent e) {
                        try {
                            int x = (int) e.getX();
                            int y = (int) e.getY();
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
                                        
                                        Object[] nbASpans = null;
                                        try {
                                            nbASpans = spanned.getSpans(off, off, Class.forName("nb.a"));
                                        } catch (Exception ignored) {}
                                        
                                        Object[] clickableSpans = spanned.getSpans(off, off, android.text.style.ClickableSpan.class);
                                        
                                        if ((nbASpans != null && nbASpans.length > 0) || (clickableSpans != null && clickableSpans.length > 0)) {
                                            // A link was long-pressed, let the TextView handle it natively
                                            return;
                                        }
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }

                        String text = textView.getText().toString();
                        if (text.isEmpty())
                            return;

                        View dialogView = View.inflate(context, 0x7f0c0061, null);
                        final TextView dialogTextView = dialogView.findViewById(0x7f090466);
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
                                            ClipboardManager clipboard = (ClipboardManager) context
                                                    .getSystemService(Context.CLIPBOARD_SERVICE);
                                            ClipData clip = ClipData.newPlainText("text", selected);
                                            clipboard.setPrimaryClip(clip);
                                            Toast.makeText(context, "Text copied: " + selected, Toast.LENGTH_SHORT)
                                                    .show();
                                        } else {
                                            Toast.makeText(context, "No text copied", Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                })
                                .show();
                    }
                });

        textView.setClickable(true);
        textView.setLongClickable(true);
        textView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return true;
            }
        });

        textView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                gestureDetector.onTouchEvent(event);
                v.onTouchEvent(event);
                return true; // forcefully consume touch so parent card doesn't get it
            }
        });
    }
}
