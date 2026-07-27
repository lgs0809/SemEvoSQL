<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section class="trajectory" v-loading="loading">
    <div class="toolbar">
      <div>
        <h2>查询模式与执行轨迹</h2>
        <p>仅比较同一业务模型版本和执行环境下的历史路径；当前全部为只读观察。</p>
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
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="历史路径分析不会直接改变线上执行；只有经过影子验证和人工批准的优化方案才可能成为后续查询提示。"
    />

    <div class="summary-grid">
      <div class="metric">
        <strong>{{ patterns.length }}</strong>
        <span>查询模式</span>
      </div>
      <div class="metric">
        <strong>{{ detours.length }}</strong>
        <span>绕路信号</span>
      </div>
      <div class="metric">
        <strong>{{ semanticDetours }}</strong>
        <span>语义根因</span>
      </div>
      <div class="metric">
        <strong>{{ runtimeDetours }}</strong>
        <span>运行根因</span>
      </div>
    </div>

    <el-table :data="patterns" empty-text="暂无已分析轨迹" @row-click="openPattern">
      <el-table-column label="查询模式" min-width="260">
        <template #default="scope">
          <strong>{{ intentLabel(scope.row.intent_type) }}</strong>
          <div class="subtle">{{ ambiguityLabel(scope.row.ambiguity_level) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="样本" width="140">
        <template #default="scope">
          {{ scope.row.success_count }} / {{ scope.row.episode_count }} 成功
        </template>
      </el-table-column>
      <el-table-column label="风险" width="110">
        <template #default="scope">
          <el-tag :type="riskType(scope.row.risk_level)">{{ riskLabel(scope.row.risk_level) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最后出现" width="180">
        <template #default="scope">{{ formatTime(scope.row.last_seen_time) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="scope">
          <el-button link type="primary" @click.stop="openPattern(scope.row)">查看路径</el-button>
          <el-button link @click.stop="recompute(scope.row.id)">重算</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="drawerVisible" title="查询路径画像" size="76%">
      <template v-if="detail">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="查询类型">{{ intentLabel(detail.intent_type) }}</el-descriptions-item>
          <el-descriptions-item label="样本">{{ detail.episode_count }}</el-descriptions-item>
          <el-descriptions-item label="成功">{{ detail.success_count }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ patternStatusLabel(detail.status) }}</el-descriptions-item>
        </el-descriptions>
        <h3>候选路径</h3>
        <el-table :data="detail.profiles" empty-text="暂无路径画像">
          <el-table-column prop="sample_count" label="样本" width="80" />
          <el-table-column label="正确/安全" width="150">
            <template #default="scope">
              {{ percent(scope.row.correctness_rate) }} / {{ percent(scope.row.safety_rate) }}
            </template>
          </el-table-column>
          <el-table-column label="覆盖/新鲜/稳定" min-width="210">
            <template #default="scope">
              {{ percent(scope.row.coverage_rate) }} / {{ percent(scope.row.freshness_rate) }} /
              {{ percent(scope.row.stability_rate) }}
            </template>
          </el-table-column>
          <el-table-column label="成本" min-width="190">
            <template #default="scope">
              {{ Math.round(scope.row.avg_latency_ms) }} 毫秒 ·
              {{ Math.round(scope.row.avg_token_count) }} 令牌 · 平均重试
              {{ Number(scope.row.avg_retry_count).toFixed(1) }} 次
            </template>
          </el-table-column>
          <el-table-column label="综合表现" width="120">
            <template #default="scope">
              <el-tag :type="isDominated(scope.row.dominated) ? 'info' : 'success'">
                {{ isDominated(scope.row.dominated) ? `第 ${scope.row.pareto_rank} 档` : '优选路径' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <h3>最近路径</h3>
        <el-table :data="paths" empty-text="暂无路径">
          <el-table-column label="状态" width="110">
            <template #default="scope">
              <el-tag :type="scope.row.status === 'SUCCEEDED' ? 'success' : 'danger'">
                {{ trajectoryStatusLabel(scope.row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="质量" min-width="210">
            <template #default="scope">
              正确 {{ percent(scope.row.correctness_score) }} · 安全
              {{ percent(scope.row.safety_score) }} · 稳定 {{ percent(scope.row.stability_score) }}
            </template>
          </el-table-column>
          <el-table-column label="成本" min-width="190">
            <template #default="scope">
              {{ scope.row.latency_ms || 0 }} 毫秒 · {{ scope.row.token_count || 0 }} 令牌 · 重试
              {{ scope.row.retry_count }} 次
            </template>
          </el-table-column>
        </el-table>
        <h3>绕路信号</h3>
        <el-table :data="detail.detours" empty-text="暂无绕路信号">
          <el-table-column label="信号" min-width="190">
            <template #default="scope">{{ detourSignalLabel(scope.row.signal_type) }}</template>
          </el-table-column>
          <el-table-column label="原因" min-width="180">
            <template #default="scope">{{ detourCauseLabel(scope.row.root_cause) }}</template>
          </el-table-column>
          <el-table-column label="置信度" width="120">
            <template #default="scope">{{ percent(scope.row.confidence) }}</template>
          </el-table-column>
          <el-table-column label="复现" width="130">
            <template #default="scope">
              {{ scope.row.occurrence_count }} 次 / {{ percent(scope.row.recurrence_rate) }}
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref, watch } from 'vue';
  import { ElMessage } from 'element-plus';
  import {
    semEvoSQLService,
    type DetourSignal,
    type QueryPattern,
    type QueryPatternDetail,
    type SemanticProjectVersion,
    type TrajectoryPath,
  } from '@/services/semevosql';
  import { versionStatusLabel } from '@/services/displayLabels';

  const props = defineProps<{
    projectId: number;
    versions: SemanticProjectVersion[];
    activeVersionId?: number;
  }>();
  const selectedVersionId = ref<number>();
  const patterns = ref<QueryPattern[]>([]);
  const detours = ref<DetourSignal[]>([]);
  const detail = ref<QueryPatternDetail>();
  const paths = ref<TrajectoryPath[]>([]);
  const loading = ref(false);
  const drawerVisible = ref(false);
  const semanticDetours = computed(
    () => detours.value.filter(item => item.root_cause === 'SEMANTIC_EVOLUTION').length,
  );
  const runtimeDetours = computed(
    () =>
      detours.value.filter(item =>
        ['RUNTIME_OPTIMIZATION', 'PLANNER_DEFECT'].includes(item.root_cause),
      ).length,
  );

  const preferredVersion = () =>
    props.activeVersionId ||
    props.versions.find(item => item.status === 'PUBLISHED')?.id ||
    props.versions[0]?.id;
  const load = async () => {
    if (!props.projectId) return;
    loading.value = true;
    try {
      [patterns.value, detours.value] = await Promise.all([
        semEvoSQLService.trajectoryPatterns(props.projectId, selectedVersionId.value),
        semEvoSQLService.detourSignals(props.projectId),
      ]);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '轨迹加载失败');
    } finally {
      loading.value = false;
    }
  };
  const openPattern = async (pattern: QueryPattern) => {
    drawerVisible.value = true;
    try {
      [detail.value, paths.value] = await Promise.all([
        semEvoSQLService.trajectoryPattern(pattern.id),
        semEvoSQLService.trajectoryPaths(pattern.id),
      ]);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '路径详情加载失败');
    }
  };
  const recompute = async (patternId: string) => {
    try {
      await semEvoSQLService.recomputeTrajectoryPattern(patternId);
      ElMessage.success('查询路径画像已重算');
      await load();
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '重算失败');
    }
  };
  const formatTime = (value?: string) => (value ? new Date(value).toLocaleString('zh-CN') : '-');
  const percent = (value?: number) => `${(Number(value || 0) * 100).toFixed(0)}%`;
  const riskType = (value: string) =>
    value === 'HIGH' ? 'danger' : value === 'MEDIUM' ? 'warning' : 'success';
  const riskLabel = (value?: string) =>
    ({ LOW: '低', MEDIUM: '中', HIGH: '高' })[value || ''] || '待评估';
  const intentLabel = (value?: string) =>
    ({
      AGGREGATION: '汇总统计',
      COMPARISON: '对比分析',
      TREND: '趋势分析',
      DETAIL: '明细查询',
      RANKING: '排序分析',
      DISTRIBUTION: '分布分析',
    })[value || ''] || '业务查询';
  const ambiguityLabel = (value?: string) =>
    ({ LOW: '低歧义', MEDIUM: '中等歧义', HIGH: '高歧义', NONE: '无明显歧义' })[value || ''] ||
    '歧义程度待确认';
  const patternStatusLabel = (value?: string) =>
    ({ ACTIVE: '有效', STALE: '已过期', DISABLED: '已停用' })[value || ''] || '有效';
  const trajectoryStatusLabel = (value?: string) =>
    ({ SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消' })[value || ''] || '未完成';
  const detourSignalLabel = (value?: string) =>
    ({
      DUPLICATE_NODE: '重复执行阶段',
      POST_EXECUTION_REVIEW: '执行后复核发现问题',
      REPEATED_SQL_REPAIR: '多次 SQL 修复',
      RUNTIME_CLARIFICATION: '运行中需要澄清',
      DECISION_REVERSAL: '规划决策发生反转',
      MULTI_TO_SINGLE_CORRECTION: '多源规划回退为单源',
    })[value || ''] || '异常执行路径';
  const detourCauseLabel = (value?: string) =>
    ({
      SEMANTIC_EVOLUTION: '业务语义需要完善',
      RUNTIME_OPTIMIZATION: '运行路径可优化',
      PLANNER_DEFECT: '查询规划需要改进',
    })[value || ''] || '原因待确认';
  const isDominated = (value: boolean | number) => value === true || value === 1;
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
  .trajectory {
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
  .metric span,
  .subtle {
    color: #64748b;
    font-size: 12px;
  }
  h3 {
    margin: 24px 0 10px;
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
