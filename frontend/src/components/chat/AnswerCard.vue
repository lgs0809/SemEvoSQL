<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <article class="answer-card">
    <div class="answer-heading">
      <div>
        <span class="answer-label">查询回答</span>
        <span class="answer-time">{{ formattedTime }}</span>
      </div>
      <el-tag v-if="statusLabel" :type="statusType" size="small" effect="plain">
        {{ statusLabel }}
      </el-tag>
    </div>

    <section class="answer-section answer-summary">
      <h3>结论</h3>
      <div class="answer-content">{{ content }}</div>
    </section>

    <section v-if="artifactId" class="answer-section result-section">
      <div class="section-heading">
        <h3>结果表</h3>
        <span v-if="artifact">{{ artifact.rowCount }} 行</span>
      </div>
      <div v-loading="artifactLoading" class="result-content">
        <el-alert
          v-if="artifactError"
          type="warning"
          :closable="false"
          show-icon
          title="结果数据暂时无法加载，答案与依据仍可查看。"
        />
        <el-table
          v-else-if="artifactRows.length"
          :data="artifactRows"
          max-height="360"
          border
          size="small"
        >
          <el-table-column
            v-for="column in artifactColumns"
            :key="column"
            :prop="column"
            :label="friendlyColumnLabel(column)"
            min-width="140"
            show-overflow-tooltip
          />
        </el-table>
        <el-empty
          v-else-if="artifact && !artifactLoading"
          :image-size="54"
          description="查询结果为空"
        />
      </div>
    </section>

    <section v-if="explanation" class="answer-section trust-summary">
      <div v-if="businessDefinitionText" class="trust-item">
        <span>业务口径</span>
        <strong>{{ businessDefinitionText }}</strong>
      </div>
      <div v-if="timeText" class="trust-item">
        <span>时间口径</span>
        <strong>{{ timeText }}</strong>
      </div>
      <div v-if="sourceText" class="trust-item">
        <span>数据来源</span>
        <strong>{{ sourceText }}</strong>
      </div>
    </section>

    <div class="answer-actions">
      <div v-if="showFeedbackActions" class="feedback-actions">
        <el-button type="success" plain :loading="feedbackLoading" @click="emit('trust')">
          确认结果正确
        </el-button>
        <el-button type="danger" plain @click="emit('correct')">纠正这条理解</el-button>
      </div>
      <div class="detail-actions">
        <el-button v-if="explanation" link type="primary" @click="evidenceOpen = !evidenceOpen">
          {{ evidenceOpen ? '收起依据' : '查看依据' }}
        </el-button>
      </div>
    </div>

    <slot name="feedback" />
    <slot name="learning" />

    <el-collapse-transition>
      <section v-if="evidenceOpen && explanation" class="evidence-panel">
        <div class="evidence-section">
          <strong>问题理解</strong>
          <p>{{ explanation.understoodQuery || '-' }}</p>
        </div>
        <div v-if="explanation.semanticBindings?.length" class="evidence-section">
          <strong>业务含义</strong>
          <ul>
            <li v-for="(binding, index) in explanation.semanticBindings" :key="`binding-${index}`">
              {{ binding.displayPhrase || binding.normalizedPhrase || '-' }} →
              {{ binding.businessLabel || binding.assetKey || '-' }}
              <span v-if="binding.source">
                （{{ bindingSourceLabel(String(binding.source)) }}）
              </span>
            </li>
          </ul>
        </div>
        <div v-if="explanation.businessDefinitions?.length" class="evidence-section">
          <strong>业务定义</strong>
          <ul>
            <li
              v-for="(definition, index) in explanation.businessDefinitions"
              :key="`definition-${index}`"
            >
              {{ definition.name || definition.code || '业务定义' }}
            </li>
          </ul>
        </div>
        <div v-if="explanation.filters?.length" class="evidence-section">
          <strong>过滤条件</strong>
          <ul>
            <li v-for="(filter, index) in explanation.filters" :key="`filter-${index}`">
              {{ compactObject(filter) }}
            </li>
          </ul>
        </div>
        <div v-if="explanation.models?.length" class="evidence-section">
          <strong>数据来源</strong>
          <ul>
            <li v-for="(model, index) in explanation.models" :key="`model-${index}`">
              {{ friendlyModelLabel(model) }}
            </li>
          </ul>
        </div>
        <div v-if="explanation.relationships?.length" class="evidence-section">
          <strong>表关系</strong>
          <ul>
            <li
              v-for="(relationship, index) in explanation.relationships"
              :key="`relationship-${index}`"
            >
              {{ relationship.from || '-' }} → {{ relationship.to || '-' }} ·
              {{ relationship.joinType || '-' }} · {{ relationship.condition || '-' }}
            </li>
          </ul>
        </div>
        <el-collapse v-if="explanation.sqlExecutions?.length" class="sql-collapse">
          <el-collapse-item title="SQL（高级）" name="sql">
            <pre v-for="(execution, index) in explanation.sqlExecutions" :key="`sql-${index}`">{{
              execution.sql || '该执行未记录 SQL 文本'
            }}</pre>
          </el-collapse-item>
        </el-collapse>
        <div v-if="runId" class="evidence-actions">
          <el-button link type="primary" @click="emit('diagnosis')">查询诊断</el-button>
          <el-button link @click="emit('run-details')">执行详情</el-button>
        </div>
      </section>
    </el-collapse-transition>
  </article>
