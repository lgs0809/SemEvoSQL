/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import { semEvoSQLService, type QueryCorrectionOption } from '@/services/semevosql';

type AssetType = QueryCorrectionOption['assetType'];

/** Shared cursor search for both correction surfaces; old responses never replace a new search. */
export function useCorrectionOptions() {
  const options = ref<QueryCorrectionOption[]>([]);
  const loading = ref(false);
  const hasMore = ref(false);
  let scope: { runId: string; type: AssetType } | undefined;
  let query = '';
  let cursor = 0;
  let generation = 0;

  function clear() {
    generation++;
    scope = undefined;
    options.value = [];
    hasMore.value = false;
    loading.value = false;
    cursor = 0;
  }
  async function load(append: boolean) {
    if (!scope || (append && (loading.value || !hasMore.value))) return;
    const token = ++generation;
    loading.value = true;
    try {
      const page = await semEvoSQLService.correctionOptions(scope.runId, scope.type, query, append ? cursor : 0);
      if (token !== generation) return;
      options.value = append ? [...options.value, ...page.options] : page.options;
      hasMore.value = page.hasMore;
      cursor = page.nextAfterId ?? 0;
    } catch (error) {
      if (token === generation) ElMessage.error(error instanceof Error ? error.message : '业务含义加载失败');
    } finally {
      if (token === generation) loading.value = false;
    }
  }
  async function search(text: string) {
    query = text;
    options.value = [];
    hasMore.value = false;
    await load(false);
  }
  async function reset(runId: string, type: AssetType) {
    clear();
    scope = { runId, type };
    await search('');
  }
  return { options, loading, hasMore, clear, reset, search, loadMore: () => load(true) };
}
