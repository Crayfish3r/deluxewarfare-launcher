package com.makar.launcher;

import java.net.URI;

@FunctionalInterface
interface MirrorDownloadResolver {
    URI resolveDownloadUri(String publicUrl, String filePath);
}
