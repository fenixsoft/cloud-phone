/**
 * e2e 的 node 内置模块最小类型垫片（纯类型声明，无运行时代码）。
 *
 * 背景：e2e 目录纳入 app/tsconfig.json include 后需过 strict tsc；本项目 compilerOptions
 * types: [] 且未安装 @types/node（package-lock 中 node:* 声明仅来自 vite/vitest 的
 * optional peerDependencies，npm ci 不会落地），e2e 脚本（Playwright 转译执行，非 tsc
 * 构建产物）所需的 node:net / node:child_process / node:fs / node:path / node:url 无法解析。
 * 本文件仅声明 e2e 实际用到的 API 子集；若未来引入 @types/node，请删除本文件避免声明合并冲突。
 */

declare var process: {
  env: Record<string, string | undefined>;
  kill(pid: number, signal?: number | string): boolean;
};

interface ImportMeta {
  url: string;
}

declare class Buffer extends Uint8Array {
  static alloc(size: number): Buffer;
  static concat(list: readonly Buffer[]): Buffer;
  static from(input: Buffer | ArrayBuffer | ArrayBufferView): Buffer;
  readUInt32BE(offset: number): number;
  subarray(start?: number, end?: number): Buffer;
  toString(encoding?: string): string;
}

declare module 'node:net' {
  export interface Socket {
    write(chunk: Uint8Array): boolean;
    destroy(): void;
    on(event: string, listener: (...args: any[]) => void): this;
  }
  export function connect(port: number, host: string, listener?: () => void): Socket;
}

declare module 'node:child_process' {
  export interface ChildProcessStdioStream {
    on(event: string, listener: (...args: any[]) => void): void;
  }
  export interface ChildProcess {
    pid: number | undefined;
    stdout: ChildProcessStdioStream;
    stderr: ChildProcessStdioStream;
    on(event: string, listener: (...args: any[]) => void): void;
  }
  export interface SpawnOptions {
    cwd?: string;
    env?: Record<string, string | undefined>;
    stdio?: 'ignore' | 'inherit' | 'pipe' | readonly ('ignore' | 'inherit' | 'pipe')[];
  }
  export function spawn(command: string, args: readonly string[], options?: SpawnOptions): ChildProcess;
  export function execSync(command: string, options?: { stdio?: 'ignore' | 'inherit' | 'pipe' }): unknown;
}

declare module 'node:fs' {
  export interface Stats {
    mtimeMs: number;
  }
  export function existsSync(path: string): boolean;
  export function statSync(path: string): Stats;
  export function writeFileSync(path: string, data: string): void;
  export function readFileSync(path: string, encoding?: string): string;
  export function unlinkSync(path: string): void;
}

declare module 'node:path' {
  export function dirname(path: string): string;
  export function join(...paths: string[]): string;
  export function resolve(...paths: string[]): string;
}

declare module 'node:url' {
  export function fileURLToPath(url: string): string;
}
