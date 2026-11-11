/// <reference types="vite/client" />
/**
 * audio-worklet.js 语法守卫（回归 d828582c7a72）：
 * 该文件经 AudioPipeline 的 new URL + ctx.audioWorklet.addModule 由浏览器按纯 JS
 * 原生加载（vite 对 new URL 资产引用不做转译），任何 TS 专属语法（private/readonly
 * 修饰符等）都会使 addModule reject → 音频链路完全不可用。
 * 本测试等价于 `node --check` 的框架内守卫：源码必须可被纯 JS 解析，且不含 TS 修饰符。
 */
import { describe, expect, it } from 'vitest';

// vite ?raw 原样导入源码文本（Vite 原生支持，运行时零依赖）
import workletSrc from '../src/media/audio-worklet.js?raw';

describe('audio-worklet.js 语法守卫', () => {
  it('源码可被纯 JS 解析（new Function ≈ node --check）', () => {
    expect(() => new Function(workletSrc)).not.toThrow();
  });

  it('不含 TS 修饰符 private/readonly（原生加载零转译）', () => {
    expect(workletSrc).not.toMatch(/\b(private|readonly)\b/);
  });
});
