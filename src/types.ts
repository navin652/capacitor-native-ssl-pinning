export type FileSystemWriteChunkType =
  | BufferSource
  | Blob
  | string
  | {
      type: 'write';
      position?: number;
      data: BufferSource | Blob | string;
    }
  | {
      type: 'seek';
      position: number;
    }
  | {
      type: 'truncate';
      size: number;
    };

/**
 * Enum representing the different directories available for file storage.
 * These directories are used to specify where files should be saved or read from.
 * Each directory has a specific purpose and behavior across different platforms (iOS and Android).
 * The directories include:
 * - Documents: For user-generated content, accessible on Android.
 * - Data: For application files, deleted on uninstall.
 * - Library: For application files, deleted on uninstall.
 * - Cache: For app-specific files that can be re-created easily.
 * - External: For persistent files owned by the application, deleted on uninstall.
 * - ExternalStorage: For primary shared/external storage, not accessible on Android 11+.
 * - ExternalCache: For primary shared/external cache.
 * - LibraryNoCloud: For application files without cloud backup, used in iOS.
 * - Temporary: For temporary files, used in iOS and Android.
 * * @since 1.0.0
 * @enum {string}
 */
export enum Directory {
  /**
   * The Documents directory.
   * On iOS it's the app's documents directory.
   * Use this directory to store user-generated content.
   * On Android it's the Public Documents folder, so it's accessible from other apps.
   * It's not accessible on Android 10 unless the app enables legacy External Storage
   * by adding `android:requestLegacyExternalStorage="true"` in the `application` tag
   * in the `AndroidManifest.xml`.
   * On Android 11 or newer the app can only access the files/folders the app created.
   *
   * @since 1.0.0
   */
  Documents = 'DOCUMENTS',
  /**
   * The Data directory.
   * On iOS it will use the Documents directory.
   * On Android it's the directory holding application files.
   * Files will be deleted when the application is uninstalled.
   *
   * @since 1.0.0
   */
  Data = 'DATA',
  /**
   * The Library directory.
   * On iOS it will use the Library directory.
   * On Android it's the directory holding application files.
   * Files will be deleted when the application is uninstalled.
   *
   * @since 1.1.0
   */
  Library = 'LIBRARY',
  /**
   * The Cache directory.
   * Can be deleted in cases of low memory, so use this directory to write app-specific files.
   * that your app can re-create easily.
   *
   * @since 1.0.0
   */
  Cache = 'CACHE',
  /**
   * The external directory.
   * On iOS it will use the Documents directory.
   * On Android it's the directory on the primary shared/external
   * storage device where the application can place persistent files it owns.
   * These files are internal to the applications, and not typically visible
   * to the user as media.
   * Files will be deleted when the application is uninstalled.
   *
   * @since 1.0.0
   */
  External = 'EXTERNAL',
  /**
   * The external storage directory.
   * On iOS it will use the Documents directory.
   * On Android it's the primary shared/external storage directory.
   * It's not accessible on Android 10 unless the app enables legacy External Storage
   * by adding `android:requestLegacyExternalStorage="true"` in the `application` tag
   * in the `AndroidManifest.xml`.
   * It's not accessible on Android 11 or newer.
   *
   * @since 1.0.0
   */
  ExternalStorage = 'EXTERNAL_STORAGE',
  /**
   * The external cache directory.
   * On iOS it will use the Documents directory.
   * On Android it's the primary shared/external cache.
   *
   * @since 7.1.0
   */
  ExternalCache = 'EXTERNAL_CACHE',
  /**
   * The Library directory without cloud backup. Used in iOS.
   * On Android it's the directory holding application files.
   *
   * @since 7.1.0
   */
  LibraryNoCloud = 'LIBRARY_NO_CLOUD',
  /**
   * A temporary directory for iOS.
   * On Android it's the directory holding the application cache.
   *
   * @since 7.1.0
   */
  Temporary = 'TEMPORARY',
}
