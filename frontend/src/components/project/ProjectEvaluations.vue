<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section class="evaluations" v-loading="loading">
    <div class="toolbar">
      <div>
        <h2>项目测试</h2>
        <p>对启用的验证用例执行真实回放，分别查看任务进度、预期断言和实际检查结果。</p>
      </div>
      <div class="filters">
        <el-select v-model="selectedVersionId" placeholder="选择版本" :disabled="loading || submitting" @change="load">
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
          :disabled="!selectedVersionId || loading || enabledCases.length === 0 || runningJobs > 0"
          :loading="submitting"
          @click="createReplay"
        >
          {{ runningJobs > 0 ? '回归进行中' : '运行自动回归' }}
        </el-button>
        <el-button :disabled="loading || submitting" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
    <el-alert v-else-if="!loading && enabledCases.length === 0" title="本项目还没有启用的验证用例。请先准备并核对预期结果，再开始自动回归。" type="warning" :closable="false" show-icon />

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
        <strong>{{ enabledCases.length }}</strong>
        <span>启用用例</span>
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
        <el-table class="desktop-table" :data="goldenCases" empty-text="暂无验证用例">
          <el-table-column prop="case_code" label="用例编号" min-width="180" />
          <el-table-column label="回放方式" width="160">
            <template #default="scope">
              <el-tag>{{ replayModeLabel(scope.row.replay_mode) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="question" label="问题" min-width="280" show-overflow-tooltip />
          <el-table-column label="预期检查条件" min-width="260">
            <template #default="scope">
              <details v-if="storedObject(scope.row.expected_json)"><summary>查看预期断言</summary><pre>{{ JSON.stringify(storedObject(scope.row.expected_json), null, 2) }}</pre></details>
              <span v-else>断言暂不能读取，需要核对用例</span>
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
        <div class="mobile-records">
          <el-empty v-if="!goldenCases.length" description="暂无验证用例" :image-size="70" />
          <article v-for="(item, index) in goldenCases" :key="item.case_code || index" class="mobile-record">
            <h3>{{ item.question || '未记录问题' }}</h3>
            <p class="record-code">{{ item.case_code || '未记录编号' }}</p>
            <div class="record-tags"><el-tag>{{ replayModeLabel(item.replay_mode) }}</el-tag><el-tag :type="item.enabled ? 'success' : 'info'">{{ item.enabled ? '已启用' : '未启用' }}</el-tag></div>
            <details v-if="storedObject(item.expected_json)"><summary>查看预期检查条件</summary><pre>{{ JSON.stringify(storedObject(item.expected_json), null, 2) }}</pre></details>
          </article>
        </div>
      </el-tab-pane>
      <el-tab-pane label="回归任务" name="jobs">
        <el-table class="desktop-table" :data="replayJobs" empty-text="暂无回归任务">
          <el-table-column label="类型" width="130">
            <template #default>自动回归</template>
          </el-table-column>
          <el-table-column label="任务状态" width="130">
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
              <template v-if="scope.row.status === 'SUCCEEDED'">
                <el-tag :type="replayVerdict(scope.row).type">{{ replayVerdict(scope.row).label }}</el-tag>
                <p>{{ replayVerdict(scope.row).summary }}</p>
                <el-button link type="primary" @click="showResult(scope.row)">查看检查结果</el-button>
              </template>
              <template v-else>
                <span>{{ scope.row.error_message || '任务结束后显示检查结果' }}</span>
                <el-button v-if="scope.row.result_json" link type="primary" @click="showResult(scope.row)">查看已保存记录</el-button>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="scope">{{ formatTime(scope.row.create_time) }}</template>
          </el-table-column>
        </el-table>
        <div class="mobile-records">
          <el-empty v-if="!replayJobs.length" description="暂无回归任务" :image-size="70" />
          <article v-for="(job, index) in replayJobs" :key="job.id || index" class="mobile-record">
            <header><h3>自动回归 · v{{ versionNumber(job.project_version_id) }}</h3><el-tag :type="jobType(job.status)">{{ jobStatusLabel(job.status) }}</el-tag></header>
            <p>{{ formatTime(job.create_time) }}</p>
            <el-progress :percentage="progress(job)" />
            <template v-if="job.status === 'SUCCEEDED'">
              <div class="record-tags"><el-tag :type="replayVerdict(job).type">{{ replayVerdict(job).label }}</el-tag></div>
              <p>{{ replayVerdict(job).summary }}</p>
              <el-button type="primary" plain @click="showResult(job)">查看检查结果</el-button>
            </template>
            <template v-else>
              <p>{{ job.error_message || '任务结束后显示检查结果' }}</p>
              <el-button v-if="job.result_json" plain @click="showResult(job)">查看已保存记录</el-button>
            </template>
          </article>
        </div>
      </el-tab-pane>
    </el-tabs>
    <ReplayEvidenceDrawer v-model="resultOpen" :job="selectedJob" :golden-cases="goldenCases" />
  </section>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref, watch } from 'vue';
  import { ElMessage } from 'element-plus';
  import { semEvoSQLService, type SemanticProjectVersion } from '@/services/semevosql';
  import { versionStatusLabel } from '@/services/displayLabels';
  import { replayModeLabel, replayVerdict, storedObject } from '@/utils/evaluation-presentation';
  import ReplayEvidenceDrawer from './ReplayEvidenceDrawer.vue';

  const props = defineProps<{
    projectId: number;
    versions: SemanticProjectVersion[];
    activeVersionId?: number;
    canRun?: boolean;
  }>();
  const canRun = computed(() => props.canRun !== false);
  interface GoldenCaseRow {
    case_code?: string;
    replay_mode?: string;
    question?: string;
    expected_json?: unknown;
    enabled?: boolean;
  }

  interface ReplayJobRow {
    id?: string;
    job_type?: string;
    status?: string;
    project_version_id?: number;
    result_summary?: string;
    result_json?: unknown;
    error_message?: string;
    create_time?: string;
    progress?: number | string;
    progress_percent?: number | string;
  }

  const selectedVersionId = ref<number>();
  const goldenCases = ref<GoldenCaseRow[]>([]);
  const replayJobs = ref<ReplayJobRow[]>([]);
  const loading = ref(false);
  const submitting = ref(false);
  const loadError = ref('');
  let replayKey: string | undefined;
  let loadGeneration = 0;
  const tab = ref('cases');
  const resultOpen = ref(false);
  const selectedJob = ref<ReplayJobRow>();
  const showResult = (job: ReplayJobRow) => { selectedJob.value = job; resultOpen.value = true; };
  const enabledCases = computed(() => goldenCases.value.filter(item => item.enabled));
  const runningJobs = computed(
    () => replayJobs.value.filter(item => ['PENDING', 'QUEUED', 'RUNNING'].includes(item.status ?? '')).length,
  );
  const preferredVersion = () =>
    props.activeVersionId ||
    props.versions.find(item => item.status === 'PUBLISHED')?.id ||
    props.versions[0]?.id;

  const load = async () => {
    if (!selectedVersionId.value) return;
    const generation = ++loadGeneration;
    const versionId = selectedVersionId.value;
    loading.value = true;
    loadError.value = '';
    try {
      const [cases, jobs] = await Promise.all([
        semEvoSQLService.goldenCases(props.projectId),
        semEvoSQLService.jobs(props.projectId),
      ]);
      if (generation !== loadGeneration) return;
      goldenCases.value = cases;
      replayJobs.value = jobs.filter(
        (item: ReplayJobRow) => item.job_type === 'REPLAY' && item.project_version_id === versionId,
      );
    } catch (error) {
      if (generation !== loadGeneration) return;
      loadError.value = error instanceof Error ? error.message : '评估数据加载失败，请重试';
    } finally {
      if (generation === loadGeneration) loading.value = false;
    }
  };
  const createReplay = async () => {
    if (!selectedVersionId.value || loading.value || submitting.value || runningJobs.value || !enabledCases.value.length) return;
    submitting.value = true;
    replayKey ||= `ui-replay-${selectedVersionId.value}-${crypto.randomUUID()}`;
    try {
      await semEvoSQLService.createReplay(props.projectId, selectedVersionId.value, replayKey);
      replayKey = undefined;
      ElMessage.success('自动回归任务已创建');
      tab.value = 'jobs';
      await load();
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '自动回归创建失败');
    } finally {
      submitting.value = false;
    }
  };
  const progress = (job: ReplayJobRow) => {
    if (job.status === 'SUCCEEDED') return 100;
    if (job.status === 'FAILED' || job.status === 'CANCELLED') return 100;
    return Math.max(0, Math.min(99, Number(job.progress || job.progress_percent || 0)));
  };
  const jobType = (status?: string) =>
    status === 'FAILED'
        ? 'danger'
        : status === 'RUNNING'
          ? 'warning'
          : 'info';
  const jobStatusLabel = (status?: string) => {
    const labels: Record<string, string> = {
      QUEUED: '等待执行',
      RUNNING: '正在执行',
      PENDING: '等待执行',
      SUCCEEDED: '执行完成',
      FAILED: '执行失败',
      CANCELLED: '已取消',
    };
    return labels[status || ''] || status || '未知';
  };
  watch(selectedVersionId, () => { replayKey = undefined; resultOpen.value = false; selectedJob.value = undefined; });
  // Vue owns timer cleanup. Reading progress never recreates the backend job.
  watch(runningJobs, (count, _previous, onCleanup) => {
    if (!count) return;
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible' && !loading.value && !submitting.value) void load();
    }, 5000);
    onCleanup(() => window.clearInterval(timer));
  });
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
    flex-wrap: wrap;
    min-width: 0;
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
  pre { max-width: 100%; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; }
  .mobile-records { display: none; }
  @media (max-width: 900px) {
    .toolbar {
      flex-direction: column;
    }
    .summary-grid {
      grid-template-columns: repeat(2, 1fr);
    }
  }
  @media (max-width: 640px) {
    .desktop-table { display: none; }
    .mobile-records { display: grid; gap: 12px; }
    .mobile-record { min-width: 0; padding: 16px; border: 1px solid var(--el-border-color-light); border-radius: 12px; }
    .mobile-record h3 { margin: 0; font-size: 15px; line-height: 1.6; }
    .mobile-record p { color: #64748b; font-size: 13px; }
    .mobile-record header { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
    .record-code { overflow-wrap: anywhere; }
    .record-tags { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0; }
    .mobile-record summary { cursor: pointer; color: var(--el-color-primary); }
    .filters { width: 100%; }
    .filters .el-select { width: 100%; }
  }
</style>
