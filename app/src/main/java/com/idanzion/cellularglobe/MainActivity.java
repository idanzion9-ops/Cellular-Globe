package com.idanzion.cellularglobe;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Cellular Globe is a single HTML page living in assets/. It is served over a real
 * https origin by WebViewAssetLoader rather than from file://, which is what lets
 * localStorage persist reliably and lets the page reach api.anthropic.com.
 */
public class MainActivity extends Activity {

    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String HOME = ORIGIN + "/assets/index.html";
    private static final int FILE_PICKER = 1001;

    private WebView web;
    private ValueCallback<Uri[]> pendingPicker;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url.toString().startsWith(ORIGIN)) return false;
                // Reference links (spectrummonitoring and friends) open in the real browser.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "No app can open that link.", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingPicker != null) pendingPicker.onReceiveValue(null);
                pendingPicker = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_PICKER);
                    return true;
                } catch (Exception e) {
                    pendingPicker = null;
                    Toast.makeText(MainActivity.this, "No file picker available.", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        web.addJavascriptInterface(new Bridge(), "Bridge");

        if (state != null) {
            web.restoreState(state);
        } else {
            web.loadUrl(HOME);
        }
    }

    /** Export writes a file the user can send anywhere, without asking for storage permission. */
    public class Bridge {
        @JavascriptInterface
        public void saveText(final String name, final String content) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        File dir = new File(getExternalFilesDir(null), "exports");
                        if (!dir.exists() && !dir.mkdirs()) throw new Exception("could not create the exports folder");
                        File file = new File(dir, name);
                        OutputStreamWriter writer =
                                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
                        writer.write(content);
                        writer.close();

                        Uri uri = FileProvider.getUriForFile(
                                MainActivity.this, getPackageName() + ".fileprovider", file);
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType("application/json");
                        send.putExtra(Intent.EXTRA_STREAM, uri);
                        send.putExtra(Intent.EXTRA_SUBJECT, name);
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(send, "Save or send " + name));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this,
                                "Could not write the file: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean isApp() {
            return true;
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != FILE_PICKER) return;
        if (pendingPicker == null) return;
        pendingPicker.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
        pendingPicker = null;
    }

    /** Back closes an open dialog first, and only then leaves the app. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
                "(function(){var o=document.getElementById('ovl');"
                        + "if(o&&!o.hidden){o.hidden=true;"
                        + "var m=document.getElementById('modal');if(m)m.innerHTML='';return 'closed';}"
                        + "return 'exit';})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (value == null || value.contains("exit")) finish();
                    }
                });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("Bridge");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
