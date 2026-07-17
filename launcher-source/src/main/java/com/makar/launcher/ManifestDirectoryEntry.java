package com.makar.launcher;

public final class ManifestDirectoryEntry {
    private String path = "";
    private String reason = "";

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path == null ? "" : path;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason == null ? "" : reason;
    }
}
