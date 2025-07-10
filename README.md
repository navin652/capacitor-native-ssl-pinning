# capacitor-native-ssl-pinning

Capacitor native SSL pinning & public key pinning using OkHttp 3 in Android, and AFNetworking on iOS.

## Install

```bash
npm install capacitor-native-ssl-pinning
npx cap sync
```

## Ways to Extract Public key
 - ### For public key pinning the public key should be extracted by the following options
    - #### From URL (replace google with your domain)
        - ```openssl s_client -servername google.com -connect google.com:443 | openssl x509 -pubkey -noout | openssl rsa -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64```
    - #### From .cer file
        - ```openssl x509 -in eftapme_com.cer -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64``` 
  

## API

<docgen-index>

* [`fetch(...)`](#fetch)
* [`getCookies(...)`](#getcookies)
* [`removeCookieByName(...)`](#removecookiebyname)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### fetch(...)

```typescript
fetch(options: { url: string; options: NativeSSLPinning.Options; }) => Promise<NativeSSLPinning.Response>
```

| Param         | Type                                            |
| ------------- | ----------------------------------------------- |
| **`options`** | <code>{ url: string; options: Options; }</code> |

**Returns:** <code>Promise&lt;Response&gt;</code>

--------------------


### getCookies(...)

```typescript
getCookies(options: { domain: string; }) => Promise<NativeSSLPinning.Cookies>
```

| Param         | Type                             |
| ------------- | -------------------------------- |
| **`options`** | <code>{ domain: string; }</code> |

**Returns:** <code>Promise&lt;Cookies&gt;</code>

--------------------


### removeCookieByName(...)

```typescript
removeCookieByName(options: { cookieName: string; }) => Promise<void>
```

| Param         | Type                                 |
| ------------- | ------------------------------------ |
| **`options`** | <code>{ cookieName: string; }</code> |

--------------------

</docgen-api>

## Types

### NativeSSLPinning.Response

```typescript
interface Response {
  bodyString?: string | any;
  data?: string | any;
  headers: Header;
  status: number;
  url: string;
}
```

### NativeSSLPinning.Cookies

```typescript
interface Cookies {
  [cookieName: string]: string;
}
```

### NativeSSLPinning.Header

```typescript
interface Header {
  [headerName: string]: string;
}
```

### NativeSSLPinning.CapacitorFileType

```typescript
interface Header {
  name: string;
  type: string;
  uri?: string;
  path?: string;
}
```

### Directory

```typescript
enum Directory {
  Documents = 'DOCUMENTS',
  Data = 'DATA',
  Library = 'LIBRARY',
  Cache = 'CACHE',
  External = 'EXTERNAL',
  ExternalStorage = 'EXTERNAL_STORAGE',
  ExternalCache = 'EXTERNAL_CACHE',
  LibraryNoCloud = 'LIBRARY_NO_CLOUD',
  Temporary = 'TEMPORARY',
}
```

### NativeSSLPinning.Options

```typescript
interface Options {
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
```

---

## Usage Examples

### Get Cookies

```typescript
import { NativeHttp } from 'capacitor-native-ssl-pinning';

NativeHttp.getCookies({ domain: 'https://your-api/' }).then(console.log);
```

### Remove Cookie By Name

```typescript
NativeHttp.removeCookieByName({ cookieName: 'sample' });
```

---

### Use SSL Pinning with .cer Files (pkPinning: false)

You can use SSL pinning with your own `.cer` files by setting `pkPinning: false` and providing the certificate file(s):

```typescript
NativeHttp.fetch({
  url: 'https://your-api/request',
  options: {
    method: 'POST',
    pkPinning: false,
    sslPinning: {
      certs: ['public/certificates/your-cert-file'], // Place your .cer in the correct platform folder
    },
    body: 'your-raw-body-string',
    followRedirects: true,
  },
})
  .then((resp) => {
    console.log(resp);
  })
  .catch(console.error);
```

---

### POST with String Body and Pinning

```typescript
NativeHttp.fetch({
  url: 'https://your-api/request',
  options: {
    method: 'POST',
    pkPinning: true,
    sslPinning: {
      certs: ['sha256/your-sha256-cert1', 'sha256/your-sha256-cert2'],
    },
    body: 'your-raw-body-string',
    followRedirects: true,
  },
})
  .then((resp) => {
    console.log(resp);
  })
  .catch(console.error);
```

### POST JSON Body

```typescript
NativeHttp.fetch({
  url: 'http://your-api/v1/users/login',
  options: {
    method: 'POST',
    disableAllSecurity: true,
    sslPinning: { certs: [] },
    body: JSON.stringify({ email: 'user@example.com', password: 'password' }),
  },
})
  .then((resp) => {
    console.log(resp);
  })
  .catch(console.error);
```

### POST File with Authorization

```typescript
const apkFileRead = await Filesystem.getUri({
  path: 'sample.apk',
  directory: Directory.Data,
});
const body = {
  _parts: [
    [
      'file',
      {
        uri: apkFileRead.uri,
        name: 'sample.apk',
        type: 'application/vnd.android.package-archive',
      },
    ],
  ],
};
NativeHttp.fetch({
  url: 'http://your-api/v1/files',
  options: {
    method: 'POST',
    disableAllSecurity: true,
    timeoutInterval: 180000,
    sslPinning: { certs: [] },
    body,
    headers: {
      Authorization: 'Bearer <your-token>',
    },
  },
})
  .then((resp) => {
    console.log(resp);
  })
  .catch(console.error);
```

### POST with URL-Encoded Body

```typescript
const urlencoded = new URLSearchParams();
urlencoded.append('userID', 'test');
urlencoded.append('password', 'ZHXvd2%3D%3D');
urlencoded.append('authProvider', '');
urlencoded.append('mfaToken', '');

NativeHttp.fetch({
  url: 'http://your-api/login',
  options: {
    method: 'POST',
    disableAllSecurity: true,
    sslPinning: { certs: [] },
    body: urlencoded.toString(),
    headers: {
      'content-type': 'application/x-www-form-urlencoded',
    },
  },
})
  .then((resp) => {
    console.log(resp);
  })
  .catch(console.error);
```

### GET Request with Bearer Token

```typescript
NativeHttp.fetch({
  url: 'http://your-api/features',
  options: {
    method: 'GET',
    disableAllSecurity: true,
    sslPinning: { certs: [] },
    headers: {
      Authorization: 'Bearer <your-token>',
    },
  },
})
  .then((resp) => {
    console.log(resp);
  })
  .catch(console.error);
```

### POST with FormData (Multiple Fields)

```typescript
const form1 = new FormData();
form1.append('username', 'john_doe');
form1.append('email', 'john@example.com');
form1.append('role', 'admin');
NativeHttp.fetch({
  url: 'https://your-api/post',
  options: {
    method: 'POST',
    body: form1,
    pkPinning: false,
    disableAllSecurity: true,
    sslPinning: { certs: [] },
  },
})
  .then((response) => {
    console.log('HTTPBIN RESPONSE:', response);
  })
  .catch((err) => {
    console.error('FormData test error:', err);
  });
```

### GET Request with Accept Header

```typescript
NativeHttp.fetch({
  url: 'https://your-api/get',
  options: {
    method: 'GET',
    disableAllSecurity: true,
    sslPinning: { certs: [] },
    headers: {
      Accept: 'application/json',
    },
  },
})
  .then((res) => console.log('GET RESPONSE:', res))
  .catch((err) => console.error('GET ERROR:', err));
```

### POST with FormData (Simple)

```typescript
const form = new FormData();
form.append('username', 'john_doe');
form.append('email', 'john@example.com');
NativeHttp.fetch({
  url: 'https://your-api/post',
  options: {
    method: 'POST',
    disableAllSecurity: true,
    sslPinning: { certs: [] },
    body: form,
    headers: {
      Accept: 'application/json',
    },
  },
})
  .then((res) => console.log('POST RESPONSE:', res))
  .catch((err) => console.error('POST ERROR:', err));
```

### PUT Request with JSON

```typescript
NativeHttp.fetch({
  url: 'https://your-api/put',
  options: {
    method: 'PUT',
    disableAllSecurity: true,
    sslPinning: { certs: [] },
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      id: 123,
      name: 'Updated User',
    }),
  },
})
  .then((res) => console.log('PUT RESPONSE:', res))
  .catch((err) => console.error('PUT ERROR:', err));
```

### DELETE Request with Bearer Token

```typescript
NativeHttp.fetch({
  url: 'https://your-api/delete',
  options: {
    method: 'DELETE',
    disableAllSecurity: true,
    sslPinning: { certs: [] },
    headers: {
      Authorization: 'Bearer example-token',
    },
  },
})
  .then((res) => console.log('DELETE RESPONSE:', res))
  .catch((err) => console.error('DELETE ERROR:', err));
```

### Upload File with FormData

```typescript
const fileBlob = await fetch('./assets/icon/favicon.png').then((r) => r.blob());
const formData = new FormData();
formData.append('username', 'john_doe');
formData.append('profile_pic', fileBlob, 'avatar.jpg');

NativeHttp.fetch({
  url: 'https://your-api/upload',
  options: {
    method: 'POST',
    body: formData,
    sslPinning: { certs: [] },
    disableAllSecurity: true,
  },
})
  .then((res) => {
    console.log('Upload response:', res);
  })
  .catch((err) => {
    console.error('Upload failed:', err);
  });
```

### Upload Multiple Files Using Capacitor File URIs with .cer File

```typescript
const apkFileRead = await Filesystem.getUri({
  path: 'sample.apk',
  directory: Directory.Data,
});
const pdfFileRead = await Filesystem.getUri({
  path: 'dummy.pdf',
  directory: Directory.Data,
});
const body = {
  _parts: [
    ['username', 'john_doe'],
    [
      'apkDetails',
      {
        uri: apkFileRead.uri,
        name: 'sample.apk',
        type: 'application/vnd.android.package-archive',
      },
    ],
    [
      'pdfDetails',
      {
        uri: pdfFileRead.uri,
        name: 'dummy.pdf',
        type: 'application/pdf',
      },
    ],
  ],
};
const res = await NativeHttp.fetch({
  url: 'https://your-api/upload',
  options: {
    method: 'POST',
    body,
    pkPinning: false,
    sslPinning: { certs: ['public/certificates/httpbin'] },
    timeoutInterval: 90000,
  },
});
console.log('Capacitor native file upload response:', res);
```

### Download File as Base64

```typescript
NativeHttp.fetch({
  url: 'https://your-api/dummy.pdf',
  options: {
    method: 'GET',
    disableAllSecurity: true,
    responseType: 'base64',
    sslPinning: { certs: [] },
    fileName: 'dummy.pdf',
    fileSaveDirectory: Directory.Documents,
  },
})
  .then((response) => {
    console.log(response);
  })
  .catch((err) => console.error('DOWNLOAD ERROR:', err));
```

### Download File as Blob

```typescript
NativeHttp.fetch({
  url: 'http://your-api/v1/files/download/sample',
  options: {
    method: 'GET',
    disableAllSecurity: true,
    responseType: 'blob',
    sslPinning: { certs: [] },
    fileName: 'sample.apk',
  },
})
  .then((response) => {
    console.log(response);
  })
  .catch((err) => console.error('DOWNLOAD ERROR:', err));
```

### File Input Upload (Web)

```typescript
const file = input.files?.[0];
if (!file) return;

const formData = new FormData();
formData.append('username', 'john_doe');
formData.append('profile_pic', file);

const res = await NativeHttp.fetch({
  url: 'https://your-api/upload',
  options: {
    method: 'POST',
    body: formData,
    disableAllSecurity: true,
    sslPinning: { certs: [] },
  },
});

console.log('File input upload response:', res);
```

### File Picker Upload (Capacitor Plugin)

```typescript
const result = await FilePicker.pickFiles({
  types: ['image/*'],
});

const file = result.files[0];
const body = {
  _parts: [
    ['username', 'john_doe'],
    [
      'profile_pic',
      {
        uri: file.path,
        name: file.name,
        type: file.mimeType,
      },
    ],
  ],
};

const response = await NativeHttp.fetch({
  url: 'https://your-api/upload',
  options: {
    method: 'POST',
    body: body,
    disableAllSecurity: true,
    sslPinning: { certs: [] },
  },
});

console.log('Upload result:', response);
```

---
