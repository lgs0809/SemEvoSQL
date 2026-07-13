import { defineConfig, globalIgnores } from 'eslint/config'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import vueParser from 'vue-eslint-parser'
import skipFormatting from '@vue/eslint-config-prettier/skip-formatting'

export default defineConfig(
  {
    name: 'semevosql/files-to-lint',
    files: ['**/*.{js,mjs,cjs,ts,mts,cts,jsx,tsx,vue}'],
  },
  globalIgnores(['dist/**', 'node_modules/**']),
  pluginVue.configs['flat/essential'],
  // Include SFC scripts in the same recommended TypeScript rules without
  // scanning the source tree through a separate glob dependency.
  tseslint.configs.recommended.map((config) => ({
    ...config,
    ...(config.files && { files: [...config.files, '**/*.vue'] }),
  })),
  pluginVue.configs['flat/base'],
  {
    name: 'semevosql/vue-typescript-parser',
    files: ['*.vue', '**/*.vue'],
    languageOptions: {
      parser: vueParser,
      parserOptions: {
        parser: {
          js: 'espree',
          jsx: 'espree',
          ts: tseslint.parser,
          tsx: tseslint.parser,
        },
        ecmaVersion: 2024,
        ecmaFeatures: { jsx: false },
        extraFileExtensions: ['.vue'],
      },
    },
    rules: {
      'vue/block-lang': [
        'off',
        { script: { lang: ['ts'], allowNoLang: false } },
      ],
    },
  },
  {
    rules: {
      'vue/block-lang': 'off',
      'vue/multi-word-component-names': 'off',
    },
  },
  skipFormatting,
)
