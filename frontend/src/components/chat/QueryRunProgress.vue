<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section
    class="run-progress"
    :class="{ failed: isFailed, compact: run.status === 'SUCCEEDED' && !expanded }"
    aria-label="查询执行进度"
    aria-live="polite"
    role="status"
  >
    <div class="progress-heading">
      <div>
        <strong>{{ headline }}</strong>
        <span v-if="detail">{{ detail }}</span>
      </div>
      <el-button v-if="needsAction" type="primary" size="small" @click="emit('confirm')">查看待确认内容</el-button>
      <el-button v-else-if="run.status === 'SUCCEEDED'" link size="small" :aria-expanded="expanded" @click="expanded = !expanded">{{ expanded ? '收起流程' : '查看流程' }}</el-button>
    </div>
    <ol v-if="run.status !== 'SUCCEEDED' || expanded" class="progress-stages">
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
  import { computed, ref } from 'vue';
  import type { QueryRun, RunEvent } from '@/services/semevosql';
  import { isSemanticUpdate } from '@/utils/run-request-kind';
  import { queryProgressStage } from '@/utils/query-progress-stage';

  const props = defineProps<{
    run: QueryRun;
    events?: RunEvent[];
    needsAction?: boolean;
    transportNotice?: string;
  }>();
  const emit = defineEmits<{ confirm: [] }>();
  const expanded = ref(false);

  const TERMINAL_FAILURES = new Set(['FAILED', 'CANCELLED', 'EXPIRED']);
  const semanticUpdate = computed(() => isSemanticUpdate(props.events));
  const labels = computed(() => semanticUpdate.value
    ? ['理解修改意图', '确认定义及影响', '提交口径修改', '保存确认记录']
    : ['理解需求', '确认业务口径', '生成并执行查询', '整理结果']);
  const isFailed = computed(() => TERMINAL_FAILURES.has(props.run.status));

  const stageIndex = computed(() => {
    if (props.run.status === 'SUCCEEDED') return labels.value.length;
    if (semanticUpdate.value) {
      const confirmed = props.events?.some(event => {
        if (event.eventType !== 'CLARIFICATION_ANSWERED' || !event.payload) return false;
        try {
          return String(JSON.parse(event.payload).selectedOption || '').startsWith('CONFIRM_');
        } catch { return false; }
      });
      return confirmed && props.run.status !== 'WAITING_HUMAN' && !props.needsAction ? 2 : 1;
    }
    return queryProgressStage(props.run, props.events, props.needsAction);
  });

  const stages = computed(() =>
    labels.value.map((label, index) => ({
      label,
      state:
        props.run.status === 'SUCCEEDED' || index < stageIndex.value
          ? 'done'
          : index === Math.min(stageIndex.value, labels.value.length - 1)
            ? 'current'
            : 'pending',
    })),
  );

  const headline = computed(() => {
    if (props.run.status === 'SUCCEEDED') return semanticUpdate.value ? '口径修改已完成' : '查询已完成';
    if (TERMINAL_FAILURES.has(props.run.status)) return semanticUpdate.value ? '口径修改未完成' : '查询未完成';
    if (props.needsAction || props.run.status === 'WAITING_HUMAN') return '等待你确认业务口径';
    return `正在${labels.value[Math.min(stageIndex.value, labels.value.length - 1)]}`;
  });

  const detail = computed(() => {
    if (props.transportNotice) return props.transportNotice;
    if (props.run.status === 'SUCCEEDED') return semanticUpdate.value
      ? '确认的定义、范围和历史影响已经保存，可以重新打开查看。'
      : '结果和执行依据已经保存，可以随时重新打开。';
    if (TERMINAL_FAILURES.has(props.run.status))
      return props.run.errorMessage || '可以查看运行详情后重试。';
    if (props.needsAction || props.run.status === 'WAITING_HUMAN')
      return semanticUpdate.value ? '确认后提交这次修改，没有重新执行数据查询。' : '确认后会从当前执行继续，不会重新开始整条查询。';
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
  .compact .progress-heading { margin-bottom: 0; }
  .compact .progress-heading > div > span { display: none; }
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
