import { WebPlugin } from '@capacitor/core';
import type { NativeHttpPlugin, NativeSSLPinning } from './definitions';
export class NativeHttpWeb extends WebPlugin implements NativeHttpPlugin {
  /**
   * Performs a fetch request, including FormData with files and text fields.
   * This implementation uses the standard fetch API available in web browsers.
   * It supports various response types like text, base64, blob, and file.
   * It also handles cookies and allows removing cookies by name.
   * This is a web implementation and does not support SSL pinning or native features.
   * @param _options - The options for the fetch request, including URL and request options.
   * @returns A promise that resolves to a NativeSSLPinning.Response object containing the response
   * data, status, headers, and URL.
   * @throws An error if the fetch request fails or if the response cannot be processed.
   */
  async fetch(_options: { url: string; options: NativeSSLPinning.Options }): Promise<NativeSSLPinning.Response> {
    const { url, options } = _options;

    const headers = new Headers(options.headers || {});
    const method = options.method || 'GET';

    let body: BodyInit | undefined;

    if (options.body instanceof FormData) {
      body = options.body;
    } else if (typeof options.body === 'string') {
      body = options.body;
    } else if (typeof options.body === 'object' && options.body !== null) {
      if (!headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json');
      }
      body = JSON.stringify(options.body);
    }

    const response = await fetch(url, {
      method,
      headers,
      body,
      credentials: options.credentials as RequestCredentials | undefined,
    });

    const contentType = response.headers.get('Content-Type') || '';

    let data: any;
    const resType = options.responseType ?? 'text';

    if (resType === 'blob') {
      data = await response.blob();
    } else if (resType === 'base64') {
      const blob = await response.blob();
      data = await this.blobToBase64(blob);
    } else if (resType === 'file') {
      const blob = await response.blob();
      const fileName = options.fileName ?? 'download.bin';
      const fileUrl = URL.createObjectURL(blob);
      data = {
        fileName,
        blob,
        fileUrl,
        mimeType: contentType,
      };
    } else {
      try {
        data = await response.text();
      } catch {
        data = '';
      }
    }

    return Promise.resolve({
      status: response.status,
      headers: Object.fromEntries((response.headers as any).entries()),
      data,
      url, // Add the url property to match the Response interface
    });
  }

  /**
   * Retrieves cookies for a given domain.
   * This implementation reads cookies from the document.cookie string.
   * It returns an object where each key is a cookie name and the value is the cookie value.
   * This is a web implementation and does not support native cookie management.
   * @param _options - The options containing the domain for which to retrieve cookies.
   * @returns A promise that resolves to a NativeSSLPinning.Cookies object containing the
   * cookies for the specified domain.
   * @throws An error if the cookies cannot be retrieved.
   * Note: This implementation does not filter cookies by domain as web browsers handle cookies
   * automatically based on the current document's domain. The domain parameter is included for
   * compatibility with the NativeSSLPinning.Cookies interface.
   * It retrieves all cookies available in the current document context.
   * If you need domain-specific cookies, you may need to implement additional logic to filter them
   * based on the domain parameter.
   */
  getCookies(_options: { domain: string }): Promise<NativeSSLPinning.Cookies> {
    const cookieStr = document.cookie;
    const cookies: NativeSSLPinning.Cookies = {};

    cookieStr.split(';').forEach((cookie) => {
      const [key, val] = cookie.split('=').map((c) => c.trim());
      if (key) cookies[key] = val;
    });

    return Promise.resolve(cookies);
  }

  /**
   * Removes a cookie by its name.
   * This implementation sets the cookie's expiration date to a past date, effectively deleting it.
   * It uses the document.cookie API to remove the cookie.
   * This is a web implementation and does not support native cookie management.
   * @param _options - The options containing the cookie name to remove.
   * @returns A promise that resolves when the cookie is successfully removed.
   * @throws An error if the cookie cannot be removed.
   * Note: This implementation does not check if the cookie exists before attempting to remove it.
   * It simply sets the cookie's expiration date to a past date, which is the standard way to delete cookies in web browsers.
   * If the cookie does not exist, this operation will not throw an error; it will simply not affect any existing cookies.
   */
  removeCookieByName(_options: { cookieName: string }): Promise<void> {
    const { cookieName } = _options;

    document.cookie = `${cookieName}=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/`;
    return Promise.resolve();
  }

  /**
   * Converts a Blob to a Base64 string.
   * This method uses the FileReader API to read the Blob as a Data URL and extracts
   * the Base64 part from the resulting string.
   * @param blob - The Blob to convert to Base64.
   * @returns A promise that resolves to the Base64 string representation of the Blob.
   * @throws An error if the FileReader encounters an error while reading the Blob.
   * Note: The Base64 string returned does not include the data URL prefix (data:*;base64,).
   * It only contains the Base64-encoded data.
   * This is useful for scenarios where you need to send the Base64 data
   * over a network or store it in a database without the additional metadata.
   */
  blobToBase64 = (blob: Blob): Promise<string> =>
    new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onerror = reject;
      reader.onloadend = () => {
        const result = reader.result as string;
        const base64 = result.split(',')[1]; // Remove data:*/*;base64, prefix
        resolve(base64);
      };
      reader.readAsDataURL(blob);
    });

  /**
   * Toggles logging for the plugin.
   * This method is not implemented in the web version as logging is typically handled
   * through the console in web applications.
   * @param _options - The options containing the enableLogging flag.
   * @returns A promise that resolves when the logging state is toggled.
   * @throws An error indicating that this method is not implemented in the web version.
   * Note: This method is included for compatibility with the NativeHttpPlugin interface,
   * but it does not perform any actual logging operations in the web implementation.
   */
  toggleLogging(_options: { enableLogging: boolean }): Promise<void> {
    throw new Error('Method not implemented.As this is a web implementation, logging cannot be toggled.');
  }
}
