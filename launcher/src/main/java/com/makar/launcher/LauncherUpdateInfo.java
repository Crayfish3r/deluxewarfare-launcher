package com.makar.launcher;

public final class LauncherUpdateInfo {
    private String version = "";
    private String url = "";
    private String sha256 = "";
    private long size;
    private boolean mandatory;
    private String notes = "";

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version == null ? "" : version.trim();
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url.trim();
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256 == null ? "" : sha256.trim();
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = Math.max(0, size);
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public void setMandatory(boolean mandatory) {
        this.mandatory = mandatory;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes == null ? "" : notes.trim();
    }
}
