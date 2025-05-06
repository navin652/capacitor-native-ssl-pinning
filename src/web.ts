import { WebPlugin } from '@capacitor/core';

import type { NativeHttpPlugin } from './definitions';

export class NativeHttpWeb extends WebPlugin implements NativeHttpPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
