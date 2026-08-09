package com.shaikhalkar.professorinstaller;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ApiClient {
    interface JsonCallback { void onSuccess(JSONObject json); void onError(String message); }

    static final String BASE_URL = "https://shaikhalkar.com/professor-installer/";
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    void fetchCatalog(String country, JsonCallback cb) {
        String c = country == null || country.isEmpty() ? "JO" : country;
        get(BASE_URL + "api/catalog.php?country=" + enc(c), cb);
    }

    void fetchSupport(String code, JsonCallback cb) {
        get(BASE_URL + "api/support.php?code=" + enc(code), cb);
    }

    private void get(String urlString, JsonCallback cb) {
        executor.execute(() -> {
            HttpURLConnection con = null;
            try {
                URL url = new URL(urlString);
                con = (HttpURLConnection) url.openConnection();
                con.setConnectTimeout(12000);
                con.setReadTimeout(20000);
                con.setInstanceFollowRedirects(true);
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("User-Agent", "ProfessorInstaller/3.0");
                int status = con.getResponseCode();
                InputStream in = status >= 200 && status < 300 ? con.getInputStream() : con.getErrorStream();
                String body = readAll(in);
                JSONObject root = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
                if (status >= 200 && status < 300 && root.optBoolean("ok", true)) {
                    main.post(() -> cb.onSuccess(root));
                } else {
                    String error = root.optString("error", "http_" + status);
                    main.post(() -> cb.onError(error));
                }
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "network_error" : e.getMessage());
                main.post(() -> cb.onError(msg));
            } finally {
                if (con != null) con.disconnect();
            }
        });
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
}
