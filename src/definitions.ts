import { Plugin } from '@capacitor/core';
import { Directory } from './types';
export namespace NativeSSLPinning {
  export interface Cookies {
    [cookieName: string]: string;
  }
  export interface Header {
    [headerName: string]: string;
  }
  export interface Options {
    body?: string | object;
    responseType?: 'text' | 'base64' | 'blob' | 'file';
    credentials?: string;
    headers?: Header;
    method?: 'DELETE' | 'GET' | 'POST' | 'PUT';
    pkPinning?: boolean;
    sslPinning: {
      certs: string[];
      /**
       * Where each entry in `certs` is sourced from:
       * - `'asset'` (default, existing behavior): bundled asset / iOS bundle resource paths,
       *   e.g. `public/certificates/httpbin`.
       * - `'filesystem'`: absolute filesystem paths, or `file://` (iOS/Android) / `content://`
       *   (Android) URIs to `.cer`/`.pem` certificate files stored on device storage. Use this for
       *   certificates downloaded at runtime (rotation) and saved e.g. via `@capacitor/filesystem`
       *   with `Filesystem.getUri()` to obtain the path/URI.
       */
      source?: 'asset' | 'filesystem';
    };
    timeoutInterval?: number;
    disableAllSecurity?: boolean;
    caseSensitiveHeaders?: boolean;
    fileName?: string;
    fileSaveDirectory?: Directory;
    followRedirects?: boolean;
  }
  export interface Response {
    bodyString?: string | any;
    data?: string | any;
    headers: Header;
    status: number;
    url: string;
  }
  export interface CapacitorFileType {
    name: string;
    type: string;
    uri?: string;
    path?: string;
  }
}
export interface NativeHttpPlugin extends Plugin {
  fetch(options: { url: string; options: NativeSSLPinning.Options }): Promise<NativeSSLPinning.Response>;
  getCookies(options: { domain: string }): Promise<NativeSSLPinning.Cookies>;
  removeCookieByName(options: { cookieName: string }): Promise<void>;
  toggleLogging(options: { enableLogging: boolean }): Promise<void>;
  /**
   * Clears the cached, per-domain native pinning configuration (OkHttpClient on Android,
   * URLSession on iOS). Both platforms build and cache a client/session per domain the first time a
   * pinned request is made and reuse it afterwards, so certificates replaced on disk (rotation) would
   * otherwise only take effect after an app restart. Call this after writing new certificate files
   * to storage so subsequent fetch() calls rebuild pinning with the fresh certificates.
   */
  clearCertificateCache(): Promise<void>;
}
