package com.bnxit.bnxittv;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Highly optimized, low-overhead link checker for checking IPTV stream links.
 * Tailored for low-end hardware (1GB RAM, H713 CPU):
 * - Sequential validation (exactly one background thread, no thread spikes).
 * - Throttled checks (300ms sleep delay between requests).
 * - Persistent caching (checked link status stored for 3 days to eliminate redundant requests).
 * - Fail-fast timeouts (2-second connection/read limits).
 */
public class LinkChecker {
    private static final String TAG = "LinkChecker";
    private static final String CACHE_FILE_NAME = "checked_links.json";
    private static final long CACHE_EXPIRY_MS = 3 * 24 * 60 * 60 * 1000L; // 3 days
    private static final int TIMEOUT_MS = 2000; // 2 seconds

    private final Context context;
    private final Map<String, LinkStatus> statusCache = new HashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean cacheLoaded = false;

    public interface OnLinkStatusChangedListener {
        void onLinkBroken(String url);
    }

    private OnLinkStatusChangedListener listener;

    public static class LinkStatus {
        public boolean isBroken;
        public long lastChecked;

        public LinkStatus(boolean isBroken, long lastChecked) {
            this.isBroken = isBroken;
            this.lastChecked = lastChecked;
        }
    }

    public LinkChecker(Context context) {
        this.context = context.getApplicationContext();
        loadCache();
    }

    public void setListener(OnLinkStatusChangedListener listener) {
        this.listener = listener;
    }

    /**
     * Checks if a URL is currently cached as broken and still valid (within 3 days).
     */
    public synchronized boolean isLinkCachedAsBroken(String url) {
        LinkStatus status = statusCache.get(url);
        if (status != null && status.isBroken) {
            if (System.currentTimeMillis() - status.lastChecked < CACHE_EXPIRY_MS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Queues a URL for asynchronous verification if it needs checking.
     */
    public void queueCheck(String url) {
        executor.execute(() -> {
            if (shouldCheck(url)) {
                // Add throttle delay to keep CPU usage low on H713 processor
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    // ignore
                }
                checkLink(url);
            }
        });
    }

    private synchronized boolean shouldCheck(String url) {
        LinkStatus status = statusCache.get(url);
        if (status == null) return true;
        return System.currentTimeMillis() - status.lastChecked > CACHE_EXPIRY_MS;
    }

    private void checkLink(String urlStr) {
        HttpURLConnection conn = null;
        boolean isBroken = false;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "BNXIT-TV/1.0");

            int responseCode = conn.getResponseCode();
            // Accept any successful response or redirects (e.g. token authorizations)
            if (responseCode >= 200 && responseCode < 400) {
                isBroken = false;
            } else {
                isBroken = true;
                Log.d(TAG, "Link checked as broken (HTTP " + responseCode + "): " + urlStr);
            }
        } catch (Exception e) {
            isBroken = true;
            Log.d(TAG, "Link checked as broken (Exception: " + e.getMessage() + "): " + urlStr);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception e) {
                    // ignore
                }
            }
        }

        updateStatus(urlStr, isBroken);
    }

    private void updateStatus(String url, boolean isBroken) {
        boolean statusChanged = false;
        synchronized (this) {
            LinkStatus oldStatus = statusCache.get(url);
            if (oldStatus == null || oldStatus.isBroken != isBroken) {
                statusChanged = isBroken;
            }
            statusCache.put(url, new LinkStatus(isBroken, System.currentTimeMillis()));
        }

        saveCache();

        if (statusChanged && listener != null) {
            listener.onLinkBroken(url);
        }
    }

    private void loadCache() {
        if (cacheLoaded) return;
        File file = new File(context.getFilesDir(), CACHE_FILE_NAME);
        if (!file.exists()) {
            cacheLoaded = true;
            return;
        }

        try {
            FileInputStream fis = new FileInputStream(file);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            fis.close();

            JSONObject json = new JSONObject(sb.toString());
            Iterator<String> keys = json.keys();
            synchronized (this) {
                while (keys.hasNext()) {
                    String url = keys.next();
                    JSONObject obj = json.getJSONObject(url);
                    boolean isBroken = obj.optBoolean("isBroken", false);
                    long lastChecked = obj.optLong("lastChecked", 0);
                    statusCache.put(url, new LinkStatus(isBroken, lastChecked));
                }
            }
            Log.d(TAG, "Loaded link checker cache: " + statusCache.size() + " items");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load link cache", e);
        }
        cacheLoaded = true;
    }

    private synchronized void saveCache() {
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, LinkStatus> entry : statusCache.entrySet()) {
                JSONObject obj = new JSONObject();
                obj.put("isBroken", entry.getValue().isBroken);
                obj.put("lastChecked", entry.getValue().lastChecked);
                json.put(entry.getKey(), obj);
            }

            File file = new File(context.getFilesDir(), CACHE_FILE_NAME);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(json.toString().getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save link cache", e);
        }
    }

    public void shutdown() {
        try {
            executor.shutdownNow();
        } catch (Exception e) {
            // ignore
        }
    }
}
