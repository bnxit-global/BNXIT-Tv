package com.bnxit.bnxittv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads channel data from GitHub remote URLs with local cache fallback.
 *
 * Strategy:
 * 1. First launch: load from local assets (instant startup)
 * 2. Background: fetch latest JSON from GitHub, cache to internal storage
 * 3. Next launch: load from cache (fast), then refresh from GitHub in background
 * 4. If network fails: use cache, if no cache: use assets
 *
 * GitHub URLs auto-update when you push changes to the repo.
 */
public class JsonLoader {

    private static final String TAG = "JsonLoader";

    // GitHub raw URLs for channel data
    private static final String[] REMOTE_URLS = {
            "https://raw.githubusercontent.com/SHAJON-404/iptv/refs/heads/main/app/data/bangla.json",
            "https://raw.githubusercontent.com/SHAJON-404/iptv/refs/heads/main/app/data/sports.json",
            "https://raw.githubusercontent.com/SHAJON-404/iptv/refs/heads/main/app/data/fifa.json",
            "https://raw.githubusercontent.com/SHAJON-404/iptv/refs/heads/main/app/data/channels.json"
    };

    // Cache filenames in internal storage
    private static final String[] CACHE_FILES = {
            "cache_bangla.json",
            "cache_sports.json",
            "cache_fifa.json",
            "cache_channels.json"
    };

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 15000;

    // Cached data
    private final List<ChannelModel> allChannels = new ArrayList<>();
    private final Map<String, List<ChannelModel>> channelsByCategory = new LinkedHashMap<>();
    private final List<String> categories = new ArrayList<>();
    private boolean loaded = false;
    private LinkChecker linkChecker;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface LoadCallback {
        void onLoaded(int channelCount, int categoryCount);
        void onError(String message);
    }

