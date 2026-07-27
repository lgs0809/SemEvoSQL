<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <el-drawer v-model="open" title="回归检查结果" size="min(800px, 100vw)" destroy-on-close>
    <template v-if="job">
      <div class="verdict">
        <el-tag :type="verdict.type" size="large">{{ verdict.label }}</el-tag>
        <p>{{ verdict.summary || job.error_message || '任务结束后显示检查结果。' }}</p>
        <span v-if="job.id" class="record-id">任务编号 {{ job.id }}</span>
      </div>
      <el-alert v-if="job.status !== 'SUCCEEDED'" type="info" :closable="false" title="以下仅展示已保存的记录，不代表任务完成。" show-icon />
      <el-empty v-if="!cases.length" description="尚未保存逐项依据；不能据此认定检查通过。" :image-size="70" />
      <article v-for="(item, index) in cases" :key="`${item.code}-${index}`" class="case-card">
        <header>
          <div>
            <h3>{{ questions.get(item.code) || item.code }}</h3>
            <span v-if="questions.has(item.code)" class="case-code">{{ item.code }}</span>
          </div>
          <el-tag :type="item.type">{{ item.label }}</el-tag>
        </header>
        <dl class="evidence-metrics">
          <div><dt>模型调用</dt><dd>{{ item.modelCalls ?? '未记录' }}</dd></div>
          <div><dt>实际数据源</dt><dd>{{ item.sources ?? '未记录' }}</dd></div>
          <div><dt>执行耗时</dt><dd>{{ item.latencyMs === undefined ? '未记录' : `${item.latencyMs} ms` }}</dd></div>
          <div><dt>结果行数</dt><dd>{{ item.rows ?? '未记录' }}</dd></div>
        </dl>
        <div v-if="item.errors.length" class="failure-reasons">
          <strong>未通过的原因</strong>
          <ul><li v-for="(error, errorIndex) in item.errors" :key="errorIndex">{{ error }}</li></ul>
        </div>
        <section class="actual-results" aria-label="实际执行结果">
          <h4>实际结果</h4>
          <p v-if="!item.results.length" class="muted">这条记录没有保存实际结果明细；请查看该次任务的执行记录。</p>
          <div v-for="(result, resultIndex) in item.results" :key="resultIndex" class="source-result">
            <span class="muted">已保存 {{ result.rows.length }} 条结果</span>
            <el-table :data="result.rows" max-height="280" empty-text="本次查询未返回记录" border>
              <el-table-column v-for="column in result.columns" :key="column" :prop="column" :label="column" min-width="150">
                <template #default="scope">{{ displayValue(scope.row[column]) }}</template>
              </el-table-column>
            </el-table>
          </div>
        </section>
        <details v-if="item.queries.length" class="technical-records">
          <summary>查看实际 SQL 与绑定参数</summary>
          <div v-for="(query, queryIndex) in item.queries" :key="queryIndex">
            <p>数据源 {{ query.datasourceId ?? '未记录' }}</p>
            <pre>{{ query.sql }}</pre>
            <p>绑定参数</p><pre>{{ JSON.stringify(query.parameters, null, 2) || '未记录' }}</pre>
          </div>
        </details>
      </article>
      <details v-if="raw" class="technical-records raw-records">
        <summary>原始技术记录</summary><pre>{{ JSON.stringify(raw, null, 2) }}</pre>
      </details>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import { replayCaseEvidence, replayVerdict, storedObject } from '@/utils/evaluation-presentation';

  const open = defineModel<boolean>({ required: true });
  const props = defineProps<{
    job?: { id?: string; status?: string; result_json?: unknown; error_message?: string };
    goldenCases: { case_code?: string; question?: string }[];
  }>();
  const raw = computed(() => storedObject(props.job?.result_json));
  const cases = computed(() => replayCaseEvidence(props.job?.result_json));
  const verdict = computed(() => replayVerdict(props.job || {}));
  const questions = computed(() => new Map(props.goldenCases.filter(item => item.case_code && item.question).map(item => [item.case_code!, item.question!])));
  const displayValue = (value: unknown) => value === null ? 'NULL' : value === undefined ? '未记录' : typeof value === 'object' ? JSON.stringify(value) : String(value);
</script>

<style scoped>
  .verdict { margin-bottom: 20px; }
  .verdict p { color: #475569; }
  .record-id, .case-code { font-size: 12px; color: #64748b; overflow-wrap: anywhere; }
  .case-card { margin-top: 18px; padding: 18px; border: 1px solid var(--el-border-color-light); border-radius: 12px; }
  .case-card header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
  .case-card header > div { min-width: 0; }
  h3 { margin: 0 0 5px; font-size: 16px; line-height: 1.6; overflow-wrap: anywhere; }
  h4 { margin: 0 0 10px; font-size: 14px; }
  .evidence-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; padding: 14px; background: #f8fafc; border-radius: 8px; }
  dt { color: #64748b; font-size: 12px; }
  dd { margin: 6px 0 0; font-size: 16px; font-weight: 600; }
  .failure-reasons { background: var(--el-color-danger-light-9); padding: 14px; border-radius: 8px; margin-bottom: 18px; overflow-wrap: anywhere; }
  .failure-reasons ul { margin: 8px 0 0; padding-left: 20px; }
  .muted { color: #64748b; font-size: 13px; }
  .source-result { margin-bottom: 10px; }
  .technical-records { margin-top: 16px; }
  .technical-records summary { cursor: pointer; color: var(--el-color-primary); padding: 5px 0; }
  pre { max-height: 350px; overflow: auto; padding: 12px; border-radius: 8px; background: #f8fafc; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; }
  @media (max-width: 600px) {
    .case-card { padding: 12px; }
    .evidence-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  }
</style>
