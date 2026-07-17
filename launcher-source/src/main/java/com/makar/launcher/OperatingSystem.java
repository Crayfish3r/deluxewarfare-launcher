package com.makar.launcher;

import java.util.Locale;

public enum OperatingSystem {
    WINDOWS("windows"),
    LINUX("linux"),
    MACOS("osx");

    private final String launcherName;

    OperatingSystem(String launcherName) {
        this.launcherName = launcherName;
    }

    public String getLauncherName() {
        return launcherName;
    }

    public static OperatingSystem current() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return MACOS;
        }
        return LINUX;
    }

    public static String currentArchitectureBits() {
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return architecture.contains("64") || architecture.contains("aarch64") ? "64" : "32";
    }
}
