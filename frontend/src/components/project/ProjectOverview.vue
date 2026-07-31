<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <div v-loading="loading" class="project-overview">
    <el-alert
      v-if="error"
      type="warning"
      show-icon
      :closable="false"
      title="项目健康信息暂时不可用"
      :description="error"
    />

    <template v-if="health">
      <section class="status-grid">
        <article class="status-card primary">
          <span>查询入口</span>
          <strong>{{ health.queryReady ? '已就绪' : '待发布模型' }}</strong>
          <p>
            {{
              health.queryReady
                ? `正式业务模型 v${health.activeVersion?.versionNumber || '-'} 已激活，可直接创建查询会话`
                : '完成业务理解、验证并发布正式版本后开放查询入口'
            }}
          </p>
        </article>
        <article class="status-card">
          <span>业务模型状态</span>
          <strong>{{ understandingLabel }}</strong>
          <p>{{ understandingDetail }}</p>
        </article>
        <article class="status-card">
          <span>查询质量</span>
          <strong>{{ qualityLabel }}</strong>
          <p>{{ qualityDetail }}</p>
        </article>
        <article class="status-card">
          <span>数据时效</span>
          <strong>{{ freshnessLabel }}</strong>
          <p>{{ freshnessDetail }}</p>
        </article>
      </section>

      <section class="overview-section">
        <div class="section-heading">
          <div>
            <h2>查询质量</h2>
            <p>
              最近 {{ health.quality.windowDays }} 天的真实执行与用户反馈事实，不做模型主观打分。
            </p>
          </div>
          <el-button v-if="props.canReview" link type="primary" @click="emit('navigate', 'test')">
            进入测试
          </el-button>
        </div>
        <div class="metric-grid">
          <div class="metric-item">
            <span>查询成功率</span>
            <strong>{{ qualityPercent(health.quality.querySuccessRate) }}</strong>
            <small>
              {{ health.quality.succeededQueries }} / {{ health.quality.totalQueries }} 次查询成功
            </small>
          </div>
          <div class="metric-item north-star">
            <span>无纠错成功答案率</span>
            <strong>{{ qualityPercent(health.quality.correctionFreeSuccessfulAnswerRate) }}</strong>
            <small>成功且没有收到明确纠错；不代表用户已确认正确</small>
          </div>
          <div class="metric-item trusted">
            <span>明确确认可信率</span>
            <strong>{{ qualityPercent(health.quality.confirmedTrustedAnswerRate) }}</strong>
            <small>{{ health.quality.confirmedTrustedAnswerCount }} 次由用户明确确认</small>
          </div>
          <div class="metric-item">
            <span>澄清率</span>
            <strong>{{ qualityPercent(health.quality.clarificationRate) }}</strong>
            <small>{{ health.quality.clarifiedRunCount }} 次查询需要确认业务含义</small>
          </div>
          <div class="metric-item">
            <span>纠错率</span>
            <strong>{{ qualityPercent(health.quality.correctionRate) }}</strong>
            <small>{{ health.quality.correctionCount }} 次明确指出理解错误</small>
          </div>
          <div class="metric-item">
            <span>历史案例复用率</span>
            <strong>{{ qualityPercent(health.quality.queryCaseReuseRate) }}</strong>
            <small>{{ health.quality.queryCaseReusedRunCount }} 次查询召回已验证案例</small>
          </div>
        </div>
      </section>

      <section class="overview-section two-column">
        <div class="fact-panel">
          <div class="section-heading compact">
            <div>
            <h2>业务模型</h2>
              <p>当前工作版本 v{{ health.workingVersion?.versionNumber || '-' }}</p>
            </div>
            <el-button
              v-if="props.canEdit"
              link
              type="primary"
              @click="emit('navigate', 'business')"
            >
              查看业务模型
            </el-button>
          </div>
          <dl class="fact-list">
            <div>
              <dt>数据连接</dt>
              <dd>{{ health.understanding.datasourceCount }}</dd>
            </div>
            <div>
              <dt>业务资料</dt>
              <dd>{{ health.understanding.documentCount }}</dd>
            </div>
            <div>
              <dt>业务对象</dt>
              <dd>{{ health.understanding.modelCount }}</dd>
            </div>
            <div>
              <dt>指标</dt>
              <dd>{{ health.understanding.metricCount }}</dd>
            </div>
            <div>
              <dt>维度</dt>
              <dd>{{ health.understanding.dimensionCount }}</dd>
            </div>
            <div>
              <dt>关系</dt>
              <dd>{{ health.understanding.relationshipCount }}</dd>
            </div>
            <div>
              <dt>待解决问题</dt>
              <dd>{{ health.understanding.openGapCount }}</dd>
            </div>
            <div>
              <dt>待处理冲突</dt>
              <dd>{{ health.understanding.unresolvedConflictCount }}</dd>
            </div>
          </dl>
          <el-alert
            v-if="health.understanding.readinessViolations.length"
            type="warning"
            :closable="false"
            :title="`${health.understanding.readinessViolations.length} 项发布前校验尚未满足`"
          />
        </div>

        <div class="fact-panel">
          <div class="section-heading compact">
            <div>
            <h2>版本与回归</h2>
              <p>自动回归结果与人工确认、发布决策保持独立。</p>
            </div>
            <el-button
              v-if="props.canReview"
              link
              type="primary"
              @click="emit('navigate', 'release')"
            >
              进入发布
            </el-button>
          </div>
          <dl class="release-list">
            <div>
              <dt>当前正式版本</dt>
              <dd>
                {{ health.activeVersion ? `v${health.activeVersion.versionNumber}` : '尚未发布' }}
              </dd>
            </div>
            <div>
              <dt>自动回归通过</dt>
              <dd>
                {{
                  health.release.replayCaseCount
                    ? `${health.release.replayPassedCount} / ${health.release.replayCaseCount}`
                    : '尚无回归记录'
                }}
              </dd>
            </div>
            <div>
              <dt>待处理学习建议</dt>
              <dd>{{ health.release.pendingLearningChangeCount }}</dd>
            </div>
            <div>
              <dt>最近成功查询</dt>
              <dd>{{ formatTime(health.freshness.lastSuccessfulQueryAt) }}</dd>
            </div>
          </dl>
        </div>
      </section>

      <section class="overview-section actions-section">
        <div class="section-heading">
          <div>
            <h2>接下来做什么</h2>
            <p>这里展示当前项目最重要的动作，完成后会自动刷新状态。</p>
          </div>
        </div>
        <div v-if="visibleNextActions.length" class="next-actions">
          <button
            v-for="(action, index) in visibleNextActions"
            :key="action.code"
            type="button"
            @click="handleAction(action.target)"
          >
            <span class="action-index">{{ index + 1 }}</span>
            <span class="action-copy">
              <strong>{{ action.label }}</strong>
              <small>{{ action.description }}</small>
            </span>
            <i class="bi bi-arrow-right"></i>
          </button>
        </div>
        <el-alert
          v-else-if="!health.queryReady"
          type="info"
          show-icon
          :closable="false"
          title="当前待办需要项目建设者继续处理；你仍可以查看项目状态和已有查询结果。"
        />
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import type { ProjectHealth } from '@/services/semevosql';

  const props = withDefaults(
    defineProps<{
      health?: ProjectHealth;
      loading?: boolean;
      error?: string;
      canEdit?: boolean;
      canReview?: boolean;
    }>(),
    { health: undefined, loading: false, error: '', canEdit: false, canReview: false },
  );
  const emit = defineEmits<{
    navigate: [target: 'data' | 'business' | 'improve' | 'test' | 'release'];
    chat: [];
  }>();

  const visibleNextActions = computed(() =>
    (props.health?.nextActions || []).filter(action => {
      if (action.target === 'chat') return true;
      if (['data', 'business'].includes(action.target)) return props.canEdit;
      return props.canReview;
    }),
  );
  const percent = (value?: number) => `${((value || 0) * 100).toFixed(1)}%`;
  const qualityPercent = (value?: number) =>
    props.health?.quality.totalQueries ? percent(value) : '—';
  const formatTime = (value?: string) =>
    value ? new Date(value).toLocaleString('zh-CN') : '尚无记录';

  const understandingLabel = computed(() => {
    if (!props.health) return '-';
    if (props.health.understanding.catalogReady && props.health.understanding.openGapCount === 0)
      return '口径已齐备';
    if (props.health.understanding.openGapCount > 0) return '仍需业务确认';
    return '还有发布前校验';
  });
  const understandingDetail = computed(() => {
    if (!props.health) return '';
    const value = props.health.understanding;
    return `${value.modelCount} 个业务对象 · ${value.metricCount} 个指标 · ${value.openGapCount} 个待解决问题`;
  });
  const qualityLabel = computed(() => {
    const quality = props.health?.quality;
    if (!quality || quality.totalQueries === 0) return '尚无真实查询样本';
    return `成功率 ${percent(quality.querySuccessRate)}`;
  });
  const qualityDetail = computed(() => {
    const quality = props.health?.quality;
    if (!quality || quality.totalQueries === 0) return '完成第一次查询后开始积累真实质量事实';
    return `${quality.totalQueries} 次查询 · ${quality.correctionCount} 次明确纠错 · ${quality.confirmedTrustedAnswerCount} 次明确确认`;
  });
  const freshnessLabel = computed(() => {
    const freshness = props.health?.freshness;
    if (!freshness || freshness.observationStatus === 'UNOBSERVED') return '尚无可判断证据';
    return freshness.latestSourceFreshnessAsOf || '已有执行时效记录';
  });
  const freshnessDetail = computed(() => {
    const freshness = props.health?.freshness;
    if (!freshness || freshness.observationStatus === 'UNOBSERVED')
      return '系统不会在没有数据源时效证据时主观标记“新鲜”';
    return `最近成功查询：${formatTime(freshness.lastSuccessfulQueryAt)}`;
  });

  const handleAction = (target: ProjectHealth['nextActions'][number]['target']) => {
    if (target === 'chat') emit('chat');
    else emit('navigate', target);
  };
