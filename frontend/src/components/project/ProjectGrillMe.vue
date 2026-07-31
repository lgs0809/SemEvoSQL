<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section class="grill-me" v-loading="loading">
    <div class="toolbar">
      <div>
        <h2>业务规则确认</h2>
        <p>系统已经先从数据库和业务资料中自动理解，只需要你确认无法安全推断的关键业务规则。</p>
      </div>
      <div class="toolbar-actions">
        <el-select v-model="selectedVersionId" placeholder="选择版本" @change="load">
          <el-option
            v-for="version in versions"
            :key="version.id"
            :label="`${version.versionNumber} · ${versionStatusLabel(version.status)}`"
            :value="version.id"
          />
        </el-select>
        <el-button v-if="canEdit" :disabled="!selectedVersionId" @click="start">
          启动 / 恢复
        </el-button>
      </div>
    </div>

    <template v-if="view">
      <div class="summary-grid">
        <el-card shadow="never">
          <span>必答项</span>
          <strong>{{ view.summary.requiredItems }}</strong>
        </el-card>
        <el-card shadow="never">
          <span>已完成</span>
          <strong>{{ view.summary.completedItems }}</strong>
        </el-card>
        <el-card shadow="never">
          <span>阻塞问题</span>
          <strong>{{ view.summary.blockingQuestions }}</strong>
        </el-card>
        <el-card shadow="never">
          <span>语义缺口</span>
          <strong>{{ view.summary.openSemanticGaps }}</strong>
        </el-card>
      </div>

      <el-alert
        v-if="view.conflicts.length"
        type="warning"
        :closable="false"
        show-icon
        title="当前仍有问询冲突"
      >
        <template #default>
          <ul>
            <li v-for="conflict in view.conflicts" :key="conflict.id">{{ conflict.message }}</li>
          </ul>
        </template>
      </el-alert>

      <el-card v-if="view.nextQuestion" shadow="never" class="question-card">
        <template #header>
          <div class="question-heading">
            <div>
              <el-tag v-if="view.nextQuestion.blocking" type="danger">必须确认</el-tag>
            </div>
            <span>{{ view.summary.completedItems }} / {{ view.summary.requiredItems }} 已完成</span>
          </div>
        </template>
        <h3>{{ view.nextQuestion.question }}</h3>
        <p v-if="view.nextQuestion.recommendationReason" class="muted">
          {{ view.nextQuestion.recommendationReason }}
        </p>
        <el-alert
          v-if="view.nextQuestion.evidence"
          type="info"
          :closable="false"
          :title="view.nextQuestion.evidence"
        />
        <el-input
          v-model="answer"
          class="answer-input"
          type="textarea"
          :autosize="{ minRows: 6, maxRows: 18 }"
          :placeholder="answerPlaceholder"
          :readonly="!canEdit"
        />
        <details v-if="view.nextQuestion.answerSchema" class="schema-details">
          <summary>高级回答格式</summary>
          <pre>{{ formattedSchema }}</pre>
        </details>
        <div v-if="canEdit" class="answer-actions">
          <el-button
            v-if="view.nextQuestion.recommendedAnswer"
            @click="answer = view.nextQuestion?.recommendedAnswer || ''"
          >
            使用推荐回答
          </el-button>
          <el-button type="primary" :loading="submitting" @click="submitAnswer">提交回答</el-button>
        </div>
      </el-card>

      <el-result
        v-else-if="view.summary.readyToConfirm && !view.session.summaryConfirmed"
        icon="success"
        title="所有阻塞问题已处理"
        sub-title="确认后系统会检查业务模型是否已经满足发布前要求。"
      >
        <template #extra>
          <el-button v-if="canEdit" type="primary" :loading="confirming" @click="confirm">
            确认业务规则
          </el-button>
        </template>
      </el-result>

      <el-result
        v-else-if="view.session.summaryConfirmed"
        icon="success"
        title="业务规则已确认"
        :sub-title="
          view.summary.catalogReady
            ? '业务模型已经满足当前发布前要求。'
            : '规则已确认，仍有业务模型结构问题需要处理。'
        "
      />
      <el-empty v-else description="当前没有可回答问题；可刷新或重新执行初始化分析。" />
    </template>
  </section>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref, watch } from 'vue';
  import { ElMessage } from 'element-plus';
  import {
    semEvoSQLService,
    type OnboardingView,
    type SemanticProjectVersion,
  } from '@/services/semevosql';
  import { versionStatusLabel } from '@/services/displayLabels';

  const props = defineProps<{
    projectId: number;
    versions: SemanticProjectVersion[];
    canEdit?: boolean;
  }>();
  const canEdit = computed(() => props.canEdit !== false);
  const emit = defineEmits<{ completed: [] }>();
  const selectedVersionId = ref<number>();
  const view = ref<OnboardingView>();
  const answer = ref('');
  const loading = ref(false);
  const submitting = ref(false);
  const confirming = ref(false);

  const formattedSchema = computed(() => {
    const raw = view.value?.nextQuestion?.answerSchema;
    if (!raw) return '';
    try {
      return JSON.stringify(JSON.parse(raw), null, 2);
    } catch {
      return raw;
    }
  });
  const answerPlaceholder = computed(() =>
    view.value?.nextQuestion?.answerSchema
      ? '按回答结构填写 JSON；普通文本问题也可直接输入。'
      : '请输入明确、可验证的回答。',
  );

  const chooseVersion = () => {
    if (
      !selectedVersionId.value ||
      !props.versions.some(item => item.id === selectedVersionId.value)
    ) {
      selectedVersionId.value =
        props.versions.find(item => item.status === 'DRAFT')?.id || props.versions[0]?.id;
    }
  };

  const load = async () => {
    if (!selectedVersionId.value) return;
    loading.value = true;
    try {
      view.value = await semEvoSQLService.onboarding(props.projectId, selectedVersionId.value);
      answer.value = view.value.nextQuestion?.recommendedAnswer || '';
    } catch {
      view.value = undefined;
    } finally {
      loading.value = false;
    }
  };

  const start = async () => {
    if (!selectedVersionId.value) return;
    loading.value = true;
    try {
      view.value = await semEvoSQLService.startOnboarding(
        props.projectId,
        selectedVersionId.value,
      );
      answer.value = view.value.nextQuestion?.recommendedAnswer || '';
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '业务规则确认启动失败');
    } finally {
      loading.value = false;
    }
  };

  const submitAnswer = async () => {
    if (!selectedVersionId.value || !view.value?.nextQuestion || !answer.value.trim()) {
      ElMessage.warning('请输入回答');
      return;
    }
    submitting.value = true;
    try {
      view.value = await semEvoSQLService.answerOnboarding(
        props.projectId,
        selectedVersionId.value,
        view.value.nextQuestion,
        answer.value.trim(),
      );
      answer.value = view.value.nextQuestion?.recommendedAnswer || '';
      ElMessage.success('回答已保存并重新计算缺口');
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '回答提交失败');
    } finally {
      submitting.value = false;
    }
  };

  const confirm = async () => {
    if (!selectedVersionId.value || !view.value) return;
    confirming.value = true;
    try {
      view.value = await semEvoSQLService.confirmOnboarding(
        props.projectId,
        selectedVersionId.value,
        view.value.summary.revision,
      );
      ElMessage.success('业务规则已确认');
      emit('completed');
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '确认失败');
    } finally {
      confirming.value = false;
    }
  };

  watch(
    () => props.versions,
    () => {
      chooseVersion();
      void load();
    },
    { deep: true },
  );
  onMounted(() => {
    chooseVersion();
    void load();
  });
