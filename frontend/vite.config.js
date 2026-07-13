/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const backendTarget = process.env.SEMEVOSQL_BACKEND_URL || 'http://localhost:8065';
const currentDirectory = dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: [{ find: '@', replacement: resolve(currentDirectory, 'src') }],
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: backendTarget,
        changeOrigin: true,
      },
      '/nl2sql': {
        target: backendTarget,
        changeOrigin: true,
      },
      '/uploads': {
        target: backendTarget,
        changeOrigin: true,
      },
    },
    historyApiFallback: true,
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('/node_modules/echarts/') || id.includes('/node_modules/zrender/')) {
            return 'vendor-echarts';
          }
          if (
            id.includes('/node_modules/element-plus/') ||
            id.includes('/node_modules/@element-plus/')
          ) {
            return 'vendor-element-plus';
          }
          if (id.includes('/node_modules/vue/') || id.includes('/node_modules/vue-router/')) {
            return 'vendor-vue';
          }
          if (
            id.includes('/node_modules/markdown-it/') ||
            id.includes('/node_modules/marked/') ||
            id.includes('/node_modules/highlight.js/') ||
            id.includes('/node_modules/dompurify/')
          ) {
            return 'vendor-markdown';
          }
          if (id.includes('/node_modules/')) return 'vendor';
          return undefined;
        },
      },
    },
  },
});
