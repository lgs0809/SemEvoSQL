<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section class="learning-inbox" v-loading="loading">
    <div class="heading">
      <div>
        <h2>改进建议</h2>
        <p>
          系统从真实查询、纠错和运行表现中形成建议。任何项目级改变都不会直接修改当前正式业务模型。
        </p>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-alert
      v-if="error"
      type="warning"
      show-icon
      :closable="false"
      title="部分改进信号加载失败"
      :description="error"
    />

    <div class="summary-grid">
      <div class="summary-item">
        <strong>{{ semanticItems.length }}</strong>
        <span>业务模型 / 项目规则</span>
      </div>
      <div class="summary-item">
        <strong>{{ quarantinedCases.length }}</strong>
        <span>需复核历史案例</span>
      </div>
      <div class="summary-item">
        <strong>{{ optimizationItems.length }}</strong>
        <span>运行优化建议</span>
      </div>
    </div>

    <el-empty v-if="!items.length && !loading" description="当前没有需要处理的改进建议" />

    <el-table v-else :data="items" empty-text="当前没有需要处理的改进建议">
      <el-table-column label="类型" width="130">
        <template #default="scope">
          <el-tag :type="kindType(scope.row.kind)" effect="plain">
            {{ kindLabel(scope.row.kind) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="建议" min-width="280">
        <template #default="scope">
          <strong class="item-title">{{ scope.row.title }}</strong>
          <div class="subtle">{{ scope.row.description }}</div>
        </template>
      </el-table-column>
      <el-table-column label="为什么出现" min-width="300">
        <template #default="scope">
          <span>{{ scope.row.evidence }}</span>
          <div v-if="scope.row.independentEvidence" class="subtle">
            {{ scope.row.independentEvidence }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="风险 / 状态" width="160">
        <template #default="scope">
          <el-tag size="small" :type="riskType(scope.row.risk)">
            {{ riskLabel(scope.row.risk) }}
          </el-tag>
          <div class="subtle state-copy">{{ statusLabel(scope.row.status) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="scope">
          <el-button link type="primary" @click="emit('open', scope.row.target)">
            处理建议
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue';
  import { semEvoSQLService } from '@/services/semevosql';
  import type {
    RuntimeOptimizationCandidate,
    SemanticEvolutionCandidate,
    ValidatedQueryExample,
  } from '@/services/semevosql';

  type InboxTarget = 'semantic' | 'examples' | 'optimization';
  type InboxKind = 'PROJECT_RULE' | 'BUSINESS_MODEL' | 'QUERY_CASE' | 'RUNTIME';

  interface InboxItem {
    id: string;
    kind: InboxKind;
    title: string;
    description: string;
    evidence: string;
    independentEvidence?: string;
    risk: string;
    status: string;
    updateTime: string;
    target: InboxTarget;
  }

  const props = defineProps<{ projectId: number }>();
  const emit = defineEmits<{ open: [target: InboxTarget] }>();
  const semanticCandidates = ref<SemanticEvolutionCandidate[]>([]);
  const quarantinedCases = ref<ValidatedQueryExample[]>([]);
  const optimizationCandidates = ref<RuntimeOptimizationCandidate[]>([]);
  const loading = ref(false);
  const error = ref('');

  const semanticItems = computed(() =>
    semanticCandidates.value
      .filter(item => !['PUBLISHED', 'REJECTED', 'STALE'].includes(item.status))
      .map(toSemanticItem),
  );
  const optimizationItems = computed(() =>
    optimizationCandidates.value
      .filter(item => !['ENABLED', 'DISABLED', 'REJECTED', 'STALE'].includes(item.status))
      .map(toOptimizationItem),
  );
  const items = computed(() =>
    [
      ...semanticItems.value,
      ...quarantinedCases.value.map(toQueryCaseItem),
      ...optimizationItems.value,
    ].sort((a, b) => new Date(b.updateTime).getTime() - new Date(a.updateTime).getTime()),
  );

  const load = async () => {
    loading.value = true;
    error.value = '';
    const results = await Promise.allSettled([
      semEvoSQLService.semanticEvolutionCandidates(props.projectId),
      semEvoSQLService.queryExamples(props.projectId, undefined, 'QUARANTINED'),
      semEvoSQLService.runtimeOptimizationCandidates(props.projectId),
    ]);
    semanticCandidates.value = results[0].status === 'fulfilled' ? results[0].value : [];
    quarantinedCases.value = results[1].status === 'fulfilled' ? results[1].value : [];
    optimizationCandidates.value = results[2].status === 'fulfilled' ? results[2].value : [];
    const failed = results.filter(result => result.status === 'rejected').length;
    if (failed) error.value = `${failed} 类改进信号暂时不可用，其余事实已正常展示。`;
    loading.value = false;
  };

  const toSemanticItem = (candidate: SemanticEvolutionCandidate): InboxItem => {
    const alias = candidate.candidate_type === 'PROJECT_ALIAS_PROPOSAL';
    const operation = firstPatchOperation(candidate.patch_json);
    const values = (operation?.values || {}) as Record<string, unknown>;
    const phrase = text(values.phrase) || candidate.asset_key;
    const businessLabel = text(values.businessLabel) || text(values.businessName);
    const title = alias
      ? businessLabel
        ? `将“${phrase}”提交为“${businessLabel}”的项目规则`
        : `统一项目叫法“${phrase}”`
      : semanticTitle(candidate, operation);
    return {
      id: candidate.id,
      kind: alias ? 'PROJECT_RULE' : 'BUSINESS_MODEL',
      title,
      description: alias
        ? '来自用户明确确认的共享叫法建议，需进入新业务模型版本后才能影响其他用户。'
        : '来自真实查询、纠错或语义证据的业务模型改进建议。',
      evidence: evidenceText(candidate.evidence_summary),
      independentEvidence: independentEvidenceText(candidate),
      risk: candidate.risk_level,
      status: candidate.status,
      updateTime: candidate.update_time,
      target: 'semantic',
    };
  };

  const toQueryCaseItem = (item: ValidatedQueryExample): InboxItem => ({
    id: item.id,
    kind: 'QUERY_CASE',
    title: item.original_question || item.normalized_question || '历史查询案例',
    description: '这个历史案例已被隔离，不会继续作为正常复用案例参与查询。',
    evidence:
      item.quarantine_reason ||
      item.quality_summary ||
      '历史复用后出现质量问题，需要人工确认是否修复、替换或继续隔离。',
    independentEvidence: item.failed_after_recall_count
      ? `${item.failed_after_recall_count} 次复用后失败 · 连续异常 ${item.consecutive_recall_issue_count || 0} 次`
      : undefined,
    risk: 'HIGH',
    status: item.status,
    updateTime: item.update_time,
    target: 'examples',
  });

  const toOptimizationItem = (item: RuntimeOptimizationCandidate): InboxItem => ({
    id: item.id,
    kind: 'RUNTIME',
    title: optimizationTitle(item.optimization_type),
    description: '只影响运行策略，不改变业务定义；启用前仍需经过影子验证和人工审核。',
    evidence:
      item.gateReasons?.join('；') ||
      (item.gatePassed === true
        ? `影子验证已通过${item.costReduction != null ? `，综合成本下降 ${percent(item.costReduction)}` : ''}`
        : '尚未完成可启用的影子验证。'),
    risk: item.risk_level,
    status: item.status,
    updateTime: item.update_time,
    target: 'optimization',
  });

  const firstPatchOperation = (value: string) => {
    try {
      const parsed = JSON.parse(value) as { operations?: Array<Record<string, unknown>> };
      return parsed.operations?.[0];
    } catch {
      return undefined;
    }
  };
  const semanticTitle = (
    candidate: SemanticEvolutionCandidate,
    operation?: Record<string, unknown>,
  ) => {
    const values = (operation?.values || {}) as Record<string, unknown>;
    const businessName = text(values.businessName) || text(values.name);
    const operationName = text(operation?.operation);
    if (businessName) return `${operationLabel(operationName)}“${businessName}”`;
    return `${operationLabel(operationName)}${assetTypeLabel(candidate.asset_type)}“${candidate.asset_key}”`;
  };
  const evidenceText = (value: string) => {
    if (!value) return '已有真实使用证据，详情中可查看完整来源。';
    try {
      const parsed = JSON.parse(value) as Record<string, unknown>;
      const phrase = text(parsed.phrase);
      const label = text(parsed.businessLabel);
      if (phrase && label) return `用户明确确认“${phrase}”表示“${label}”。`;
      return (
        text(parsed.summary) || text(parsed.reason) || '已有结构化证据，详情中可查看完整来源。'
      );
    } catch {
      return value;
    }
  };
  const independentEvidenceText = (item: SemanticEvolutionCandidate) => {
    const facts = [
      item.distinct_user_count ? `${item.distinct_user_count} 个用户` : '',
      item.distinct_conversation_count ? `${item.distinct_conversation_count} 个会话` : '',
      item.distinct_root_evidence_count ? `${item.distinct_root_evidence_count} 条独立证据` : '',
    ].filter(Boolean);
    return facts.join(' · ') || undefined;
  };
  const text = (value: unknown) => (typeof value === 'string' ? value.trim() : '');
  const operationLabel = (operation: string) => {
    if (operation.startsWith('ADD')) return '新增';
    if (operation.startsWith('UPDATE')) return '修改';
    if (operation.startsWith('DELETE') || operation.startsWith('REMOVE')) return '停用';
    return '调整';
  };
  const assetTypeLabel = (type: string) => {
    const labels: Record<string, string> = {
      METRIC: '指标',
      DIMENSION: '维度',
      ENUM_VALUE: '枚举',
      RELATIONSHIP: '关系',
      GRAIN: '粒度',
      RULE: '业务规则',
      PROJECT_ALIAS: '项目规则',
    };
    return labels[type] || '业务模型';
  };
  const optimizationTitle = (type: string) => {
    const labels: Record<string, string> = {
      PREFERRED_PLAN: '优先使用更稳定的查询路径',
      PATH_PREFERENCE: '调整查询路径优先级',
      RETRIEVAL_HINT: '优化检索提示',
      PLANNER_HINT: '优化查询规划提示',
    };
    return labels[type] || '优化查询运行策略';
  };
  const kindLabel = (kind: InboxKind) => {
    if (kind === 'PROJECT_RULE') return '项目规则';
    if (kind === 'BUSINESS_MODEL') return '业务模型';
    if (kind === 'QUERY_CASE') return '历史案例';
    return '运行优化';
  };
  const kindType = (kind: InboxKind) => {
    if (kind === 'PROJECT_RULE') return 'primary';
    if (kind === 'BUSINESS_MODEL') return 'warning';
    if (kind === 'QUERY_CASE') return 'danger';
    return 'info';
  };
  const statusLabel = (status: string) => {
    const labels: Record<string, string> = {
      CANDIDATE: '待处理',
      REVIEWED: '已审核',
      APPROVED: '已批准',
      DRAFT_CREATED: '已创建变更草稿',
      APPLIED: '已应用到草稿',
      PATCH_APPLIED: '已应用到草稿',
      REPLAYED: '已完成自动回归',
      REPLAY_RUNNING: '正在回归验证',
      REPLAY_PASSED: '回归验证通过',
      REPLAY_FAILED: '回归验证未通过',
      READY: '等待发布',
      READY_FOR_PUBLISH: '等待发布决定',
      QUARANTINED: '已隔离',
      SHADOW: '影子验证中',
      DEGRADED: '效果下降',
    };
    return labels[status] || status;
  };
  const riskLabel = (risk: string) => {
    if (risk === 'HIGH') return '高风险';
    if (risk === 'MEDIUM') return '中风险';
    if (risk === 'LOW') return '低风险';
    return risk || '待评估';
  };
  const riskType = (risk: string) => {
    if (risk === 'HIGH') return 'danger';
    if (risk === 'MEDIUM') return 'warning';
    return 'success';
  };
  const percent = (value: number) => `${(value * 100).toFixed(1)}%`;

  onMounted(load);
</script>

<style scoped>
  .learning-inbox {
    display: flex;
    flex-direction: column;
    gap: 18px;
  }
  .heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 18px;
  }
  .heading h2 {
    margin: 0 0 6px;
    color: #0f172a;
  }
  .heading p {
    margin: 0;
    color: #64748b;
    line-height: 1.6;
  }
  .summary-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 12px;
  }
  .summary-item {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 14px 16px;
    border: 1px solid #e2e8f0;
    border-radius: 12px;
    background: #f8fafc;
  }
  .summary-item strong {
    color: #0f172a;
    font-size: 24px;
  }
  .summary-item span,
  .subtle {
    color: #64748b;
    font-size: 12px;
  }
  .item-title {
    color: #0f172a;
  }
  .state-copy {
    margin-top: 6px;
  }
  @media (max-width: 760px) {
    .heading {
      flex-direction: column;
    }
    .summary-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
