package com.makar.launcher;

import java.util.ArrayList;
import java.util.List;

public final class LauncherManifest {
    private String minecraftVersion = "";
    private String forgeVersion = "";
    private String modpackVersion = "";
    private int requiredJava;
    private List<ManifestFileEntry> files = new ArrayList<>();
    private List<ManifestFileEntry> optionalAllowedFiles = new ArrayList<>();
    private List<ManifestDirectoryEntry> optionalAllowedDirectories = new ArrayList<>();
    private boolean deleteUnknownMods;
    private List<String> allowedMods = new ArrayList<>();

    public String getMinecraftVersion() {
        return minecraftVersion;
    }

    public void setMinecraftVersion(String minecraftVersion) {
        this.minecraftVersion = minecraftVersion == null ? "" : minecraftVersion;
    }

    public String getForgeVersion() {
        return forgeVersion;
    }

    public void setForgeVersion(String forgeVersion) {
        this.forgeVersion = forgeVersion == null ? "" : forgeVersion;
    }

    public String getModpackVersion() {
        return modpackVersion;
    }

    public void setModpackVersion(String modpackVersion) {
        this.modpackVersion = modpackVersion == null ? "" : modpackVersion;
    }

    public int getRequiredJava() {
        return requiredJava;
    }

    public void setRequiredJava(int requiredJava) {
        this.requiredJava = requiredJava;
    }

    public List<ManifestFileEntry> getFiles() {
        return files;
    }

    public void setFiles(List<ManifestFileEntry> files) {
        this.files = files == null ? new ArrayList<>() : files;
    }

    public List<ManifestFileEntry> getOptionalAllowedFiles() {
        return optionalAllowedFiles;
    }

    public void setOptionalAllowedFiles(List<ManifestFileEntry> optionalAllowedFiles) {
        this.optionalAllowedFiles = optionalAllowedFiles == null ? new ArrayList<>() : optionalAllowedFiles;
    }

    public List<ManifestDirectoryEntry> getOptionalAllowedDirectories() {
        return optionalAllowedDirectories;
    }

    public void setOptionalAllowedDirectories(List<ManifestDirectoryEntry> optionalAllowedDirectories) {
        this.optionalAllowedDirectories = optionalAllowedDirectories == null ? new ArrayList<>() : optionalAllowedDirectories;
    }

    public boolean isDeleteUnknownMods() {
        return deleteUnknownMods;
    }

    public void setDeleteUnknownMods(boolean deleteUnknownMods) {
        this.deleteUnknownMods = deleteUnknownMods;
    }

    public List<String> getAllowedMods() {
        return allowedMods;
    }

    public void setAllowedMods(List<String> allowedMods) {
        this.allowedMods = allowedMods == null ? new ArrayList<>() : allowedMods;
    }
}
