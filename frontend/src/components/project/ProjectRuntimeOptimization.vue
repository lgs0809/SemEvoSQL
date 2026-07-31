<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section class="optimization" v-loading="loading">
    <div class="toolbar">
      <div>
        <h2>运行优化</h2>
        <p>优化建议先经过影子验证；至少 5 个样本、质量不下降且综合成本下降 30% 后才能人工启用。</p>
      </div>
      <div class="filters">
        <el-select v-model="status" placeholder="筛选状态" clearable @change="load">
          <el-option label="全部状态" value="" />
          <el-option
            v-for="item in statuses"
            :key="item"
            :label="statusLabel(item)"
            :value="item"
          />
        </el-select>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="启用后的优化只影响查询执行起点，不会改变业务口径；每次查询仍会重新经过现有安全与成本控制。"
    />

    <el-table :data="candidates" empty-text="暂无运行优化候选">
      <el-table-column label="优化建议" min-width="280">
        <template #default="scope">
          <strong>{{ optimizationTitle(scope.row.optimization_type) }}</strong>
          <div class="subtle">只影响查询执行策略，不改变业务口径</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="140">
        <template #default="scope">
          <el-tag :type="statusType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="证据强度 / 风险" width="170">
        <template #default="scope">
          {{ percent(scope.row.confidence) }}
          <div>
            <el-tag size="small" :type="riskType(scope.row.risk_level)">
              {{ riskLabel(scope.row.risk_level) }}
            </el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="影子验证" min-width="180">
        <template #default="scope">
          <span v-if="scope.row.gatePassed === true" class="passed">
            通过 · 降本 {{ percent(scope.row.costReduction) }}
          </span>
          <span v-else-if="scope.row.gatePassed === false" class="failed">未通过</span>
          <span v-else class="subtle">尚未评估</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="280" fixed="right">
        <template #default="scope">
          <el-button link type="primary" @click="open(scope.row)">详情</el-button>
          <el-button
            v-if="canGovern && ['CANDIDATE', 'SHADOW'].includes(scope.row.status)"
            link
            @click="shadow(scope.row)"
          >
            记录影子验证
          </el-button>
          <el-button
            v-if="canGovern && scope.row.status === 'SHADOW'"
            link
            type="success"
            @click="approve(scope.row)"
          >
            批准
          </el-button>
          <el-button
            v-if="canGovern && ['CANDIDATE', 'SHADOW'].includes(scope.row.status)"
            link
            type="danger"
            @click="reject(scope.row)"
          >
            拒绝
          </el-button>
          <el-button
            v-if="canGovern && scope.row.status === 'APPROVED'"
            link
            type="success"
            @click="enable(scope.row)"
          >
            启用
          </el-button>
          <el-button
            v-if="canGovern && ['ENABLED', 'APPROVED', 'SHADOW'].includes(scope.row.status)"
            link
            type="warning"
            @click="disable(scope.row)"
          >
            停用
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="drawerVisible" title="运行优化门禁" size="68%">
      <template v-if="selected">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="类型">
            {{ optimizationTitle(selected.optimization_type) }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusLabel(selected.status) }}</el-descriptions-item>
          <el-descriptions-item label="门禁状态">
            <el-tag :type="selected.gatePassed ? 'success' : 'danger'">
              {{ selected.gatePassed ? '通过' : '未通过' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="降本">
            {{ percent(selected.costReduction) }}
          </el-descriptions-item>
        </el-descriptions>
        <el-alert
          v-if="selected.gateReasons?.length"
          type="warning"
          :closable="false"
          :title="selected.gateReasons.join('；')"
        />
        <h3>适用条件</h3>
        <pre>{{ pretty(selected.applicability_json) }}</pre>
        <h3>起始提示提案</h3>
        <pre>{{ pretty(selected.proposal_json) }}</pre>
        <h3>基线指标</h3>
        <pre>{{ pretty(selected.baseline_metrics_json) }}</pre>
        <h3>影子验证指标</h3>
        <pre>{{ pretty(selected.shadow_metrics_json) }}</pre>
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { semEvoSQLService, type RuntimeOptimizationCandidate } from '@/services/semevosql';

  const props = defineProps<{ projectId: number; canGovern?: boolean }>();
  const canGovern = computed(() => props.canGovern !== false);
  const candidates = ref<RuntimeOptimizationCandidate[]>([]);
  const selected = ref<RuntimeOptimizationCandidate>();
  const status = ref('');
  const loading = ref(false);
  const drawerVisible = ref(false);
  const statuses = [
    'CANDIDATE',
    'SHADOW',
    'APPROVED',
    'ENABLED',
    'DEGRADED',
    'DISABLED',
    'REJECTED',
    'STALE',
  ];

  const load = async () => {
    loading.value = true;
    try {
      const base = await semEvoSQLService.runtimeOptimizationCandidates(
        props.projectId,
        status.value,
      );
      candidates.value = await Promise.all(
        base.map(async item => {
          try {
            return await semEvoSQLService.runtimeOptimizationCandidate(item.id);
          } catch {
            return item;
          }
        }),
      );
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '运行优化加载失败');
    } finally {
      loading.value = false;
    }
  };
  const open = async (candidate: RuntimeOptimizationCandidate) => {
    drawerVisible.value = true;
    try {
      selected.value = await semEvoSQLService.runtimeOptimizationCandidate(candidate.id);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '详情加载失败');
    }
  };
  const shadow = async (candidate: RuntimeOptimizationCandidate) => {
    try {
      const response = await ElMessageBox.prompt(
        '请输入影子验证指标 JSON。必须包含样本数、正确性/安全性等质量指标和成本指标。',
        '记录影子验证',
        {
          inputType: 'textarea',
          inputValue: JSON.stringify(
            {
              sampleCount: 5,
              correctnessRate: 1,
              safetyRate: 1,
              coverageRate: 1,
              freshnessRate: 1,
              stabilityRate: 1,
              avgLatencyMs: 0,
              avgTokenCount: 0,
              avgRetryCount: 0,
              avgClarificationCount: 0,
            },
            null,
            2,
          ),
          inputValidator: value => {
            try {
              JSON.parse(value);
              return true;
            } catch {
              return '请输入合法 JSON';
            }
          },
        },
      );
      await semEvoSQLService.recordOptimizationShadow(candidate.id, JSON.parse(response.value));
      ElMessage.success('影子验证指标已记录，已重新执行门禁');
      await load();
    } catch (error) {
      if (error instanceof Error) ElMessage.error(error.message);
    }
  };
  const approve = async (candidate: RuntimeOptimizationCandidate) => {
    try {
      await ElMessageBox.confirm('批准前端操作不会绕过后端 Shadow Gate。', '批准运行优化', {
        type: 'warning',
      });
      await semEvoSQLService.approveRuntimeOptimization(candidate.id, '已完成影子验证门禁复核');
      ElMessage.success('候选已批准');
      await load();
    } catch (error) {
      if (error instanceof Error) ElMessage.error(error.message);
    }
  };
  const reject = async (candidate: RuntimeOptimizationCandidate) => {
    try {
      const response = await ElMessageBox.prompt('说明拒绝原因。', '拒绝运行优化', {
        inputType: 'textarea',
        inputValidator: value => Boolean(value.trim()) || '必须填写原因',
      });
      await semEvoSQLService.rejectRuntimeOptimization(candidate.id, response.value);
      ElMessage.success('候选已拒绝');
      await load();
    } catch (error) {
      if (error instanceof Error) ElMessage.error(error.message);
    }
  };
  const enable = async (candidate: RuntimeOptimizationCandidate) => {
    try {
      await ElMessageBox.confirm(
        '启用后仅作为精确适用时的 Planner 起始提示，所有安全控制仍生效。',
        '启用 Preferred Plan',
        { type: 'warning' },
      );
      await semEvoSQLService.enableRuntimeOptimization(candidate.id);
      ElMessage.success('Preferred Plan 已启用');
      await load();
    } catch (error) {
      if (error instanceof Error) ElMessage.error(error.message);
    }
  };
  const disable = async (candidate: RuntimeOptimizationCandidate) => {
    try {
      const response = await ElMessageBox.prompt('说明停用或降级原因。', '停用运行优化', {
        inputType: 'textarea',
        inputValidator: value => Boolean(value.trim()) || '必须填写原因',
      });
      await semEvoSQLService.disableRuntimeOptimization(
        candidate.id,
        response.value,
        candidate.status === 'ENABLED',
      );
      ElMessage.success('运行优化已停用');
      await load();
    } catch (error) {
      if (error instanceof Error) ElMessage.error(error.message);
    }
  };
  const optimizationTitle = (value: string) => {
    const labels: Record<string, string> = {
      PREFERRED_PLAN: '优先使用更稳定的查询路径',
      PATH_PREFERENCE: '调整查询路径优先级',
      RETRIEVAL_HINT: '优化检索提示',
      PLANNER_HINT: '优化查询规划提示',
    };
    return labels[value] || '优化查询运行策略';
  };
  const statusLabel = (value: string) => {
    const labels: Record<string, string> = {
      CANDIDATE: '待验证',
      SHADOW: '影子验证中',
      APPROVED: '已批准',
      ENABLED: '已启用',
      DEGRADED: '效果下降',
      DISABLED: '已停用',
      REJECTED: '已拒绝',
      STALE: '已过期',
    };
    return labels[value] || value;
  };
  const riskLabel = (value: string) => {
    if (value === 'HIGH') return '高风险';
    if (value === 'MEDIUM') return '中风险';
    if (value === 'LOW') return '低风险';
    return value || '待评估';
  };
  const statusType = (value: string) =>
    value === 'ENABLED'
      ? 'success'
      : ['REJECTED', 'DEGRADED'].includes(value)
        ? 'danger'
        : ['DISABLED', 'STALE'].includes(value)
          ? 'info'
          : 'warning';
  const riskType = (value: string) =>
    value === 'HIGH' ? 'danger' : value === 'MEDIUM' ? 'warning' : 'success';
  const percent = (value?: number) => `${(Number(value || 0) * 100).toFixed(0)}%`;
  const pretty = (value?: string) => {
    try {
      return JSON.stringify(JSON.parse(value || '{}'), null, 2);
    } catch {
      return value || '-';
    }
  };
  onMounted(load);
</script>

<style scoped>
  .optimization {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }
  .toolbar {
    display: flex;
    justify-content: space-between;
    gap: 20px;
    align-items: flex-start;
  }
  .toolbar h2 {
    margin: 0 0 6px;
    color: #0f172a;
  }
  .toolbar p {
    margin: 0;
    color: #64748b;
  }
  .filters {
    display: flex;
    gap: 10px;
  }
  .filters .el-select {
    width: 180px;
  }
  .subtle {
    margin-top: 5px;
    color: #94a3b8;
    font-size: 12px;
  }
  .passed {
    color: #15803d;
  }
  .failed {
    color: #b91c1c;
  }
  code {
    white-space: normal;
    word-break: break-all;
    font-size: 12px;
  }
  pre {
    padding: 14px;
    overflow: auto;
    border-radius: 10px;
    background: #f8fafc;
    white-space: pre-wrap;
    word-break: break-word;
  }
  h3 {
    margin: 24px 0 10px;
  }
  @media (max-width: 900px) {
    .toolbar {
      flex-direction: column;
    }
  }
</style>
