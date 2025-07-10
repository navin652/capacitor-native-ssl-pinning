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
}
