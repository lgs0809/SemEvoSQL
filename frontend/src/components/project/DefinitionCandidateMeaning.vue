<!-- Copyright 2026 the original author or authors. Licensed under the Apache License, Version 2.0. -->
<template>
  <div class="candidate-meaning">
    <h4>{{ candidate.business_name }} · 完整确认含义</h4>
    <p class="meaning">{{ candidate.definition_text }}</p>
    <p v-if="candidate.assessment_json?.alignment.reason">{{ candidate.assessment_json.alignment.reason }}</p>
    <ul v-if="candidate.assessment_json?.alignment.differences?.length">
      <li v-for="difference in candidate.assessment_json.alignment.differences" :key="difference">{{ difference }}</li>
    </ul>
    <p v-if="candidate.structured_json?.metric">计量单位：{{ candidate.structured_json.metric.unit ?? '尚未确定' }}。</p>
    <p v-if="candidate.assessment_state === 'RETRYABLE_FAILURE'">后台暂时未完成评估，将于 {{ candidate.assessment_next_attempt_at }} 起重试。你的个人查询不等待此任务。</p>
    <details>
      <summary>查看版本与证据编号</summary>
      <p>内容修订 {{ candidate.content_revision }} · 证据修订 {{ candidate.evidence_revision }} · 对照公共版本 {{ candidate.assessed_base_version_id ?? '待评估' }}</p>
      <p v-if="candidate.assessment_json?.alignment.targets?.length">对应公共资产：{{ candidate.assessment_json.alignment.targets.join('、') }}</p>
      <p v-if="candidate.structured_json?.metric">模型：{{ candidate.structured_json.metric.entity }} · 时间字段：{{ candidate.structured_json.metric.timeAttribute ?? '未绑定' }}</p>
      <p v-if="candidate.published_version_id">已发布版本：{{ candidate.published_version_id }} · 公共资产：{{ candidate.public_asset_key }}</p>
    </details>
    <DefinitionContributionEvidence :project-id="projectId" :candidate="candidate" />
  </div>
</template>

<script setup lang="ts">
import type { ProjectDefinitionCandidate } from '@/services/semevosql';
import DefinitionContributionEvidence from './DefinitionContributionEvidence.vue';
defineProps<{ projectId: number; candidate: ProjectDefinitionCandidate }>();
</script>

<style scoped>
.candidate-meaning { line-height: 1.7; min-width: 0; overflow-wrap: anywhere; }
h4 { margin: 0 0 10px; }
.meaning { white-space: pre-wrap; }
summary { cursor: pointer; color: var(--el-color-primary); font-size: 12px; }
details { margin-top: 12px; }
</style>
