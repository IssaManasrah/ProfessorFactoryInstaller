package com.shaikhalkar.professorinstaller;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ShakoMainActivity extends Activity {
    private static final String BASE = "https://shaikhalkar.com/shako-api/api/";
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(245, 247, 251));
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new ShakoBridge(), "ShakoApi");
        setContentView(webView);

        webView.loadUrl("file:///android_asset/shako/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    public class ShakoBridge {
        @JavascriptInterface
        public String health() {
            return get("health.php");
        }

        @JavascriptInterface
        public String home() {
            return get("home.php");
        }

        @JavascriptInterface
        public String categories() {
            return get("categories.php");
        }

        @JavascriptInterface
        public String products(int page, int limit, String q, int categoryId) {
            StringBuilder path = new StringBuilder("products.php?page=")
                    .append(Math.max(1, page))
                    .append("&limit=")
                    .append(Math.max(1, Math.min(50, limit)));
            try {
                if (q != null && !q.trim().isEmpty()) {
                    path.append("&q=").append(Uri.encode(q.trim()));
                }
            } catch (Throwable ignored) {}
            if (categoryId > 0) {
                path.append("&category_id=").append(categoryId);
            }
            return get(path.toString());
        }

        @JavascriptInterface
        public String product(int id) {
            return get("product.php?id=" + id);
        }

        @JavascriptInterface
        public void openUrl(String url) {
            if (url == null || url.trim().isEmpty()) return;
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } catch (Throwable ignored) {}
        }

        private String get(String path) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(BASE + path);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "SHAKO-Android/0.1");

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 400
                        ? connection.getInputStream()
                        : connection.getErrorStream();
                if (stream == null) {
                    return error("empty_response", code);
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) out.append(line);
                reader.close();

                if (out.length() == 0) return error("empty_response", code);
                return out.toString();
            } catch (Throwable e) {
                return error(e.getClass().getSimpleName(), -1);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        private String error(String message, int code) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("ok", false);
                obj.put("error", message);
                obj.put("http_code", code);
                return obj.toString();
            } catch (Throwable ignored) {
                return "{\"ok\":false,\"error\":\"network_error\"}";
            }
        }
    }
}
