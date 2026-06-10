import type { Preset } from 'unocss';
import { presetUni } from '@uni-helper/unocss-preset-uni';
import { presetLegacyCompat } from '@unocss/preset-legacy-compat';
import { defineConfig, presetIcons, transformerDirectives, transformerVariantGroup } from 'unocss';

export default defineConfig({
  presets: [
    // https://uni-helper.js.org/unocss-preset-uni
    presetUni({
      attributify: {
        prefixedOnly: true,
      },
    }),
    // https://unocss.dev/presets/icons
    presetIcons({
      scale: 1.2,
      warn: true,
      extraProperties: {
        'display': 'inline-block',
        'vertical-align': 'middle',
      },
    }),
    /**
     * 启用 legacy-compat 模式(处理低端安卓机的样式问题)
     */
    presetLegacyCompat({
      commaStyleColorFunction: true,
      legacyColorSpace: true,
    }) as Preset,
  ],
  shortcuts: {
    'border-base': 'border border-gray-500_10',
    'center': 'flex justify-center items-center',
  },
  rules: [
    ['pb-safe', { 'padding-bottom': 'env(safe-area-inset-bottom)' }],
  ],
  theme: {
    colors: {
      'primary': 'var(--theme-primary)',
      'success': 'var(--theme-success)',
      'warning': 'var(--theme-warning)',
      'error': 'var(--theme-error)',
      'text-main': 'var(--theme-main-color)',
      'text-content': 'var(--theme-content-color)',
      'text-tips': 'var(--theme-tips-color)',
      'text-light': 'var(--theme-light-color)',
      'text-disabled': 'var(--theme-disabled-color)',
      'bg-main': 'var(--theme-bg-color)',
      'bg-secondary': 'var(--theme-bg-color-secondary)',
      'border-main': 'var(--theme-border-color)',
    },
  },
  transformers: [
    transformerDirectives(),
    transformerVariantGroup(),
  ],
});
