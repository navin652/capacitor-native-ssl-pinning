// TempFileManager.java
package com.cap.nativehttp.utils;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class TempFileManager {
    private static final Set<File> tempFiles = Collections.synchronizedSet(new HashSet<>());

    public static void registerTempFile(File file) {
        if (file != null && file.exists()) {
            tempFiles.add(file);
        }
    }

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
