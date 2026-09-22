package com.makar.launcher;

import java.util.Objects;

public record LauncherUpdateCandidate(
        LauncherUpdateInfo updateInfo,
        LauncherUpdateSource source
) {
    public LauncherUpdateCandidate {
        Objects.requireNonNull(updateInfo, "updateInfo");
        Objects.requireNonNull(source, "source");
    }
}