</script>

<style scoped>
  .toolbar {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 18px;
  }
  .toolbar h2 {
    margin: 0 0 6px;
    color: #0f172a;
  }
  .toolbar p,
  .muted,
  .question-heading span {
    color: #64748b;
  }
  .toolbar-actions {
    display: flex;
    gap: 10px;
  }
  .toolbar-actions .el-select {
    width: 250px;
  }
  .summary-grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 12px;
    margin: 18px 0;
  }
  .summary-grid :deep(.el-card__body) {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .summary-grid span {
    color: #94a3b8;
    font-size: 12px;
  }
  .summary-grid strong {
    color: #0f172a;
    font-size: 22px;
  }
  .question-card {
    margin-top: 18px;
  }
  .question-heading,
  .answer-actions {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    align-items: center;
  }
  .question-heading > div {
    display: flex;
    gap: 8px;
  }
  .answer-input {
    margin-top: 16px;
  }
  .schema-details {
    margin: 12px 0;
    color: #64748b;
  }
  .schema-details pre {
    overflow: auto;
    padding: 12px;
    background: #f8fafc;
    color: #334155;
  }
  @media (max-width: 800px) {
    .toolbar,
    .toolbar-actions,
    .answer-actions {
      flex-direction: column;
    }
    .toolbar-actions,
    .toolbar-actions .el-select {
      width: 100%;
    }
    .summary-grid {
      grid-template-columns: 1fr 1fr;
    }
  }
</style>
