// TempFileManager.java
package com.cap.nativehttp.utils;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Tracks temp files created for native file uploads (see {@link OkHttpUtils#getTempFile}) so they
 * can be deleted once no longer needed, instead of accumulating in the app's cache directory. iOS
 * doesn't need an equivalent: it can read {@code file://} URIs/paths directly without copying them
 * to a temp location first.
 */
public class TempFileManager {
    private static final Set<File> tempFiles = Collections.synchronizedSet(new HashSet<>());

    /**
     * Registers a temp file for later cleanup. No-op if the file is {@code null} or doesn't exist.
     *
     * @param file the temp file to track
     */
    public static void registerTempFile(File file) {
        if (file != null && file.exists()) {
            tempFiles.add(file);
        }
    }

    /**
     * Deletes every registered temp file and clears the tracked set. Called after each
     * {@code fetch()} request completes (success or failure) and when the plugin is destroyed, so
     * temp files never outlive the request that created them. Falls back to
     * {@link File#deleteOnExit()} for any file that can't be deleted immediately.
     */
    public static void cleanup() {
        for (File file : tempFiles) {
            if (file.exists()) {
                boolean deleted = file.delete();
                if (!deleted) {
                    file.deleteOnExit(); // fallback
                }
            }
        }
        tempFiles.clear();
    }
}
