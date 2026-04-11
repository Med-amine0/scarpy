package com.vaults.app;

import android.content.Context;
import android.content.Intent;
import android.webkit.JavascriptInterface;

public class WebViewBridge {
    private final Context context;

    public WebViewBridge(Context context) {
        this.context = context;
    }

    @JavascriptInterface
    public void openGallery(long galleryId) {
        Intent intent = new Intent(context, WebViewGalleryActivity.class);
        intent.putExtra("gallery_id", galleryId);
        context.startActivity(intent);
    }
}