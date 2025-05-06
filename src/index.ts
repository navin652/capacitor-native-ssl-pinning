import { registerPlugin } from '@capacitor/core';

import type { NativeHttpPlugin } from './definitions';

const NativeHttp = registerPlugin<NativeHttpPlugin>('NativeHttp', {
  web: () => import('./web').then((m) => new m.NativeHttpWeb()),
});

export * from './definitions';
export { NativeHttp };
