import { Capacitor, registerPlugin } from '@capacitor/core';
import type { NativeHttpPlugin, NativeSSLPinning } from './definitions';
import type { FileSystemWriteChunkType } from './types';

const NativeHttpPluginRef = registerPlugin<NativeHttpPlugin>('NativeHttp', {
  web: () => import('./web').then((m) => new m.NativeHttpWeb()),
});

/**
 * Converts FormData to a format compatible with NativeHttp.
 * This function reads files and blobs from FormData and converts them to base64 strings.
 * It also handles Capacitor File objects with native URIs.
 * @param formData - The FormData object to convert.
 * @returns A promise that resolves to an object with _parts array for NativeHttp.
 */
async function convertFormDataToNativeJsonAsync(formData: FormData): Promise<any> {
  const parts: Array<[string, any]> = [];

  for (const [key, value] of (formData as any).entries() as IterableIterator<
    [string, FormDataEntryValue | FileSystemWriteChunkType | NativeSSLPinning.CapacitorFileType]
  >) {
    if (value instanceof File || value instanceof Blob) {
      const base64 = await readFileAsBase64(value);
      parts.push([
        key,
        {
          name: (value as File).name || 'upload.bin',
          type: value.type || 'application/octet-stream',
          data: base64,
        },
      ]);
    } else if (typeof value === 'object' && 'uri' in value) {
      // Capacitor File object with native URI
      parts.push([
        key,
        {
          name: value.name ?? 'file',
          type: value.type ?? 'application/octet-stream',
          uri: value.uri ?? value.path,
        },
      ]);
    } else {
      // Simple string/text field
      parts.push([key, value]);
    }
  }

  return { _parts: parts };
}

/**
 * Reads a File or Blob and converts it to a base64 string.
 * This function uses FileReader to read the file as a data URL and extracts the base64 part.
 * @param file - The File or Blob to read.
 * @returns A promise that resolves to the base64 string of the file.
 * @throws An error if reading the file fails.
 */
function readFileAsBase64(file: File | Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result as string;
      resolve(result.split(',')[1]); // Strip data:...base64,
    };
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

/**
 * NativeHttp provides methods for making HTTP requests with SSL pinning,
 * handling cookies, and managing file downloads.
 * It supports various response types and allows for custom headers and options.
 * This interface is designed to work with the Capacitor framework and can be used in both iOS and Android applications.
 */
const NativeHttp = {
  /**
   * Makes an HTTP request with SSL pinning support.
   * This method allows you to specify the URL, request options, and SSL pinning configuration.
   * It supports various response types such as text, base64, blob, and file.
   * It also handles cookies and allows removing cookies by name.
   * @param options - The options for the fetch request, including URL and request options.
   * @returns A promise that resolves to a NativeSSLPinning.Response object containing the response
   * data, status, headers, and URL.
   * @throws An error if the fetch request fails or if the response cannot be processed.
   */
  async fetch({ url, options }: { url: string; options: NativeSSLPinning.Options }) {
    const finalOptions: NativeSSLPinning.Options = { ...options };

    if (Capacitor.isNativePlatform() && options.body instanceof FormData) {
      const bodyJson = await convertFormDataToNativeJsonAsync(options.body);
      finalOptions.body = bodyJson;
      finalOptions.headers = {
        'Content-Type': 'application/json',
        ...finalOptions.headers,
      };
    }

    return NativeHttpPluginRef.fetch({ url, options: finalOptions });
  },

  /**
   * Returns cookies for a given URL.
   * This method retrieves all cookies available in the current document context.
   * If you need domain-specific cookies, you may need to implement additional logic to filter them
   * based on the domain parameter.
   * @param options - The options for retrieving cookies, including the domain.
   * @returns A promise that resolves to an object containing cookies, where each key is a cookie name
   * and the value is the cookie value.
   * @throws An error if the cookie retrieval fails.
   */
  async getCookies(options: { domain: string }) {
    return NativeHttpPluginRef.getCookies(options);
  },

  /**
   * Removes a cookie by name and URL.
   * This method sets the cookie's expiration date to a past date,
   * effectively deleting it from the browser's cookie store.
   * Note: This implementation does not check if the cookie exists before attempting to remove it.
   * It simply sets the cookie's expiration date to a past date, which is the standard way to delete cookies in web browsers.
   * If the cookie does not exist, this operation will not throw an error; it will simply not affect any existing cookies.
   * @param options - The options for removing the cookie, including the cookie name.
   * @returns A promise that resolves when the cookie is removed.
   * @throws An error if the cookie removal fails.
   */
  async removeCookieByName(options: { cookieName: string }) {
    return NativeHttpPluginRef.removeCookieByName(options);
  },
  async toggleLogging(options: { enableLogging: boolean }) {
    return NativeHttpPluginRef.toggleLogging(options);
  }
};

export * from './definitions';
export { NativeHttp };
