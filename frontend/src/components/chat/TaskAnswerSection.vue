<!-- Copyright 2024-2026 the original author or authors. Licensed under the Apache License, Version 2.0. -->
<template>
  <section class="task-answer">
    <h3>结果 {{ answer.ordinal + 1 }}</h3>
    <p class="task-question">{{ answer.question }}</p>
    <el-alert v-if="answer.error" :title="answer.error" type="warning" :closable="false" />
    <template v-else>
      <p v-if="answer.report" class="task-report">{{ answer.report }}</p>
      <div v-if="answer.columns.length" class="result-content">
        <div class="row-count">{{ answer.rows.length }} 行</div>
        <el-table
          v-if="answer.rows.length"
          :data="answer.rows"
          max-height="360"
          border
          size="small"
        >
          <el-table-column
            v-for="(column, index) in answer.columns"
            :key="column"
            :prop="column"
            :label="columnLabel(column, index)"
            :formatter="resultCellText"
            min-width="140"
            show-overflow-tooltip
          />
        </el-table>
        <el-empty v-else :image-size="54" description="这项查询没有符合条件的数据" />
      </div>
      <dl v-if="answer.explanation" class="task-facts">
        <div v-if="definition">
          <dt>业务口径</dt>
          <dd>{{ definition }}</dd>
        </div>
        <div v-if="timeRange">
          <dt>时间口径</dt>
          <dd>{{ timeRange }}</dd>
        </div>
        <div v-if="sources">
          <dt>数据来源</dt>
          <dd>{{ sources }}</dd>
        </div>
      </dl>
    </template>
  </section>
</template>

<script setup lang="ts">
  import { resultCellText } from '@/utils/result-cell';
  import { computed } from 'vue';
  import type { QueryTaskAnswer } from '@/services/semevosql';

  const props = defineProps<{ answer: QueryTaskAnswer }>();
  const definition = computed(() =>
    (props.answer.explanation?.businessDefinitions || [])
      .map((item) => String(item.name || ''))
      .filter(Boolean)
      .join('、'),
  );
  const timeRange = computed(() => {
    const time = props.answer.explanation?.time || {};
    return (
      [time.startInclusive, time.endExclusive].filter(Boolean).join(' ～ ') ||
      String(time.relativeExpression || '')
    );
  });
  const sources = computed(() =>
    (props.answer.explanation?.models || [])
      .map((item) => String(item.name || '业务对象'))
      .join('、'),
  );
  const columnLabel = (column: string, index: number) => {
    const label = props.answer.explanation?.resultColumns?.find(
      (item) => item.key === column,
    )?.label;
    return label || `结果字段${index + 1}`;
  };
</script>

<style scoped>
  .task-answer {
    padding: 18px;
    border-bottom: 1px solid #edf3f2;
  }
  h3 {
    margin: 0 0 10px;
    color: #46636a;
    font-size: 14px;
  }
  .task-question,
  .task-report {
    color: #213e46;
    white-space: pre-wrap;
    line-height: 1.7;
  }
  .task-question {
    margin: 0 0 12px;
  }
  .row-count {
    margin-bottom: 6px;
    color: #63777e;
    font-size: 12px;
  }
  .task-facts {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 12px;
    margin: 14px 0 0;
  }
  dt {
    color: #63777e;
    font-size: 11px;
  }
  dd {
    margin: 4px 0 0;
    color: #46636a;
    font-size: 12px;
    overflow-wrap: anywhere;
  }
  @media (max-width: 760px) {
    .task-facts {
      grid-template-columns: 1fr;
    }
  }
</style>
