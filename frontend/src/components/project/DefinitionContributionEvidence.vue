<!-- Copyright 2026 the original author or authors. Licensed under the Apache License, Version 2.0. -->
<template>
  <section class="contribution-evidence" aria-label="共享贡献来源">
    <el-button :loading="loading" @click="load(0)">{{ evidence ? '刷新使用记录' : '查看使用与撤回记录' }}</el-button>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />
    <template v-if="evidence">
      <p>当前有效贡献：{{ evidence.totals.validUsers }} 人 · {{ evidence.totals.validUses }} 次。每次查询仅计一次；下方保留各定义修订的历史记录。</p>
      <el-alert v-if="changed" title="贡献依据已更新，请刷新建议列表后再审核。" type="info" :closable="false" />
      <p v-if="!evidence.totalRecords">尚无关联查询使用记录。</p>
      <ol v-else class="receipt-list">
        <li v-for="row in evidence.records" :key="`${row.preference_id}:${row.definition_revision}:${row.run_id}`">
          <div class="receipt-heading"><strong>{{ row.user_id }}</strong><el-tag :type="row.counted ? 'success' : 'info'">{{ stateLabel(row.contribution_state) }}</el-tag></div>
          <p>使用口径：{{ candidate.business_name }}</p>
          <small>个人定义 {{ row.preference_id }} · 修订 {{ row.definition_revision }} · {{ formatTime(row.create_time) }}</small>
          <details><summary>查看查询来源</summary><p>查询 {{ row.run_id }} · 版本 {{ row.project_version_id }} · {{ row.run_status }}</p></details>
        </li>
      </ol>
      <nav v-if="evidence.totalRecords > evidence.limit" class="paging" aria-label="贡献记录分页">
        <el-button :disabled="loading || evidence.offset === 0" @click="load(Math.max(0, evidence.offset - evidence.limit))">上一页</el-button>
        <span>{{ evidence.offset + 1 }}–{{ Math.min(evidence.offset + evidence.limit, evidence.totalRecords) }} / {{ evidence.totalRecords }}</span>
        <el-button :disabled="loading || evidence.offset + evidence.limit >= evidence.totalRecords" @click="load(evidence.offset + evidence.limit)">下一页</el-button>
      </nav>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { semEvoSQLService, type ProjectDefinitionCandidate, type ProjectDefinitionContributionEvidence } from '@/services/semevosql';
const props = defineProps<{ projectId: number; candidate: ProjectDefinitionCandidate }>();
const evidence = ref<ProjectDefinitionContributionEvidence>();
const loading = ref(false);
const error = ref('');
let requestSequence = 0;
const changed = computed(() => evidence.value && (evidence.value.contentRevision !== props.candidate.content_revision || evidence.value.evidenceRevision !== props.candidate.evidence_revision || evidence.value.totals.fingerprint !== props.candidate.contributions.fingerprint));
watch(() => [props.projectId, props.candidate.id, props.candidate.content_revision], () => { requestSequence++; evidence.value = undefined; error.value = ''; loading.value = false; });
async function load(offset: number) {
  const sequence = ++requestSequence;
  loading.value = true; error.value = '';
  try {
    const result = await semEvoSQLService.projectDefinitionContributions(props.projectId, props.candidate.id, offset);
    if (sequence === requestSequence) evidence.value = result;
  } catch {
    if (sequence === requestSequence) error.value = '未能读取贡献来源，请稍后重试。';
  } finally {
    if (sequence === requestSequence) loading.value = false;
  }
}
function stateLabel(state: ProjectDefinitionContributionEvidence['records'][number]['contribution_state']) {
  return { COUNTED: '已计入', WITHDRAWN: '已撤回或失效', SOURCE_INELIGIBLE: '来源当前不具备推广资格', NOT_COUNTED: '未满足有效使用条件' }[state];
}
function formatTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}
</script>

<style scoped>
.contribution-evidence { margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--el-border-color-lighter); }
.receipt-list { list-style: none; padding: 0; max-height: 440px; overflow: auto; }
.receipt-list li { padding: 14px; margin: 10px 0; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-fill-color-extra-light); }
.receipt-heading, .paging { display: flex; gap: 12px; align-items: center; justify-content: space-between; flex-wrap: wrap; }
small { color: var(--el-text-color-secondary); }
summary { cursor: pointer; color: var(--el-color-primary); font-size: 12px; }
p { margin: 8px 0; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