</template>

<script setup lang="ts">
  import { computed, ref } from 'vue';
  import type { QueryExecutionExplanation, ResultArtifact } from '@/services/semevosql';

  const props = defineProps<{
    content: string;
    createTime?: string;
    status?: string;
    runId?: string;
    explanation?: QueryExecutionExplanation;
    artifactId?: string;
    artifact?: ResultArtifact;
    artifactColumns: string[];
    artifactRows: Array<Record<string, unknown>>;
    artifactLoading?: boolean;
    artifactError?: string;
    showFeedbackActions?: boolean;
    feedbackLoading?: boolean;
  }>();
  const emit = defineEmits<{
    trust: [];
    correct: [];
    diagnosis: [];
    'run-details': [];
  }>();

  const evidenceOpen = ref(false);
  const formattedTime = computed(() =>
    props.createTime ? new Date(props.createTime).toLocaleString('zh-CN') : '',
  );
  const statusLabel = computed(() => {
    if (props.status === 'COMPLETED') return '查询完成';
    if (props.status === 'FAILED') return '查询未完成';
    if (props.status === 'PENDING') return '处理中';
    return '';
  });
  const statusType = computed(() => {
    if (props.status === 'COMPLETED') return 'success';
    if (props.status === 'FAILED') return 'danger';
    return 'info';
  });

  const businessDefinitionText = computed(() => {
    const definitions = (props.explanation?.businessDefinitions || [])
      .slice(0, 3)
      .map(item => {
        const name = String(item.name || item.businessName || item.code || '').trim();
        const definition = String(item.description || item.definition || '').trim();
        if (!name) return definition || '业务定义';
        return definition && definition !== name ? `${name}：${definition}` : name;
      })
      .filter(Boolean);
    if (definitions.length) return definitions.join('；');
    return (props.explanation?.semanticBindings || [])
      .slice(0, 4)
      .map(item => String(item.businessLabel || item.assetKey || ''))
      .filter(Boolean)
      .join('、');
  });
  const timeText = computed(() => {
    const time = props.explanation?.time || {};
    const range = [time.startInclusive, time.endExclusive].filter(Boolean).join(' ～ ');
    const businessField = time.businessName || time.businessLabel || time.displayName;
    return [time.relativeExpression, range, businessField ? `按${businessField}统计` : '']
      .filter(Boolean)
      .join('；');
  });
  const sourceText = computed(() =>
    (props.explanation?.models || [])
      .slice(0, 5)
      .map(item => friendlyModelLabel(item))
      .filter(Boolean)
      .join('、'),
  );

  const friendlyColumnLabel = (column: string) => {
    const normalized = column.trim().toLowerCase();
    const persisted = props.explanation?.resultColumns?.find(
      item => String(item.key || '').trim().toLowerCase() === normalized,
    );
    if (persisted?.label) return persisted.label;
    const definitions = props.explanation?.businessDefinitions || [];
    const definition = definitions.find(item => {
      const code = String(item.code || '').trim().toLowerCase();
      return code && code === normalized && String(item.name || '').trim();
    });
    if (definition) return String(definition.name).trim();
    const binding = (props.explanation?.semanticBindings || []).find(item => {
      const key = String(item.assetKey || '').trim().toLowerCase();
      return key && key === normalized && String(item.businessLabel || item.displayName || '').trim();
    });
    return String(binding?.businessLabel || binding?.displayName || '结果字段');
  };

  const friendlyModelLabel = (model: Record<string, unknown>) => {
    const named = String(model.name || model.businessName || '').trim();
    const code = String(model.code || model.table || '').trim();
    if (named && named !== code) return named;
    return '业务对象';
  };

  const bindingSourceLabel = (source: string) => {
    if (source === 'USER') return '你的偏好';
    if (source === 'PROJECT') return '项目默认';
    if (source === 'MANUAL' || source === 'CLARIFICATION') return '本次确认';
    return '业务模型';
  };

  const compactObject = (value: Record<string, unknown>) =>
    Object.entries(value)
      .filter(([, item]) => item != null && item !== '')
      .slice(0, 5)
      .map(([key, item]) => `${key}: ${String(item)}`)
      .join('；');
