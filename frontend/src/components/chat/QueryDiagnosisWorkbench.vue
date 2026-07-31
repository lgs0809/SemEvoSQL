<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <el-drawer
    :model-value="modelValue"
    title="查询诊断与修复"
    size="min(860px, 94vw)"
    @close="emit('update:modelValue', false)"
  >
    <div v-loading="loading" class="diagnosis-workbench">
      <el-alert v-if="error" type="error" show-icon :closable="false" title="诊断信息加载失败">
        <template #default>
          <div class="inline-action">
            <span>{{ error }}</span>
            <el-button size="small" @click="load">重新诊断</el-button>
          </div>
        </template>
      </el-alert>

      <template v-if="diagnosis">
        <section class="diagnosis-hero" :class="rootCauseTone">
          <div>
            <span class="eyebrow">自动归因</span>
            <h2>{{ rootCauseLabel(diagnosis.rootCause) }}</h2>
            <p>{{ diagnosis.summary }}</p>
            <small v-if="diagnosis.question">原问题：{{ diagnosis.question }}</small>
          </div>
          <div class="hero-tags">
            <el-tag :type="confidenceType" effect="plain">
              {{ confidenceLabel(diagnosis.confidence) }}
            </el-tag>
            <el-tag effect="plain">执行状态：{{ runStatusLabel(diagnosis.runStatus) }}</el-tag>
          </div>
        </section>

        <section class="workbench-section">
          <div class="section-heading">
            <div>
              <span class="section-index">01</span>
              <h3>异常定位</h3>
            </div>
            <small>基于可审计的执行记录定位问题，不展示模型内部推理过程</small>
          </div>
          <div class="stage-grid">
            <article
              v-for="stage in diagnosis.stages"
              :key="stage.code"
              :class="stage.state.toLowerCase()"
            >
              <span class="stage-icon" aria-hidden="true">{{ stageIcon(stage.state) }}</span>
              <div>
                <strong>{{ stageLabel(stage) }}</strong>
                <p>{{ stageSummary(stage) }}</p>
              </div>
            </article>
          </div>
        </section>

        <section v-if="diagnosis.pipeline" class="workbench-section">
          <div class="section-heading">
            <div>
              <span class="section-index">02</span>
              <h3>执行证据链</h3>
            </div>
            <small>语义绑定 → 语义蓝图 → 语义 SQL → 查询预检 → 执行 SQL → 安全与成本 → 复核</small>
          </div>

          <div class="pipeline-facts">
            <el-tag effect="plain">查询预检：{{ preflightStatusLabel(String(diagnosis.pipeline.dryPlan?.status || '')) }}</el-tag>
            <el-tag effect="plain">SQL 轨迹：{{ diagnosis.pipeline.sqlTraces?.length || 0 }} 条</el-tag>
            <el-tag
              v-if="diagnosis.pipeline.reviewDecision"
              :type="diagnosis.pipeline.reviewDecision === 'PASS' ? 'success' : 'warning'"
              effect="plain"
            >
              执行复核：{{ decisionLabel(diagnosis.pipeline.reviewDecision) }}
            </el-tag>
          </div>

          <el-collapse class="pipeline-collapse">
            <el-collapse-item v-if="diagnosis.pipeline.semanticPlanJson" title="语义蓝图" name="semantic-plan">
              <pre class="evidence-code">{{ prettyJson(diagnosis.pipeline.semanticPlanJson) }}</pre>
            </el-collapse-item>
            <el-collapse-item v-if="diagnosis.pipeline.executionPlanJson" title="规划执行方案" name="execution-plan">
              <pre class="evidence-code">{{ prettyJson(diagnosis.pipeline.executionPlanJson) }}</pre>
            </el-collapse-item>
          <el-collapse-item v-if="diagnosis.pipeline.semanticSql" title="语义 SQL" name="semantic-sql">
              <pre class="evidence-code">{{ diagnosis.pipeline.semanticSql }}</pre>
            </el-collapse-item>
            <el-collapse-item title="查询预检" name="dry-plan">
              <pre class="evidence-code">{{ prettyJson(diagnosis.pipeline.dryPlan) }}</pre>
            </el-collapse-item>
          <el-collapse-item v-if="diagnosis.pipeline.physicalSql" title="执行 SQL" name="physical-sql">
              <pre class="evidence-code">{{ diagnosis.pipeline.physicalSql }}</pre>
            </el-collapse-item>
            <el-collapse-item
              v-for="(trace, index) in diagnosis.pipeline.sqlTraces || []"
              :key="`sql-trace-${index}`"
              :title="`安全与成本检查 #${index + 1} · ${checkStatusLabel(String(trace.status || ''))}`"
              :name="`sql-trace-${index}`"
            >
              <pre class="evidence-code">{{ prettyJson(trace) }}</pre>
            </el-collapse-item>
            <el-collapse-item
              v-if="diagnosis.pipeline.reviewDecision || diagnosis.pipeline.reviewEvidence"
              title="执行后复核与修复预算"
              name="post-review"
            >
              <pre class="evidence-code">{{ prettyJson({
                decision: diagnosis.pipeline.reviewDecision,
                issueType: diagnosis.pipeline.reviewIssueType,
                evidence: diagnosis.pipeline.reviewEvidence,
                repairBudget: diagnosis.pipeline.repairBudget,
              }) }}</pre>
            </el-collapse-item>
          </el-collapse>
        </section>

        <section v-if="hasSemanticEvidence" class="workbench-section">
          <div class="section-heading">
            <div>
              <span class="section-index">03</span>
              <h3>召回与最终绑定</h3>
            </div>
            <small v-if="!diagnosis.retrievalCandidates.length">未找到召回候选，仅展示最终绑定摘要</small>
          </div>

          <div v-if="selectedAssetRows.length" class="selected-assets">
            <span>最终选择</span>
            <el-tag
              v-for="item in selectedAssetRows"
              :key="`${item.type}-${item.key}`"
              effect="plain"
            >
              {{ assetTypeLabel(item.type) }} · {{ item.key }}
            </el-tag>
          </div>

          <el-table
            v-if="diagnosis.retrievalCandidates.length"
            :data="diagnosis.retrievalCandidates.slice(0, 30)"
            size="small"
            border
          >
            <el-table-column label="候选资产" min-width="210">
              <template #default="scope">
                <strong>{{ scope.row.assetKey }}</strong>
                <div class="muted">
                  {{ assetTypeLabel(scope.row.assetType) }} · {{ scope.row.modelCode || '-' }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="融合得分（RRF）" width="130">
              <template #default="scope">{{ Number(scope.row.rrfScore || 0).toFixed(4) }}</template>
            </el-table-column>
            <el-table-column label="通道排名" min-width="220">
              <template #default="scope">{{ compactRanks(scope.row.channelRanks) }}</template>
            </el-table-column>
          </el-table>
        </section>

        <section class="workbench-section">
          <div class="section-heading">
            <div>
              <span class="section-index">04</span>
              <h3>最小修复</h3>
            </div>
            <small>优先局部修复；只有项目级语义变化才进入治理发布</small>
          </div>

          <el-tabs v-model="repairTab" class="repair-tabs">
            <el-tab-pane label="语义映射" name="binding">
              <el-alert
                v-if="!actionEnabled('CORRECT_BINDING')"
                type="info"
                :closable="false"
                show-icon
                title="当前语义纠正暂不可用，系统正在保护已发布语义与当前运行状态。"
              />
              <el-form label-position="top" class="repair-form">
                <el-form-item label="错误的是哪类业务含义">
                  <el-select
                    v-model="bindingForm.assetType"
                    :disabled="!actionEnabled('CORRECT_BINDING')"
                    @change="loadCorrectionOptions"
                  >
                    <el-option label="指标" value="METRIC" />
                    <el-option label="维度" value="DIMENSION" />
                    <el-option label="枚举值" value="ENUM_VALUE" />
                  </el-select>
                </el-form-item>
                <el-form-item label="原问题中的错误词组">
                  <el-input
                    v-model="bindingForm.rawExpression"
                    :disabled="!actionEnabled('CORRECT_BINDING')"
                    placeholder="输入原问题中的业务词或短语"
                  />
                </el-form-item>
                <el-form-item label="正确业务资产">
                  <el-select
                    v-model="bindingForm.assetKey"
                    filterable
                    :loading="optionsLoading"
                    :disabled="!actionEnabled('CORRECT_BINDING')"
                    placeholder="选择受治理资产"
                  >
                    <el-option
                      v-for="option in correctionOptions"
                      :key="option.assetKey"
                      :value="option.assetKey"
                      :label="`${option.businessLabel} · ${option.assetKey}`"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="影响范围">
                  <el-radio-group
                    v-model="bindingForm.scope"
                    :disabled="!actionEnabled('CORRECT_BINDING')"
                  >
                    <el-radio-button value="QUERY">仅本次</el-radio-button>
                    <el-radio-button value="USER">记住我的习惯</el-radio-button>
                    <el-radio-button value="PROJECT">作为项目统一定义</el-radio-button>
                  </el-radio-group>
                </el-form-item>
                <div class="repair-submit">
                  <span>
                    “仅本次”和“记住我的习惯”会立即按当前选择重新查询；“作为项目统一定义”会先用于本次查询，并进入项目语义验证与发布流程。
                  </span>
                  <el-button
                    type="primary"
                    :loading="mutating"
                    :disabled="!canSubmitBinding"
                    @click="submitBindingCorrection"
                  >
                    修正并重新查询
                  </el-button>
                </div>
              </el-form>
            </el-tab-pane>

            <el-tab-pane label="业务定义" name="definition">
              <el-alert
                v-if="!actionEnabled('PROPOSE_DEFINITION')"
                type="info"
                :closable="false"
                show-icon
                title="项目级定义修正需要编辑权限，并经过回归验证后才能生效。"
              />
              <el-form label-position="top" class="repair-form">
                <el-form-item label="问题类型">
                  <el-select
                    v-model="definitionForm.category"
                    :disabled="!actionEnabled('PROPOSE_DEFINITION')"
                  >
                    <el-option label="指标/业务定义" value="DEFINITION" />
                    <el-option label="时间口径" value="TIME" />
                    <el-option label="过滤规则" value="FILTER" />
                    <el-option label="表关系" value="RELATIONSHIP" />
                    <el-option label="规划策略" value="PLANNING" />
                  </el-select>
                </el-form-item>
                <el-form-item
                  :label="definitionForm.category === 'PLANNING' ? '正确规划原则' : '正确业务规则'"
                >
                  <el-input
                    v-model="definitionForm.correctionText"
                    type="textarea"
                    :autosize="{ minRows: 3, maxRows: 7 }"
                    :disabled="!actionEnabled('PROPOSE_DEFINITION')"
                    :placeholder="
                      definitionForm.category === 'PLANNING'
                        ? '说明这次规划哪里不合理，以及在什么情况下应该如何规划；系统会先生成待审核的规划建议。'
                        : '写清正确的业务口径、适用范围和例外情况。'
                    "
                  />
                </el-form-item>
                <div class="repair-submit">
                  <span>不会直接修改正式业务模型；系统会先生成待审核变更，并保留完整审计记录。</span>
                  <el-button
                    type="primary"
                    :loading="mutating"
                    :disabled="!canSubmitDefinition"
                    @click="submitDefinitionCorrection"
                  >
                    创建治理修复
                  </el-button>
                </div>
              </el-form>
            </el-tab-pane>
          </el-tabs>
        </section>

        <section v-if="diagnosis.governance" class="workbench-section governance-section">
          <div class="section-heading">
            <div>
              <span class="section-index">05</span>
              <h3>定向回归与发布</h3>
            </div>
            <el-tag effect="plain">{{ governanceStatusLabel(diagnosis.governance.status) }}</el-tag>
          </div>

          <div class="governance-facts">
            <div>
              <span>修复资产</span>
              <strong>
                {{ assetTypeLabel(diagnosis.governance.assetType) }} · {{ diagnosis.governance.assetKey }}
              </strong>
            </div>
            <div>
              <span>风险级别</span>
              <strong>{{ riskLabel(diagnosis.governance.riskLevel) }}</strong>
            </div>
            <div v-if="diagnosis.governance.impact">
              <span>直接受影响案例</span>
              <strong>{{ diagnosis.governance.impact.referencedAffectedCases }}</strong>
            </div>
            <div v-if="diagnosis.governance.impact">
              <span>本次回归样本</span>
              <strong>{{ diagnosis.governance.impact.totalSelectedCases }}</strong>
            </div>
          </div>

          <el-alert
            v-if="diagnosis.governance.impact"
            type="info"
            :closable="false"
            show-icon
            :title="impactDescription"
          />

          <div
            v-if="Object.keys(diagnosis.governance.replayResultCounts || {}).length"
            class="replay-results"
          >
            <span>回归结果</span>
            <el-tag
              v-for="(count, status) in diagnosis.governance.replayResultCounts"
              :key="status"
              effect="plain"
            >
              {{ replayStatusLabel(status) }} {{ count }}
            </el-tag>
          </div>

          <div class="governance-actions">
            <template v-for="action in governanceActions" :key="action.code">
              <el-button
                :type="primaryGovernanceAction(action.code) ? 'primary' : 'default'"
                :disabled="!action.enabled"
                :loading="mutating && activeMutation === action.code"
                @click="runGovernanceAction(action.code)"
              >
                {{ action.label }}
              </el-button>
            </template>
          </div>
          <p class="permission-hint">
            灰色操作表示当前运行权限不足。
          </p>
        </section>

        <el-collapse v-if="diagnosis.advanced" class="advanced-evidence">
          <el-collapse-item title="高级诊断证据（管理员）" name="advanced">
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="运行错误代码">
                {{ diagnosis.advanced.runErrorCode || '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="当前执行节点">
                {{ diagnosis.advanced.currentNode || '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="历史查询案例">
                {{ diagnosis.advanced.historicalExampleIds.join(', ') || '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="持久化事件类型">
                {{ diagnosis.advanced.eventTypes.join(' → ') || '-' }}
              </el-descriptions-item>
            </el-descriptions>
          </el-collapse-item>
        </el-collapse>
      </template>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
  import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import {
    semEvoSQLService,
    type QueryCorrectionOption,
    type QueryDiagnosis,
    type SemanticBindingScope,
  } from '@/services/semevosql';

  const props = defineProps<{
    modelValue: boolean;
    runId?: string;
  }>();

  const emit = defineEmits<{
    'update:modelValue': [value: boolean];
    rerun: [runId: string];
    'open-evolution': [candidateId: string];
  }>();

  const diagnosis = ref<QueryDiagnosis>();
  const loading = ref(false);
  const error = ref('');
  const mutating = ref(false);
  const activeMutation = ref('');
  const repairTab = ref<'binding' | 'definition'>('binding');
  const correctionOptions = ref<QueryCorrectionOption[]>([]);
  const optionsLoading = ref(false);
  let replayPollTimer: number | undefined;
  let replayPollCount = 0;

  const bindingForm = reactive({
    assetType: 'METRIC' as 'METRIC' | 'DIMENSION' | 'ENUM_VALUE',
    rawExpression: '',
    assetKey: '',
    scope: 'QUERY' as SemanticBindingScope,
  });
  const definitionForm = reactive({
    category: 'DEFINITION' as 'DEFINITION' | 'TIME' | 'FILTER' | 'RELATIONSHIP' | 'PLANNING',
    correctionText: '',
  });

  const selectedAssetRows = computed(() => {
    const selected = diagnosis.value?.selectedAssets;
    if (!selected) return [];
    return [
      ...(selected.metricCodes || []).map(key => ({ type: 'METRIC', key })),
      ...(selected.dimensionCodes || []).map(key => ({ type: 'DIMENSION', key })),
      ...(selected.ruleCodes || []).map(key => ({ type: 'RULE', key })),
      ...(selected.relationshipCodes || []).map(key => ({ type: 'RELATIONSHIP', key })),
      ...(selected.grainCodes || []).map(key => ({ type: 'GRAIN', key })),
    ];
  });
  const hasSemanticEvidence = computed(
    () =>
      selectedAssetRows.value.length > 0 || Boolean(diagnosis.value?.retrievalCandidates.length),
  );
  const confidenceType = computed(() => {
    if (diagnosis.value?.confidence === 'HIGH') return 'success';
    if (diagnosis.value?.confidence === 'MEDIUM') return 'warning';
    return 'info';
  });
  const rootCauseTone = computed(() => {
    if (diagnosis.value?.rootCause === 'NO_CONFIRMED_FAILURE') return 'neutral';
    if (diagnosis.value?.rootCause === 'UNKNOWN') return 'warning';
    return 'problem';
  });
  const governanceActions = computed(() =>
    (diagnosis.value?.repairActions || []).filter(action =>
      [
        'OPEN_EVOLUTION',
        'REVIEW_CANDIDATE',
        'CREATE_DRAFT',
        'START_REPLAY',
        'READY_FOR_PUBLISH',
        'PUBLISH_DRAFT',
        'ACTIVATE_DRAFT',
      ].includes(action.code),
    ),
  );
  const canSubmitBinding = computed(
    () =>
      actionEnabled('CORRECT_BINDING') &&
      Boolean(bindingForm.rawExpression.trim()) &&
      Boolean(bindingForm.assetKey),
  );
  const canSubmitDefinition = computed(
    () => actionEnabled('PROPOSE_DEFINITION') && Boolean(definitionForm.correctionText.trim()),
  );
  const impactDescription = computed(() => {
    const impact = diagnosis.value?.governance?.impact;
    if (!impact) return '';
    return `系统会优先覆盖 ${impact.referencedAffectedCases} 个直接引用受改资产的案例，并补 ${impact.selectedRepresentativeCases} 个代表性查询样本；本次最多 ${impact.maxCases} 条。`;
  });

  const load = async () => {
    if (!props.runId) return;
    loading.value = true;
    error.value = '';
    try {
      diagnosis.value = await semEvoSQLService.diagnosis(props.runId);
      if (!bindingForm.rawExpression && diagnosis.value.question) {
        bindingForm.rawExpression = diagnosis.value.question;
      }
      if (actionEnabled('CORRECT_BINDING') && correctionOptions.value.length === 0) {
        await loadCorrectionOptions();
      }
      scheduleReplayRefresh();
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : '查询诊断失败';
    } finally {
      loading.value = false;
    }
  };

  const loadCorrectionOptions = async () => {
    if (!props.runId || !actionEnabled('CORRECT_BINDING')) return;
    optionsLoading.value = true;
    bindingForm.assetKey = '';
    try {
      correctionOptions.value = (
        await semEvoSQLService.correctionOptions(props.runId, bindingForm.assetType)
      ).options;
    } catch (caught) {
      ElMessage.error(caught instanceof Error ? caught.message : '业务资产加载失败');
    } finally {
      optionsLoading.value = false;
    }
  };

  const submitBindingCorrection = async () => {
    if (!diagnosis.value?.conversationId || !canSubmitBinding.value) return;
    const option = correctionOptions.value.find(item => item.assetKey === bindingForm.assetKey);
    if (!option) return;
    mutating.value = true;
    activeMutation.value = 'CORRECT_BINDING';
    try {
      const result = await semEvoSQLService.correctBinding(
        diagnosis.value.projectId,
        diagnosis.value.conversationId,
        diagnosis.value.runId,
        {
          rawExpression: bindingForm.rawExpression.trim(),
          assetType: bindingForm.assetType,
          assetKey: option.assetKey,
          businessLabel: option.businessLabel,
          scope: bindingForm.scope,
          idempotencyKey: crypto.randomUUID(),
        },
      );
      ElMessage.success(
        bindingForm.scope === 'PROJECT'
          ? '已按正确映射重新查询，并创建项目公共别名治理建议'
          : '已按正确映射重新查询',
      );
      emit('rerun', result.rerunId);
      await load();
    } catch (caught) {
      ElMessage.error(caught instanceof Error ? caught.message : '语义映射修正失败');
    } finally {
      mutating.value = false;
      activeMutation.value = '';
    }
  };

  const submitDefinitionCorrection = async () => {
    if (!diagnosis.value?.conversationId || !canSubmitDefinition.value) return;
    mutating.value = true;
    activeMutation.value = 'PROPOSE_DEFINITION';
    try {
      await semEvoSQLService.proposeDefinitionCorrection(
        diagnosis.value.projectId,
        diagnosis.value.conversationId,
        diagnosis.value.runId,
        definitionForm.category,
        definitionForm.correctionText.trim(),
      );
      ElMessage.success('已创建业务模型修复建议');
      await load();
    } catch (caught) {
      ElMessage.error(caught instanceof Error ? caught.message : '定义修正提交失败');
    } finally {
      mutating.value = false;
      activeMutation.value = '';
    }
  };

  const runGovernanceAction = async (code: string) => {
    const governance = diagnosis.value?.governance;
    if (!diagnosis.value || !governance) return;
    if (code === 'OPEN_EVOLUTION') {
      emit('open-evolution', governance.candidateId);
      return;
    }
    const action = diagnosis.value.repairActions.find(item => item.code === code);
    if (!action?.enabled) return;
    mutating.value = true;
    activeMutation.value = code;
    try {
      if (code === 'REVIEW_CANDIDATE') {
        await semEvoSQLService.reviewSemanticEvolution(
          governance.candidateId,
          true,
          `Query diagnosis confirmed from run ${diagnosis.value.runId}`,
        );
        ElMessage.success('修复建议已审核通过');
      } else if (code === 'CREATE_DRAFT') {
        const version = await ElMessageBox.prompt(
          '为修复草稿输入业务模型版本号，例如 2.1.0',
          '创建修复草稿',
          {
            inputPattern: /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/,
            inputErrorMessage: '版本号必须使用 x.x.x 格式',
          },
        );
        await semEvoSQLService.createSemanticEvolutionDraft(
          governance.candidateId,
          version.value.trim(),
        );
        ElMessage.success('修复草稿已创建并应用变更');
      } else if (code === 'START_REPLAY') {
        await semEvoSQLService.replaySemanticEvolution(governance.candidateId);
        ElMessage.success('定向回归验证已启动，会在后台持续执行');
      } else if (code === 'READY_FOR_PUBLISH') {
        await semEvoSQLService.readySemanticEvolution(governance.candidateId);
        ElMessage.success('已通过回归验证门禁，等待发布');
      } else if (code === 'PUBLISH_DRAFT' && governance.targetDraftVersionId) {
        await semEvoSQLService.publishProjectVersion(
          diagnosis.value.projectId,
          governance.targetDraftVersionId,
        );
        ElMessage.success('修复版本已发布');
      } else if (code === 'ACTIVATE_DRAFT' && governance.targetDraftVersionId) {
        await semEvoSQLService.activateProjectVersion(
          diagnosis.value.projectId,
          governance.targetDraftVersionId,
        );
        ElMessage.success('修复版本已激活，新会话立即使用新版本');
      }
      await refreshAfterGovernance();
    } catch (caught) {
      if (caught instanceof Error && caught.message === 'cancel') return;
      ElMessage.error(caught instanceof Error ? caught.message : '治理操作失败');
    } finally {
      mutating.value = false;
      activeMutation.value = '';
    }
  };

  const refreshAfterGovernance = async () => {
    await load();
    if (diagnosis.value?.governance?.status === 'REPLAY_RUNNING') scheduleReplayRefresh();
  };

  const scheduleReplayRefresh = () => {
    if (replayPollTimer) window.clearTimeout(replayPollTimer);
    if (diagnosis.value?.governance?.status !== 'REPLAY_RUNNING') {
      replayPollCount = 0;
      return;
    }
    if (replayPollCount >= 80) return;
    replayPollCount += 1;
    replayPollTimer = window.setTimeout(() => void load(), 1500);
  };

  const actionEnabled = (code: string) =>
    Boolean(diagnosis.value?.repairActions.find(action => action.code === code)?.enabled);
  const prettyJson = (value: unknown) => {
    if (value === undefined || value === null || value === '') return '-';
    try {
      const parsed = typeof value === 'string' ? JSON.parse(value) : value;
      return JSON.stringify(parsed, null, 2);
    } catch {
      return String(value);
    }
  };
  const compactRanks = (ranks: Record<string, number>) =>
    Object.entries(ranks || {})
      .sort(([, left], [, right]) => left - right)
      .map(([channel, rank]) => `${channelLabel(channel)} #${rank}`)
      .join(' · ') || '-';
  const runStatusLabel = (value?: string) =>
    ({
      QUEUED: '排队中',
      RUNNING: '执行中',
      SUCCEEDED: '已完成',
      COMPLETED: '已完成',
      FAILED: '失败',
      CANCELLED: '已取消',
      EXPIRED: '已超时',
      INPUT_REQUIRED: '等待确认',
    })[String(value || '').toUpperCase()] || value || '未知';
  const preflightStatusLabel = (value?: string) =>
    ({ PASS: '通过', PASSED: '通过', FAIL: '未通过', FAILED: '未通过' })[
      String(value || '').toUpperCase()
    ] || value || '未知';
  const decisionLabel = (value?: string) =>
    ({ PASS: '通过', PASSED: '通过', FAIL: '未通过', FAILED: '未通过', NONE: '无' })[
      String(value || '').toUpperCase()
    ] || value || '未知';
  const checkStatusLabel = (value?: string) =>
    ({ SUCCEEDED: '通过', PASSED: '通过', FAILED: '失败', REVIEW_REQUIRED: '需复核' })[
      String(value || '').toUpperCase()
    ] || value || '待确认';
  const channelLabel = (value?: string) =>
    ({ RRF: '融合', RERANK: '重排', EXACT: '精确匹配', BM25: '关键词' })[
      String(value || '').toUpperCase()
    ] || value || '召回';
  const assetTypeLabel = (value?: string) =>
    ({
      METRIC: '指标',
      DIMENSION: '维度',
      ENUM_VALUE: '枚举值',
      MODEL: '业务对象',
      RULE: '业务规则',
      RELATIONSHIP: '业务关系',
      GRAIN: '统计粒度',
    })[String(value || '').toUpperCase()] || value || '业务资产';
  const riskLabel = (value?: string) =>
    ({ HIGH: '高风险', MEDIUM: '中风险', LOW: '低风险' })[
      String(value || '').toUpperCase()
    ] || value || '未评估';
  const replayStatusLabel = (value?: string) =>
    ({
      SUCCEEDED: '通过',
      PASSED: '通过',
      FAILED: '失败',
      REVIEW_REQUIRED: '需复核',
      RUNNING: '执行中',
      QUEUED: '排队中',
    })[String(value || '').toUpperCase()] || value || '待确认';
  const stageLabel = (stage: { code?: string; label?: string }) => {
    const byCode: Record<string, string> = {
      SEMANTIC_BLUEPRINT: '语义蓝图',
      PLANNING: '语义规划',
      SQL_GENERATION: 'SQL 生成',
      SQL_EXECUTION: 'SQL 执行',
      POST_EXECUTION_REVIEW: '执行后复核',
    };
    const code = String(stage.code || '').toUpperCase();
    return byCode[code] || String(stage.label || '').replace(/Semantic Blueprint/gi, '语义蓝图') || '执行阶段';
  };
  const stageSummary = (stage: { state?: string; summary?: string }) => {
    const raw = String(stage.summary || '').trim();
    if (!raw) return stageStateLabel(String(stage.state || ''));
    return raw
      .replace(/Semantic Blueprint/gi, '语义蓝图')
      .replace(/decision=PASS/gi, '判定：通过')
      .replace(/decision=FAILED?/gi, '判定：未通过')
      .replace(/issueType=/gi, '问题类型：')
      .replace(/evidence=/gi, '证据：')
      .replace(/\bSUCCEEDED\b/gi, '已完成');
  };
  const stageIcon = (state: string) => {
    if (state === 'PASSED') return '✓';
    if (state === 'FAILED') return '×';
    if (state === 'WAITING') return '!';
    return '?';
  };
  const stageStateLabel = (state: string) => {
    if (state === 'PASSED') return '已通过';
    if (state === 'FAILED') return '失败';
    if (state === 'WAITING') return '等待确认';
    return '证据不足';
  };
  const rootCauseLabel = (value: string) => {
    const labels: Record<string, string> = {
      RETRIEVAL_MISS: '召回缺失',
      CANDIDATE_BUILD_EMPTY: '候选构建失败',
      PLANNER_SELECTION_ERROR: 'Planner 选择错误',
      PLANNER_REJECTED: 'Planner 输出被治理拒绝',
      CLARIFICATION_REQUIRED: '需要业务澄清',
      SEMANTIC_DEFINITION_GAP: '业务定义缺口',
      PLAN_RESOLUTION_ERROR: '语义蓝图解析失败',
      SQL_COMPILATION_ERROR: 'SQL 编译失败',
      SQL_GUARD_ERROR: 'SQL 安全/准入拒绝',
      SQL_EXECUTION_ERROR: 'SQL / 数据源执行失败',
      MODEL_GATEWAY_ERROR: '模型调用层异常',
      NO_CONFIRMED_FAILURE: '尚无已确认异常',
      UNKNOWN: '暂时无法稳定归因',
    };
    return labels[value] || value;
  };
  const confidenceLabel = (value: string) => {
    if (value === 'HIGH') return '高置信归因';
    if (value === 'MEDIUM') return '中置信归因';
    return '低置信归因';
  };
  const governanceStatusLabel = (value: string) => {
    const labels: Record<string, string> = {
      CANDIDATE: '修复建议待审核',
      APPROVED: '已审核，待创建草稿',
      DRAFT_CREATED: '草稿已创建',
      PATCH_APPLIED: '变更已应用，待回归验证',
      REPLAY_RUNNING: '定向回归验证进行中',
      REPLAY_PASSED: '回归验证通过',
      REPLAY_FAILED: '回归验证未通过',
      READY_FOR_PUBLISH: '等待发布',
      PUBLISHED: '修复版本已发布',
      STALE: '修复建议已过期',
      REJECTED: '修复建议已驳回',
    };
    return labels[value] || value;
  };
  const primaryGovernanceAction = (code: string) =>
    [
      'REVIEW_CANDIDATE',
      'CREATE_DRAFT',
      'START_REPLAY',
      'READY_FOR_PUBLISH',
      'PUBLISH_DRAFT',
      'ACTIVATE_DRAFT',
    ].includes(code);

  watch(
    () => [props.modelValue, props.runId] as const,
    ([open]) => {
      if (open && props.runId) void load();
    },
    { immediate: true },
  );

  onBeforeUnmount(() => {
    if (replayPollTimer) window.clearTimeout(replayPollTimer);
  });
</script>

<style scoped>
  .diagnosis-workbench {
    display: grid;
    gap: 18px;
    padding-bottom: 28px;
  }
  .inline-action,
  .section-heading,
  .repair-submit,
  .selected-assets,
  .replay-results,
  .governance-actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .diagnosis-hero {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 20px;
    padding: 20px;
    border: 1px solid #fed7aa;
    border-radius: 16px;
    background: #fffaf5;
  }
  .diagnosis-hero.problem {
    border-color: #fecaca;
    background: #fff7f7;
  }
  .diagnosis-hero.neutral {
    border-color: #dbeafe;
    background: #f8fbff;
  }
  .diagnosis-hero h2 {
    margin: 4px 0 8px;
    color: #0f172a;
    font-size: 22px;
  }
  .diagnosis-hero p {
    max-width: 620px;
    margin: 0;
    color: #475569;
    line-height: 1.65;
  }
  .diagnosis-hero small,
  .muted,
  .section-heading small,
  .permission-hint {
    color: #94a3b8;
    font-size: 12px;
  }
  .diagnosis-hero small {
    display: block;
    margin-top: 8px;
  }
  .eyebrow,
  .section-index {
    color: #64748b;
    font-size: 11px;
    font-weight: 750;
    letter-spacing: 0.08em;
  }
  .hero-tags {
    display: flex;
    flex: 0 0 auto;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 8px;
  }
  .workbench-section {
    padding: 18px;
    border: 1px solid #e2e8f0;
    border-radius: 16px;
    background: #fff;
  }
  .section-heading {
    align-items: flex-start;
    margin-bottom: 16px;
  }
  .section-heading > div {
    display: flex;
    align-items: center;
    gap: 9px;
  }
  .section-heading h3 {
    margin: 0;
    color: #0f172a;
    font-size: 17px;
  }
  .stage-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px;
  }
  .stage-grid article {
    display: flex;
    min-width: 0;
    gap: 10px;
    padding: 12px;
    border: 1px solid #e2e8f0;
    border-radius: 12px;
    background: #f8fafc;
  }
  .stage-grid article.failed {
    border-color: #fecaca;
    background: #fff7f7;
  }
  .stage-grid article.waiting {
    border-color: #fde68a;
    background: #fffbeb;
  }
  .stage-grid article.passed .stage-icon {
    background: #dcfce7;
    color: #15803d;
  }
  .stage-grid article.failed .stage-icon {
    background: #fee2e2;
    color: #b91c1c;
  }
  .stage-grid article.waiting .stage-icon {
    background: #fef3c7;
    color: #b45309;
  }
  .stage-icon {
    display: grid;
    width: 24px;
    height: 24px;
    flex: 0 0 auto;
    place-items: center;
    border-radius: 50%;
    background: #e2e8f0;
    color: #64748b;
    font-weight: 800;
  }
  .stage-grid strong {
    color: #334155;
    font-size: 13px;
  }
  .stage-grid p {
    margin: 4px 0 0;
    color: #64748b;
    font-size: 11px;
    line-height: 1.45;
  }
  .selected-assets {
    justify-content: flex-start;
    flex-wrap: wrap;
    margin-bottom: 14px;
  }
  .selected-assets > span,
  .replay-results > span {
    color: #64748b;
    font-size: 12px;
    font-weight: 650;
  }
  .repair-form {
    padding-top: 8px;
  }
  .repair-form :deep(.el-select) {
    width: 100%;
  }
  .repair-submit {
    align-items: flex-end;
    padding-top: 4px;
  }
  .repair-submit span {
    max-width: 560px;
    color: #64748b;
    font-size: 12px;
    line-height: 1.55;
  }
  .governance-section {
    border-color: #bfdbfe;
    background: #fbfdff;
  }
  .governance-facts {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 10px;
    margin-bottom: 14px;
  }
  .governance-facts > div {
    display: grid;
    gap: 4px;
    padding: 12px;
    border-radius: 10px;
    background: #f1f5f9;
  }
  .governance-facts span {
    color: #64748b;
    font-size: 11px;
  }
  .governance-facts strong {
    color: #0f172a;
    font-size: 13px;
  }
  .replay-results {
    justify-content: flex-start;
    flex-wrap: wrap;
    margin-top: 14px;
  }
  .governance-actions {
    justify-content: flex-start;
    flex-wrap: wrap;
    margin-top: 16px;
  }
  .permission-hint {
    margin: 10px 0 0;
  }
  .pipeline-facts {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 12px;
  }
  .pipeline-collapse {
    border-top: 1px solid #e2e8f0;
  }
  .evidence-code {
    max-height: 360px;
    margin: 0;
    overflow: auto;
    padding: 12px;
    border-radius: 10px;
    background: #0f172a;
    color: #e2e8f0;
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 12px;
    line-height: 1.55;
    white-space: pre-wrap;
    word-break: break-word;
  }
  .advanced-evidence {
    border-top: 1px solid #e2e8f0;
  }
  @media (max-width: 760px) {
    .diagnosis-hero,
    .section-heading,
    .repair-submit,
    .inline-action {
      align-items: stretch;
      flex-direction: column;
    }
    .hero-tags {
      justify-content: flex-start;
    }
    .stage-grid,
    .governance-facts {
      grid-template-columns: 1fr;
    }
  }
</style>
