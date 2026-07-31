<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section class="evaluations" v-loading="loading">
    <div class="toolbar">
      <div>
        <h2>项目测试</h2>
        <p>使用验证用例和自动回归结果，覆盖正向、负向、边界、安全、新鲜度、多源与性能场景。</p>
      </div>
      <div class="filters">
        <el-select v-model="selectedVersionId" placeholder="选择版本" @change="load">
          <el-option
            v-for="version in versions"
            :key="version.id"
            :label="`${version.versionNumber} · ${versionStatusLabel(version.status)}`"
            :value="version.id"
          />
        </el-select>
        <el-button
          v-if="canRun"
          type="primary"
          :disabled="!selectedVersionId"
          @click="createReplay"
        >
          运行自动回归
        </el-button>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="业务模型改进和运行优化进入下一状态前，应至少通过核心用例、负向、安全、边界、多源和性能回归。"
    />

    <div class="summary-grid">
      <div class="metric">
        <strong>{{ goldenCases.length }}</strong>
        <span>验证用例</span>
      </div>
      <div class="metric">
        <strong>{{ caseTypes.size }}</strong>
        <span>场景类型</span>
      </div>
      <div class="metric">
        <strong>{{ replayJobs.length }}</strong>
        <span>回归任务</span>
      </div>
      <div class="metric">
        <strong>{{ runningJobs }}</strong>
        <span>运行中</span>
      </div>
    </div>

    <el-tabs v-model="tab">
      <el-tab-pane label="验证用例" name="cases">
        <el-table :data="goldenCases" empty-text="暂无验证用例">
          <el-table-column prop="case_name" label="名称" min-width="220" />
          <el-table-column prop="case_type" label="类型" width="150">
            <template #default="scope">
              <el-tag>{{ caseTypeLabel(scope.row.case_type) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="question" label="问题" min-width="280" show-overflow-tooltip />
          <el-table-column label="SQL" min-width="260">
            <template #default="scope">
              <code>{{ scope.row.expected_sql || '-' }}</code>
            </template>
          </el-table-column>
          <el-table-column label="启用" width="90">
            <template #default="scope">
              <el-tag :type="scope.row.enabled ? 'success' : 'info'">
                {{ scope.row.enabled ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="回归任务" name="jobs">
        <el-table :data="replayJobs" empty-text="暂无回归任务">
          <el-table-column label="类型" width="130">
            <template #default>自动回归</template>
          </el-table-column>
          <el-table-column label="状态" width="130">
            <template #default="scope">
              <el-tag :type="jobType(scope.row.status)">
                {{ jobStatusLabel(scope.row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="版本" width="100">
            <template #default="scope">v{{ versionNumber(scope.row.project_version_id) }}</template>
          </el-table-column>
          <el-table-column label="进度" min-width="230">
            <template #default="scope"><el-progress :percentage="progress(scope.row)" /></template>
          </el-table-column>
          <el-table-column label="结果" min-width="260">
            <template #default="scope">
              <code>{{ scope.row.result_summary || scope.row.error_message || '-' }}</code>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="scope">{{ formatTime(scope.row.create_time) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref, watch } from 'vue';
  import { ElMessage } from 'element-plus';
  import { semEvoSQLService, type SemanticProjectVersion } from '@/services/semevosql';
  import { versionStatusLabel } from '@/services/displayLabels';

  const props = defineProps<{
    projectId: number;
    versions: SemanticProjectVersion[];
    activeVersionId?: number;
    canRun?: boolean;
  }>();
  const canRun = computed(() => props.canRun !== false);
  interface GoldenCaseRow {
    case_name?: string;
    case_type?: string;
    question?: string;
    expected_sql?: string;
    enabled?: boolean;
  }

  interface ReplayJobRow {
    job_type?: string;
    status?: string;
    project_version_id?: number;
    result_summary?: string;
    error_message?: string;
    create_time?: string;
    progress?: number | string;
    progress_percent?: number | string;
  }

  const selectedVersionId = ref<number>();
  const goldenCases = ref<GoldenCaseRow[]>([]);
  const replayJobs = ref<ReplayJobRow[]>([]);
  const loading = ref(false);
  const tab = ref('cases');
  const caseTypes = computed(
    () => new Set(goldenCases.value.map(item => item.case_type).filter(Boolean)),
  );
  const runningJobs = computed(
    () => replayJobs.value.filter(item => ['QUEUED', 'RUNNING'].includes(item.status ?? '')).length,
  );
  const preferredVersion = () =>
    props.activeVersionId ||
    props.versions.find(item => item.status === 'PUBLISHED')?.id ||
    props.versions[0]?.id;

  const load = async () => {
    if (!selectedVersionId.value) return;
    loading.value = true;
    try {
      [goldenCases.value, replayJobs.value] = await Promise.all([
        semEvoSQLService.goldenCases(props.projectId),
        semEvoSQLService.jobs(props.projectId),
      ]);
      replayJobs.value = replayJobs.value.filter(
        item => item.job_type === 'REPLAY' && item.project_version_id === selectedVersionId.value,
      );
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '评估数据加载失败');
    } finally {
      loading.value = false;
    }
  };
  const createReplay = async () => {
    if (!selectedVersionId.value) return;
    try {
      await semEvoSQLService.createReplay(props.projectId, selectedVersionId.value);
      ElMessage.success('自动回归任务已创建');
      tab.value = 'jobs';
      await load();
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '自动回归创建失败');
    }
  };
  const progress = (job: ReplayJobRow) => {
    if (job.status === 'SUCCEEDED') return 100;
    if (job.status === 'FAILED' || job.status === 'CANCELLED') return 100;
    return Math.max(0, Math.min(99, Number(job.progress || job.progress_percent || 0)));
  };
  const jobType = (status?: string) =>
    status === 'SUCCEEDED'
      ? 'success'
      : status === 'FAILED'
        ? 'danger'
        : status === 'RUNNING'
          ? 'warning'
          : 'info';
  const jobStatusLabel = (status?: string) => {
    const labels: Record<string, string> = {
      QUEUED: '等待执行',
      RUNNING: '正在执行',
      SUCCEEDED: '已通过',
      FAILED: '未通过',
      CANCELLED: '已取消',
    };
    return labels[status || ''] || status || '未知';
  };
  const caseTypeLabel = (value?: string) =>
    ({
      POSITIVE: '正向查询',
      NEGATIVE: '负向查询',
      BOUNDARY: '边界场景',
      SECURITY: '安全场景',
      FRESHNESS: '时效场景',
      MULTI_SOURCE: '多数据源',
      PERFORMANCE: '性能场景',
    })[String(value || '').toUpperCase()] || value || '未分类场景';
  const versionNumber = (versionId?: number) =>
    props.versions.find(item => item.id === versionId)?.versionNumber || '未知';
  const formatTime = (value?: string) => (value ? new Date(value).toLocaleString('zh-CN') : '-');
  watch(
    () => [props.activeVersionId, props.versions.length],
    () => {
      if (!selectedVersionId.value) selectedVersionId.value = preferredVersion();
      void load();
    },
  );
  onMounted(() => {
    selectedVersionId.value = preferredVersion();
    void load();
  });
</script>

<style scoped>
  .evaluations {
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
    width: 220px;
  }
  .summary-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 12px;
  }
  .metric {
    padding: 16px;
    border: 1px solid #e2e8f0;
    border-radius: 12px;
    background: #fff;
  }
  .metric strong {
    display: block;
    font-size: 26px;
    color: #0f172a;
  }
  .metric span {
    color: #64748b;
    font-size: 12px;
  }
  code {
    white-space: normal;
    word-break: break-all;
    font-size: 12px;
  }
  @media (max-width: 900px) {
    .toolbar {
      flex-direction: column;
    }
    .summary-grid {
      grid-template-columns: repeat(2, 1fr);
    }
  }
</style>
