package com.chaiapps.chasima;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** חיבור פשוט למסד הנתונים של Firebase דרך האינטרנט (בלי ספריות). */
final class Fb {
    private Fb() {}

    interface Listener {
        void onEvent(String event, String data);
    }

    static String base() {
        String b = BuildConfig.DB_URL;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    static String get(String path) throws IOException {
        return call("GET", path, null);
    }

    static void put(String path, String json) throws IOException {
        call("PUT", path, json);
    }

    static void patch(String path, String json) throws IOException {
        call("PATCH", path, json);
    }

    static void delete(String path) throws IOException {
        call("DELETE", path, null);
    }

    /** הוספת פריט חדש לרשימה (מפתח אוטומטי לפי זמן). */
    static String post(String path, String json) throws IOException {
        String r = call("POST", path, json);
        try {
            return new org.json.JSONObject(r).optString("name", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** N הפריטים האחרונים ברשימה. */
    static String last(String path, int n) throws IOException {
        return callUrl("GET", url(path) + lastQ(n), null);
    }

    static String lastQ(int n) {
        return "?orderBy=%22%24key%22&limitToLast=" + n;
    }

    /** בקשה כללית. הנתיב יכול לכלול שאילתה (?orderBy=...). */
    static String raw(String method, String path, String body) throws IOException {
        int q = path.indexOf('?');
        String full = q < 0 ? url(path) : url(path.substring(0, q)) + path.substring(q);
        return callUrl(method, full, body);
    }

    static String url(String path) {
        return base() + "/" + path + ".json";
    }

    private static String call(String method, String path, String body) throws IOException {
        return callUrl(method, url(path), body);
    }

    private static String callUrl(String method, String fullUrl, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(fullUrl).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        if (method.equals("PATCH")) {
            c.setRequestMethod("POST");
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        } else {
            c.setRequestMethod(method);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String res = in == null ? "" : read(in);
        c.disconnect();
        if (code >= 400) throw new IOException("HTTP " + code + " " + res);
        return res;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    /** האזנה חיה לשינויים בנתיב. חוזר רק כשהחיבור נקטע או כשביקשו לעצור. */
    static void stream(String path, Listener l, AtomicBoolean stop) throws IOException {
        streamUrl(url(path), l, stop);
    }

    static void streamUrl(String fullUrl, Listener l, AtomicBoolean stop) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(fullUrl).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(75000); // השרת שולח keep-alive כל 30 שניות
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "text/event-stream");
        int code = c.getResponseCode();
        if (code >= 400) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line, event = null;
            StringBuilder data = new StringBuilder();
            while (!stop.get() && (line = r.readLine()) != null) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                } else if (line.isEmpty()) {
                    if (event != null) l.onEvent(event, data.toString());
                    event = null;
                    data.setLength(0);
                }
            }
        } finally {
            c.disconnect();
        }
    }
}
