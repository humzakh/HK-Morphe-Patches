package app.morphe.extension.syncforreddit;

import android.view.View;

/**
 * Clickable span for inline images: opens the image viewer with the caption as its title.
 * Long-press behavior is delegated to a real {@link mb.d} so it matches plain links.
 *
 * @noinspection unused
 */
public class InlineImageClickableSpan implements nb.a {
    private final String url;
    private final String caption;
    private final mb.d delegate;

    public InlineImageClickableSpan(String url, String caption) {
        this.url = url;
        this.caption = caption;
        this.delegate = new mb.d(url);
    }

    @Override
    public void onClick(View view) {
        InlineImageCaptionExtension.openImageWithCaption(view.getContext(), url, caption);
    }

    @Override
    public void onLongClick(View view) {
        delegate.onLongClick(view);
    }
}