    /**
     * Load channels: cache/assets first (fast), then refresh from GitHub in background.
     * Calls callback on main thread when ready.
     */
    public void loadAsync(Context context, LoadCallback callback) {
        if (linkChecker == null) {
            linkChecker = new LinkChecker(context);
        }
        linkChecker.setListener(brokenUrl -> {
            if (removeBrokenChannel(brokenUrl)) {
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onLoaded(allChannels.size(), categories.size());
                    }
                });
            }
        });

        executor.execute(() -> {
            try {
                // Step 1: Load from cache (instant)
                loadFromCache(context);

                // Notify UI — data is ready for display
                final int chCount = allChannels.size();
                final int catCount = categories.size();
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onLoaded(chCount, catCount);
                    }
                });

                // Step 2: Refresh from GitHub in background
                refreshFromRemote(context, callback);

            } catch (Exception e) {
                Log.e(TAG, "Load error", e);
                mainHandler.post(() -> {
                    if (callback != null) {
                        if (allChannels.isEmpty()) {
                            callback.onError("Failed to load channels");
                        } else {
                            // We have cached data, still usable
                            callback.onLoaded(allChannels.size(), categories.size());
                        }
                    }
                });
            }
        });
    }

    /**
     * Load from local cache files.
     */
    private void loadFromCache(Context context) {
        Set<String> seenUrls = new LinkedHashSet<>();
        Set<String> categorySet = new LinkedHashSet<>();

        allChannels.clear();

        for (int i = 0; i < CACHE_FILES.length; i++) {
            File cacheFile = new File(context.getFilesDir(), CACHE_FILES[i]);
            if (cacheFile.exists() && cacheFile.length() > 0) {
                String json = readFile(cacheFile);
                Log.d(TAG, "Loaded from cache: " + CACHE_FILES[i]);
                if (json != null && !json.isEmpty()) {
                    parseJsonArray(json, seenUrls, categorySet);
                }
            }
        }

        buildCategoryData(categorySet);
        loaded = true;
        Log.d(TAG, "Initial load: " + allChannels.size() + " channels, " + categories.size() + " categories");

        if (linkChecker != null) {
            for (ChannelModel ch : allChannels) {
                linkChecker.queueCheck(ch.url);
            }
        }
    }

    /**
     * Fetch latest data from GitHub and update cache + in-memory data.
     */
    private void refreshFromRemote(Context context, LoadCallback callback) {
        boolean anyUpdated = false;

        Set<String> seenUrls = new LinkedHashSet<>();
        Set<String> categorySet = new LinkedHashSet<>();
        List<ChannelModel> freshChannels = new ArrayList<>();

        for (int i = 0; i < REMOTE_URLS.length; i++) {
            try {
                String json = downloadUrl(REMOTE_URLS[i]);

                if (json != null && !json.isEmpty() && json.trim().startsWith("[")) {
                    // Save to cache
                    saveToCache(context, CACHE_FILES[i], json);
                    anyUpdated = true;
                    Log.d(TAG, "Updated from remote: " + REMOTE_URLS[i]);
                } else {
                    // Use cached version for this file
                    File cacheFile = new File(context.getFilesDir(), CACHE_FILES[i]);
                    if (cacheFile.exists()) {
                        json = readFile(cacheFile);
                    }
                }

                if (json != null && !json.isEmpty()) {
                    parseJsonArrayInto(json, seenUrls, categorySet, freshChannels);
                }

            } catch (Exception e) {
                Log.w(TAG, "Remote fetch failed for " + REMOTE_URLS[i] + ": " + e.getMessage());

                // Use existing cached data for this file
                try {
                    File cacheFile = new File(context.getFilesDir(), CACHE_FILES[i]);
                    if (cacheFile.exists()) {
                        String json = readFile(cacheFile);
                        if (json != null && !json.isEmpty()) {
                            parseJsonArrayInto(json, seenUrls, categorySet, freshChannels);
                        }
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Fallback also failed", ex);
                }
            }
        }

        // If we got fresh data, replace in-memory data
        if (anyUpdated && !freshChannels.isEmpty()) {
            synchronized (this) {
                allChannels.clear();
                allChannels.addAll(freshChannels);
                buildCategoryData(categorySet);
            }

            Log.d(TAG, "Refreshed: " + allChannels.size() + " channels, " + categories.size() + " categories");

            if (linkChecker != null) {
                for (ChannelModel ch : freshChannels) {
                    linkChecker.queueCheck(ch.url);
                }
            }

            // Notify UI to refresh
            final int chCount = allChannels.size();
            final int catCount = categories.size();
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onLoaded(chCount, catCount);
                }
            });
        }
    }

    // ---- Parsing ----
 
    private String capitalizeCategory(String cat) {
        if (cat == null || cat.isEmpty()) return "";
        String s = cat.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return "";
        
        StringBuilder sb = new StringBuilder();
        boolean nextTitleCase = true;
        for (char c : s.toCharArray()) {
            if (Character.isSpaceChar(c)) {
                nextTitleCase = true;
                sb.append(c);
            } else if (nextTitleCase) {
                sb.append(Character.toUpperCase(c));
                nextTitleCase = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private void parseJsonArray(String json, Set<String> seenUrls, Set<String> categorySet) {
        parseJsonArrayInto(json, seenUrls, categorySet, allChannels);
    }

    private void parseJsonArrayInto(String json, Set<String> seenUrls, Set<String> categorySet, List<ChannelModel> target) {
        try {
            JSONArray array = new JSONArray(json);
            int len = array.length();

            for (int i = 0; i < len; i++) {
                JSONObject obj = array.getJSONObject(i);

                String url = obj.optString("url", "");
                if (url.isEmpty()) continue;

                // Deduplicate by URL
                if (seenUrls.contains(url)) continue;
                seenUrls.add(url);

                // Skip if cached as broken (to save CPU/RAM on H713 / 1GB RAM)
                if (linkChecker != null && linkChecker.isLinkCachedAsBroken(url)) {
                    continue;
                }

                ChannelModel channel = new ChannelModel();
                channel.name = obj.optString("name", "Unknown");
                channel.logo = obj.optString("logo", "");
                channel.group = obj.optString("group", "Other");
                channel.url = url;
                channel.id = obj.optString("id", "");
                channel.type = obj.optString("type", null);
                channel.kid = obj.optString("kid", null);
                channel.key = obj.optString("key", null);
                channel.status = obj.optString("status", null);

                if (channel.group == null || channel.group.isEmpty()) {
                    channel.group = "Other";
                }

                target.add(channel);
                
                // Split categories by semicolon
                String[] split = channel.group.split(";");
                for (String g : split) {
                    String trimmed = g.trim();
                    if (!trimmed.isEmpty()) {
                        categorySet.add(capitalizeCategory(trimmed));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "JSON parse error", e);
        }
    }

    private void buildCategoryData(Set<String> categorySet) {
        categories.clear();
        categories.add("All");

        // Sort priority categories on top (FIFA, World Cup, Cricket, etc.)
        List<String> priorityCats = new ArrayList<>();
        List<String> priorityKeywords = new ArrayList<>();
        priorityKeywords.add("fifa");
        priorityKeywords.add("world cup");
        priorityKeywords.add("cricket cup");
        priorityKeywords.add("cricket");
        priorityKeywords.add("sports");
        priorityKeywords.add("football");

        List<String> remainingCats = new ArrayList<>(categorySet);

        // Find categories that match priority keywords (order by priorityKeywords list)
        for (String keyword : priorityKeywords) {
            List<String> matchesForKeyword = new ArrayList<>();
            for (String cat : remainingCats) {
                if (cat.toLowerCase().contains(keyword)) {
                    matchesForKeyword.add(cat);
                }
            }
            // Sort matches for this keyword alphabetically, then add to priority
            java.util.Collections.sort(matchesForKeyword);
            for (String match : matchesForKeyword) {
                if (!priorityCats.contains(match)) {
                    priorityCats.add(match);
                }
            }
        }

        // Remove priority categories from the remaining list
        remainingCats.removeAll(priorityCats);

        // Sort the remaining categories alphabetically
        java.util.Collections.sort(remainingCats);

        // Assemble categories list
        categories.addAll(priorityCats);
        categories.addAll(remainingCats);

        channelsByCategory.clear();
        channelsByCategory.put("All", new ArrayList<>(allChannels));
        
        // Initialize lists for all categories
        for (String cat : categorySet) {
            channelsByCategory.put(cat, new ArrayList<>());
        }

        // Map channels to all their respective split categories
        for (ChannelModel ch : allChannels) {
            if (ch.group != null) {
                String[] split = ch.group.split(";");
                for (String g : split) {
                    String trimmed = g.trim();
                    if (!trimmed.isEmpty()) {
                        String normalized = capitalizeCategory(trimmed);
                        List<ChannelModel> catList = channelsByCategory.get(normalized);
                        if (catList != null && !catList.contains(ch)) {
                            catList.add(ch);
                        }
                    }
                }
            }
        }
    }

    // ---- Network ----

    private String downloadUrl(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "BNXIT-TV/1.0");
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                Log.w(TAG, "HTTP " + responseCode + " for " + urlStr);
                return null;
            }

            InputStream is = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"), 8192);
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
            reader.close();
            is.close();

            return sb.toString();

        } catch (Exception e) {
            Log.w(TAG, "Download failed: " + urlStr + " - " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ---- File I/O ----

    private void saveToCache(Context context, String fileName, String data) {
        try {
            File file = new File(context.getFilesDir(), fileName);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(data.getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "Cache save error: " + fileName, e);
        }
    }

    private String readFile(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"), 8192);
            StringBuilder sb = new StringBuilder((int) file.length());
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
            reader.close();
            fis.close();
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "File read error: " + file.getName(), e);
            return null;
        }
    }



    // ---- Accessors (thread-safe reads) ----

    public synchronized List<String> getCategories() {
        return new ArrayList<>(categories);
    }

    public synchronized List<ChannelModel> getChannelsForCategory(String category) {
        if (category == null || "All".equals(category)) {
            return new ArrayList<>(allChannels);
        }
        List<ChannelModel> list = channelsByCategory.get(category);
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    public synchronized List<ChannelModel> getAllChannels() {
        return new ArrayList<>(allChannels);
    }

    public synchronized int getTotalCount() {
        return allChannels.size();
    }

    public synchronized ChannelModel findByUrl(String url) {
        if (url == null) return null;
        for (ChannelModel ch : allChannels) {
            if (url.equals(ch.url)) {
                return ch;
            }
        }
        return null;
    }

    public synchronized boolean removeBrokenChannel(String url) {
        ChannelModel found = null;
        for (ChannelModel ch : allChannels) {
            if (url.equals(ch.url)) {
                found = ch;
                break;
            }
        }
        if (found == null) return false;

        allChannels.remove(found);
        for (Map.Entry<String, List<ChannelModel>> entry : channelsByCategory.entrySet()) {
            entry.getValue().remove(found);
        }
        return true;
    }

    /**
     * Shutdown executor. Call in Activity onDestroy.
     */
    public void shutdown() {
        try {
            executor.shutdownNow();
        } catch (Exception e) {
            // ignore
        }
        if (linkChecker != null) {
            linkChecker.shutdown();
        }
    }
}
