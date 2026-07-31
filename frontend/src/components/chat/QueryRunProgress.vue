<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section
    class="run-progress"
    :class="{ failed: isFailed }"
    aria-label="查询执行进度"
    aria-live="polite"
    role="status"
  >
    <div class="progress-heading">
      <div>
        <strong>{{ headline }}</strong>
        <span v-if="detail">{{ detail }}</span>
      </div>
      <el-tag v-if="needsAction" type="warning" effect="plain">需要你的确认</el-tag>
    </div>
    <ol class="progress-stages">
      <li v-for="(stage, index) in stages" :key="stage.label" :class="stage.state">
        <span class="stage-dot">
          <i v-if="stage.state === 'done'" class="bi bi-check-lg"></i>
          <span v-else>{{ index + 1 }}</span>
        </span>
        <span>{{ stage.label }}</span>
      </li>
    </ol>
  </section>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import type { QueryRun, RunEvent } from '@/services/semevosql';

  const props = defineProps<{
    run: QueryRun;
    events?: RunEvent[];
    needsAction?: boolean;
    transportNotice?: string;
  }>();

  const TERMINAL_FAILURES = new Set(['FAILED', 'CANCELLED', 'EXPIRED']);
  const labels = ['理解需求', '确认业务口径', '生成并执行查询', '整理结果'];
  const isFailed = computed(() => TERMINAL_FAILURES.has(props.run.status));

  const stageIndex = computed(() => {
    if (props.run.status === 'SUCCEEDED') return labels.length;
    const latest = props.events?.at(-1);
    const signal =
      `${props.run.currentNode || ''} ${latest?.eventType || ''} ${latest?.nodeName || ''}`.toUpperCase();
    if (props.needsAction || props.run.status === 'WAITING_HUMAN') return 1;
    if (/MERGE|ARTIFACT|REPORT|AGGREGAT|FINAL/.test(signal)) return 3;
    if (/SQL|EXECUT|QUERY|DATASOURCE|SOURCE_SUBRUN/.test(signal)) return 2;
    if (/PLAN|SEMANTIC|CLARIFICATION|HUMAN/.test(signal)) return 1;
    return 0;
  });

  const stages = computed(() =>
    labels.map((label, index) => ({
      label,
      state:
        props.run.status === 'SUCCEEDED' || index < stageIndex.value
          ? 'done'
          : index === Math.min(stageIndex.value, labels.length - 1)
            ? 'current'
            : 'pending',
    })),
  );

  const headline = computed(() => {
    if (props.run.status === 'SUCCEEDED') return '查询已完成';
    if (TERMINAL_FAILURES.has(props.run.status)) return '查询未完成';
    if (props.needsAction || props.run.status === 'WAITING_HUMAN') return '等待你确认业务口径';
    return `正在${labels[Math.min(stageIndex.value, labels.length - 1)]}`;
  });

  const detail = computed(() => {
    if (props.transportNotice) return props.transportNotice;
    if (props.run.status === 'SUCCEEDED') return '结果和执行依据已经保存，可以随时重新打开。';
    if (TERMINAL_FAILURES.has(props.run.status))
      return props.run.errorMessage || '可以查看运行详情后重试。';
    if (props.needsAction || props.run.status === 'WAITING_HUMAN')
      return '确认后会从当前执行继续，不会重新开始整条查询。';
    return '页面断开不会取消后台执行，重新连接后会继续同步进度。';
  });
</script>

<style scoped>
  .run-progress {
    margin: 0 22px 10px;
    padding: 12px 14px;
    border: 1px solid #cfe7e2;
    border-radius: 12px;
    background: #f3fbf8;
  }
  .run-progress.failed {
    border-color: #fecaca;
    background: #fff7f7;
  }
  .run-progress.failed .progress-heading strong {
    color: #b91c1c;
  }
  .progress-heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 10px;
  }
  .progress-heading > div {
    display: grid;
    gap: 3px;
  }
  .progress-heading strong {
    color: #177d73;
    font-size: 12px;
  }
  .progress-heading span {
    color: #71858b;
    font-size: 11px;
  }
  .progress-stages {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 8px;
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .progress-stages li {
    display: flex;
    align-items: center;
    gap: 7px;
    min-width: 0;
    color: #94a3b8;
    font-size: 11px;
  }
  .progress-stages li.current {
    color: #177d73;
    font-weight: 650;
  }
  .progress-stages li.done {
    color: #4d6870;
  }
  .stage-dot {
    display: grid;
    width: 20px;
    height: 20px;
    flex: 0 0 auto;
    place-items: center;
    border-radius: 50%;
    background: #e2e8f0;
    color: #64748b;
    font-size: 9px;
    font-weight: 700;
  }
  .done .stage-dot {
    background: #e5f6ee;
    color: #2b896d;
  }
  .current .stage-dot {
    background: #177d73;
    color: #fff;
  }
  @media (max-width: 680px) {
    .run-progress {
      margin-inline: 12px;
    }
    .progress-stages {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }
</style>
