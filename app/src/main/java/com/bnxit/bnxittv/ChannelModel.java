package com.bnxit.bnxittv;

/**
 * Lightweight POJO representing a single TV channel.
 * Matches the JSON format in assets: name, logo, group, url, id, type, kid, key.
 * No getters/setters overhead — direct field access for performance.
 */
public class ChannelModel {

    public String name;
    public String logo;
    public String group;
    public String url;
    public String id;
    public String type;   // "dash", "hls", or null (auto-detect)
    public String kid;    // ClearKey DRM key ID
    public String key;    // ClearKey DRM key
    public String status; // "live" or null

    public ChannelModel() {
    }

    public ChannelModel(String name, String group, String url) {
        this.name = name;
        this.group = group;
        this.url = url;
    }

    /**
     * Returns the first character of the channel name (uppercase) for the initial circle.
     */
    public char getInitial() {
        if (name != null && !name.isEmpty()) {
            // Skip common prefixes like "IN | ", "FB | ", etc.
            String cleaned = name;
            int pipeIndex = cleaned.indexOf('|');
            if (pipeIndex > 0 && pipeIndex < 5) {
                cleaned = cleaned.substring(pipeIndex + 1).trim();
            }
            if (!cleaned.isEmpty()) {
                return Character.toUpperCase(cleaned.charAt(0));
            }
        }
        return '?';
    }

    /**
     * Check if this channel uses DASH format (with possible ClearKey DRM).
     */
    public boolean isDash() {
        if ("dash".equalsIgnoreCase(type)) return true;
        if (url != null && url.endsWith(".mpd")) return true;
        return false;
    }

    /**
     * Check if this channel has ClearKey DRM.
     */
    public boolean hasClearKey() {
        return kid != null && !kid.isEmpty() && key != null && !key.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChannelModel that = (ChannelModel) o;
        if (url != null) return url.equals(that.url);
        return name != null && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return url != null ? url.hashCode() : (name != null ? name.hashCode() : 0);
    }
}
