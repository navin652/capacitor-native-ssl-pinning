export interface NativeHttpPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