</script>

<style scoped>
  .answer-card {
    max-width: 860px;
    margin: 0 auto 22px;
    overflow: hidden;
    border: 1px solid #dce8e8;
    border-radius: 14px;
    background: #fff;
    box-shadow: 0 10px 24px rgb(20 63 65 / 6%);
  }
  .answer-heading,
  .section-heading,
  .answer-actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .answer-heading {
    padding: 14px 18px;
    border-bottom: 1px solid #edf3f2;
    background: #f7fbfa;
  }
  .answer-heading > div {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .answer-label {
    color: #17353b;
    font-weight: 700;
  }
  .answer-time,
  .section-heading span {
    color: #63777e;
    font-size: 12px;
  }
  .answer-section {
    padding: 18px;
    border-bottom: 1px solid #edf3f2;
  }
  .answer-section h3,
  .section-heading h3 {
    margin: 0 0 10px;
    color: #46636a;
    font-size: 13px;
    font-weight: 650;
  }
  .section-heading h3 {
    margin: 0;
  }
  .answer-content {
    color: #213e46;
    white-space: pre-wrap;
    line-height: 1.78;
  }
  .result-content {
    min-height: 36px;
    margin-top: 12px;
  }
  .trust-summary {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px;
    background: #f7fbfa;
  }
  .trust-item {
    display: grid;
    align-content: start;
    gap: 4px;
    min-width: 0;
  }
  .trust-item span {
    color: #63777e;
    font-size: 11px;
  }
  .trust-item strong {
    overflow: hidden;
    color: #46636a;
    font-size: 12px;
    font-weight: 600;
    text-overflow: ellipsis;
  }
  .answer-actions {
    padding: 13px 18px;
  }
  .feedback-actions,
  .detail-actions {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 6px;
  }
  .evidence-panel {
    padding: 4px 18px 18px;
    border-top: 1px solid #edf3f2;
    background: #f5f9f8;
  }
  .evidence-section {
    padding: 12px 0;
    border-bottom: 1px dashed #d6e6e3;
    color: #46636a;
    line-height: 1.65;
  }
  .evidence-section strong {
    font-size: 13px;
  }
  .evidence-section p,
  .evidence-section ul {
    margin: 6px 0 0;
  }
  .evidence-section ul {
    padding-left: 20px;
  }
  .sql-collapse {
    margin-top: 8px;
    border: 0;
  }
  .evidence-actions {
    display: flex;
    gap: 4px;
    margin-top: 10px;
    padding-top: 8px;
    border-top: 1px dashed #d6e6e3;
  }
  .sql-collapse pre {
    overflow-x: auto;
    padding: 10px;
    border-radius: 8px;
    background: #0f172a;
    color: #e2e8f0;
    white-space: pre-wrap;
    word-break: break-word;
  }
  @media (max-width: 760px) {
    .trust-summary {
      grid-template-columns: 1fr;
    }
    .answer-actions {
      align-items: flex-start;
      flex-direction: column;
    }
  }
</style>
