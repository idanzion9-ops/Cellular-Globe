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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Cellular Globe is a single HTML page. Rather than baking it into the APK for good,
 * the shell copies it into internal storage on first run and serves it from there.
 * A background check pulls a newer copy from GitHub, so pushing to main updates the
 * running app without a reinstall. Both copies are served from the same https origin,
 * which is what keeps the user's saved data intact across an update.
 */
public class MainActivity extends Activity {

    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String HOME = ORIGIN + "/app/index.html";
    private static final String UPDATE_URL =
            "https://raw.githubusercontent.com/idanzion9-ops/Cellular-Globe/main/app/src/main/assets/index.html";
    private static final int FILE_PICKER = 1001;
    private static final int MIN_SANE_PAGE_BYTES = 20000;

    private WebView web;
    private ValueCallback<Uri[]> pendingPicker;
    private File pageFile;
    private volatile boolean checking = false;
    private volatile boolean loudPending = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        File webDir = new File(getFilesDir(), "web");
        if (!webDir.exists()) webDir.mkdirs();
        pageFile = new File(webDir, "index.html");
        seedFromAssetsIfNeeded();

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/app/", new WebViewAssetLoader.InternalStoragePathHandler(this, webDir))
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
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url.toString().startsWith(ORIGIN)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "No app can open that link.", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Quiet check on every launch. Only speaks up if something is actually new.
                checkForUpdate(false);
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

        if (state != null) web.restoreState(state);
        else web.loadUrl(HOME);
    }

    /** First run, or a reinstall whose bundled page is newer than the cached one. */
    private void seedFromAssetsIfNeeded() {
        try {
            if (pageFile.exists()) {
                long installed = getPackageManager()
                        .getPackageInfo(getPackageName(), 0).lastUpdateTime;
                if (pageFile.lastModified() >= installed) return;
            }
            InputStream in = getAssets().open("index.html");
            byte[] data = readAll(in);
            in.close();
            writePage(data);
        } catch (Exception e) {
            // If this fails the loader still has /assets/ as a fallback path.
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private void writePage(byte[] data) throws Exception {
        FileOutputStream fos = new FileOutputStream(pageFile);
        fos.write(data);
        fos.close();
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void toPage(final String state, final String detail) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web == null) return;
                String d = detail == null ? "" : detail.replace("\\", "\\\\").replace("'", "\\'");
                web.evaluateJavascript(
                        "window.__update && window.__update('" + state + "','" + d + "')", null);
            }
        });
    }

    /**
     * Pulls the page from GitHub. When loud is true the page reports every outcome;
     * when false it only speaks up if an update is actually waiting.
     */
    private void checkForUpdate(final boolean loud) {
        if (loud) {
            loudPending = true;
            toPage("checking", null);
        }
        // An already-running silent check will now report its result out loud,
        // so a button tap is never swallowed.
        if (checking) return;
        checking = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(UPDATE_URL).openConnection();
                    conn.setConnectTimeout(12000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("Cache-Control", "no-cache");
                    conn.setRequestProperty("User-Agent", "CellularGlobe");
                    int code = conn.getResponseCode();
                    if (code == 404) throw new Exception(
                            "HTTP 404 — GitHub will not serve this file. The repository is "
                          + "probably private, or the branch or path has changed.");
                    if (code != 200) throw new Exception("HTTP " + code);

                    byte[] remote = readAll(conn.getInputStream());
                    // A truncated or error body must never replace a working page.
                    if (remote.length < MIN_SANE_PAGE_BYTES) throw new Exception("the download looked incomplete");

                    byte[] local = new byte[0];
                    if (pageFile.exists()) {
                        FileInputStream fin = new FileInputStream(pageFile);
                        local = readAll(fin);
                        fin.close();
                    }

                    if (sha256(remote).equals(sha256(local))) {
                        if (loudPending) toPage("current", null);
                    } else {
                        writePage(remote);
                        toPage("ready", null);
                    }
                } catch (Exception e) {
                    if (loudPending) toPage("failed", e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                    loudPending = false;
                    checking = false;
                }
            }
        }).start();
    }

    public class Bridge {
        /** Export: writes the file, then hands it to Android's share sheet. No permissions needed. */
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
        public void checkUpdate() {
            checkForUpdate(true);
        }

        @JavascriptInterface
        public void reloadApp() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (web != null) web.loadUrl(HOME);
                }
            });
        }

        @JavascriptInterface
        public String updateSource() {
            return UPDATE_URL;
        }

        @JavascriptInterface
        public boolean isApp() {
            return true;
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != FILE_PICKER || pendingPicker == null) return;
        pendingPicker.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
        pendingPicker = null;
    }

    /** Back closes the topmost layer — dialog, then the spectrum map, then the country list. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
                "(function(){"
                        + "var o=document.getElementById('ovl');"
                        + "if(o&&!o.hidden){o.hidden=true;var m=document.getElementById('modal');"
                        + "if(m)m.innerHTML='';return 'closed';}"
                        + "var w=document.getElementById('chartWin');"
                        + "if(w&&!w.hidden){w.hidden=true;return 'closed';}"
                        + "var c=document.getElementById('cmenu');"
                        + "if(c&&!c.hidden){c.hidden=true;return 'closed';}"
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