</script>

<style scoped>
  .project-overview {
    min-height: 340px;
  }
  .status-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 14px;
  }
  .status-card {
    padding: 18px;
    border: 1px solid #dce7e7;
    border-radius: 14px;
    background: #fff;
  }
  .status-card.primary {
    border-color: #b9e1d9;
    background: #f2fbf8;
  }
  .status-card span,
  .metric-item span {
    color: #71858b;
    font-size: 12px;
  }
  .status-card strong {
    display: block;
    margin-top: 8px;
    color: #17353b;
    font-size: 20px;
  }
  .status-card p {
    margin: 7px 0 0;
    color: #71858b;
    font-size: 12px;
    line-height: 1.55;
  }
  .overview-section {
    margin-top: 26px;
    padding-top: 24px;
    border-top: 1px solid #eef2f7;
  }
  .section-heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 16px;
  }
  .section-heading.compact {
    margin-bottom: 12px;
  }
  .section-heading h2 {
    margin: 0 0 5px;
    color: #0f172a;
    font-size: 17px;
  }
  .section-heading p {
    margin: 0;
    color: #64748b;
    font-size: 12px;
  }
  .metric-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 12px;
  }
  .metric-item {
    display: grid;
    gap: 5px;
    padding: 14px;
    border: 1px solid #e1e9ea;
    border-radius: 12px;
    background: #fcfefe;
  }
  .metric-item.north-star {
    border-color: #b9e1d9;
  }
  .metric-item.trusted {
    border-color: #bfe5d4;
  }
  .metric-item strong {
    color: #17353b;
    font-size: 20px;
  }
  .metric-item small {
    color: #71858b;
    line-height: 1.45;
  }
  .two-column {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 18px;
  }
  .fact-panel {
    padding: 18px;
    border: 1px solid #dce7e7;
    border-radius: 14px;
  }
  .fact-list {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 12px;
    margin: 0 0 14px;
  }
  .fact-list div,
  .release-list div {
    display: grid;
    gap: 4px;
  }
  dt {
    color: #71858b;
    font-size: 11px;
  }
  dd {
    margin: 0;
    color: #17353b;
    font-weight: 650;
  }
  .release-list {
    display: grid;
    gap: 13px;
    margin: 0;
  }
  .next-actions {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 12px;
  }
  .next-actions button {
    display: flex;
    align-items: center;
    gap: 12px;
    min-height: 78px;
    padding: 14px;
    border: 1px solid #dce7e7;
    border-radius: 12px;
    background: #fff;
    text-align: left;
    cursor: pointer;
  }
  .next-actions button:hover {
    border-color: #afd8d0;
    background: #f2fbf8;
  }
  .action-index {
    display: grid;
    width: 28px;
    height: 28px;
    flex: 0 0 auto;
    place-items: center;
    border-radius: 50%;
    background: #e4f5f1;
    color: #177d73;
    font-weight: 700;
  }
  .action-copy {
    display: grid;
    flex: 1;
    gap: 4px;
  }
  .action-copy strong {
    color: #17353b;
  }
  .action-copy small {
    color: #71858b;
    line-height: 1.45;
  }
  @media (max-width: 1000px) {
    .status-grid,
    .metric-grid {
      grid-template-columns: repeat(2, 1fr);
    }
    .two-column {
      grid-template-columns: 1fr;
    }
  }
  @media (max-width: 680px) {
    .status-grid,
    .metric-grid,
    .next-actions {
      grid-template-columns: 1fr;
    }
    .fact-list {
      grid-template-columns: repeat(2, 1fr);
    }
  }
</style>
