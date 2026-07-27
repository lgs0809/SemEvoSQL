<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section class="governance" v-loading="loading">
    <div class="toolbar">
      <div>
        <h2>业务模型建议</h2>
        <p>
          系统把真实查询、纠错和独立证据整理成可审核的业务模型改进建议；正式版本不会被直接修改。
        </p>
      </div>
      <div class="filters">
        <el-select v-model="status" placeholder="筛选建议状态" clearable @change="load">
          <el-option label="全部状态" value="" />
          <el-option
            v-for="item in statuses"
            :key="item"
            :label="statusLabel(item)"
            :value="item"
          />
        </el-select>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="批准建议只会进入草稿；自动回归结果、人工证明和最终发布决定始终作为三个独立治理事实保留。"
    />

    <el-table :data="candidates" empty-text="暂无业务模型建议">
      <el-table-column label="学习建议" min-width="320">
        <template #default="scope">
          <strong>{{ suggestionTitle(scope.row) }}</strong>
          <div class="subtle">{{ suggestionDescription(scope.row) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="来自" min-width="210">
        <template #default="scope">
          <strong>{{ scope.row.evidence_count || 0 }} 次使用</strong>
          <div class="subtle">
            {{ scope.row.distinct_user_count || 0 }} 个用户 ·
            {{ scope.row.distinct_root_evidence_count || 0 }} 个独立证据
          </div>
        </template>
      </el-table-column>
      <el-table-column label="证据质量" width="150">
        <template #default="scope">
          <strong>{{ percent(scope.row.confidence) }}</strong>
          <div>
            <el-tag size="small" :type="riskType(scope.row.risk_level)">
              {{ riskLabel(scope.row.risk_level) }}
            </el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="覆盖与验证" min-width="230">
        <template #default="scope">
          <strong>{{ scope.row.distinct_conversation_count || 0 }} 个独立会话</strong>
          <div class="subtle">{{ replaySummaryText(scope.row.id) }}</div>
          <div v-if="scope.row.status === 'REPLAY_RUNNING'" class="subtle">
            回归进度 {{ replayFor(scope.row.id)?.progress || 0 }}%
          </div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="170">
        <template #default="scope">
          <el-tag :type="statusType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag>
          <div v-if="scope.row.status === 'REPLAY_RUNNING'" class="subtle">
            {{ replayFor(scope.row.id)?.progress || 0 }}% ·
            {{ replayLevelLabel(replayFor(scope.row.id)?.currentLevel) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="390" fixed="right">
        <template #default="scope">
          <el-button link type="primary" @click="open(scope.row)">查看详情</el-button>
          <template v-if="canGovern">
            <template v-if="scope.row.status === 'CANDIDATE'">
              <el-button
                link
                type="success"
                :disabled="isTrueAmbiguity(scope.row)"
                @click="review(scope.row, true)"
              >
                批准
              </el-button>
              <el-button link @click="review(scope.row, false)">忽略</el-button>
            </template>
            <el-button
              v-if="scope.row.status === 'APPROVED'"
              link
              type="primary"
              :disabled="isTrueAmbiguity(scope.row)"
              @click="createDraft(scope.row)"
            >
              应用到草稿
            </el-button>
            <el-button
              v-if="['PATCH_APPLIED', 'REPLAY_FAILED'].includes(scope.row.status)"
              link
              type="success"
              @click="startReplay(scope.row)"
            >
              运行回归验证
            </el-button>
            <el-button
              v-if="scope.row.status === 'REPLAY_RUNNING'"
              link
              type="danger"
              @click="cancelReplay(scope.row)"
            >
              取消回归
            </el-button>
            <el-dropdown
              v-if="['PATCH_APPLIED', 'REPLAY_FAILED'].includes(scope.row.status)"
              trigger="click"
              @command="manualReplayCommand(scope.row, $event)"
            >
              <el-button link type="warning">高级治理</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="pass">登记替代证明或风险例外</el-dropdown-item>
                  <el-dropdown-item command="fail">登记人工拒绝</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button
              v-if="scope.row.status === 'REPLAY_PASSED'"
              link
              type="success"
              @click="ready(scope.row)"
            >
              提交发布门禁
            </el-button>
            <el-button
              v-if="activeStatus(scope.row.status)"
              link
              type="warning"
              @click="stale(scope.row)"
            >
              标记过期
            </el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="drawerVisible" title="业务模型建议审核" size="76%">
      <template v-if="selected">
        <el-alert
          v-if="isTrueAmbiguity(selected)"
          type="error"
          :closable="false"
          show-icon
          title="真实歧义需要显式补充业务语义材料并完成消歧，不能自动批准、创建草稿或应用变更。"
        />
        <el-descriptions :column="2" border>
          <el-descriptions-item label="候选类型">
            {{ candidateTypeLabel(selected.candidate_type) }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ statusLabel(selected.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="资产">
            {{ semanticAssetTypeLabel(selected.asset_type) }} / {{ selected.asset_key }}
          </el-descriptions-item>
          <el-descriptions-item label="审核人">
            {{ selected.reviewed_by || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="映射判定">
            <el-tag :type="mappingType(selected.mapping_classification)">
              {{ mappingLabel(selected.mapping_classification) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="主导 / 冲突 / 熵">
            {{ percent(distribution(selected).dominantRatio) }} /
            {{ percent(distribution(selected).conflictRatio) }} /
            {{ decimal(distribution(selected).normalizedEntropy) }}
          </el-descriptions-item>
          <el-descriptions-item label="独立会话 / 用户">
            {{ selected.distinct_conversation_count || 0 }} /
            {{ selected.distinct_user_count || 0 }}
          </el-descriptions-item>
          <el-descriptions-item label="根证据 / 时间窗口">
            {{ selected.distinct_root_evidence_count || 0 }} /
            {{ selected.distinct_time_window_count || 0 }}
          </el-descriptions-item>
          <el-descriptions-item label="原子应用时间">
            {{ formatTime(selected.applied_time) }}
          </el-descriptions-item>
        </el-descriptions>

        <h3>解析结果分布</h3>
        <el-table :data="resolutionDistribution" size="small" empty-text="暂无解析分布">
          <el-table-column prop="resolution" label="解析目标" min-width="260" />
          <el-table-column prop="count" label="独立根证据数" width="140" />
          <el-table-column label="占比" width="120">
            <template #default="scope">{{ percent(scope.row.ratio) }}</template>
          </el-table-column>
        </el-table>

        <div class="section-title">
          <div>
            <h3>业务模型变更项</h3>
            <p>变更会绑定创建时的业务模型基线；待审核状态可调整具体变更操作。</p>
          </div>
          <div class="actions" v-if="canGovern && selected.status === 'CANDIDATE'">
            <el-button :disabled="isTrueAmbiguity(selected)" @click="runPreflight">
              校验变更
            </el-button>
            <el-button
              type="primary"
              :loading="savingPatch"
              :disabled="isTrueAmbiguity(selected)"
              @click="savePatch"
            >
              保存变更
            </el-button>
          </div>
        </div>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="系统会在保存、回归和发布前校验该变更仍然基于创建时的业务模型基线，避免把过期变更应用到新版本。"
        />

        <el-alert
          v-if="clientValidation.length"
          type="error"
          :closable="false"
          show-icon
          :title="`变更格式校验失败（${clientValidation.length} 项）`"
        >
          <ul>
            <li v-for="item in clientValidation" :key="item">{{ item }}</li>
          </ul>
        </el-alert>
        <div v-if="preflight" class="preflight">
          <el-alert
            :type="preflight.valid ? 'success' : 'error'"
            :closable="false"
            show-icon
            :title="preflight.valid ? '变更校验通过' : '变更校验未通过'"
          />
          <el-table :data="[...preflight.errors, ...preflight.warnings]" size="small">
            <el-table-column label="级别" width="100">
              <template #default="scope">{{ validationSeverityLabel(scope.row.severity) }}</template>
            </el-table-column>
            <el-table-column prop="assetKey" label="业务资产" width="220" />
            <el-table-column prop="message" label="说明" min-width="360" />
          </el-table>
        </div>

        <div
          v-for="(operation, index) in patch.operations"
          :key="`${operation.assetKey}-${index}`"
          class="operation-card"
          :class="{ 'high-risk': isHighRisk(operation) }"
        >
          <div class="operation-head">
            <strong>#{{ index + 1 }} {{ patchOperationLabel(operation.operation) }}</strong>
            <el-tag v-if="isHighRisk(operation)" type="danger" size="small">高风险字段</el-tag>
          </div>
          <el-form
            label-position="top"
            :disabled="selected.status !== 'CANDIDATE' || isTrueAmbiguity(selected)"
          >
            <div class="form-grid">
              <el-form-item label="变更操作">
                <el-select v-model="operation.operation" filterable>
                  <el-option
                    v-for="name in operationNames"
                    :key="name"
                    :label="patchOperationLabel(name)"
                    :value="name"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="资产类型">
                <el-input v-model="operation.assetType" />
              </el-form-item>
              <el-form-item label="业务资产">
                <el-input v-model="operation.assetKey" />
              </el-form-item>
            </div>
            <el-form-item label="变更内容（结构化 JSON）">
              <el-input
                v-model="operationValues[index]"
                type="textarea"
                :rows="6"
                @blur="syncOperationValues(index)"
              />
            </el-form-item>
          </el-form>
        </div>

        <h3>资产级变更对比</h3>
        <el-table :data="assetDiff" empty-text="当前没有资产变更">
          <el-table-column label="资产类型" width="150">
            <template #default="scope">{{ semanticAssetTypeLabel(scope.row.assetType) }}</template>
          </el-table-column>
          <el-table-column prop="assetKey" label="业务资产" min-width="200" />
          <el-table-column label="变更" width="190">
            <template #default="scope">{{ patchOperationLabel(scope.row.operation) }}</template>
          </el-table-column>
          <el-table-column label="变更前" min-width="220">
            <template #default="scope">
              <code>{{ scope.row.before }}</code>
            </template>
          </el-table-column>
          <el-table-column label="变更后草稿" min-width="280">
            <template #default="scope">
              <pre>{{ scope.row.after }}</pre>
            </template>
          </el-table-column>
        </el-table>

        <h3>自动回归结果</h3>
        <p class="section-note">
          自动回归结果按每次执行追加保存；人工证明不会改写自动验证结果。
        </p>
        <el-progress
          v-if="selected.status === 'REPLAY_RUNNING'"
          :percentage="replayFor(selected.id)?.progress || 0"
          :status="replayFor(selected.id)?.cancelRequested ? 'exception' : undefined"
        />
        <el-descriptions v-if="replayFor(selected.id)" :column="2" border>
          <el-descriptions-item label="状态">
            {{ replayStatusLabel(replayFor(selected.id)?.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="当前阶段">
            {{ replayLevelLabel(replayFor(selected.id)?.currentLevel) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="replayFor(selected.id)?.errorMessage" label="异常说明" :span="2">
            {{ replayFor(selected.id)?.errorMessage }}
          </el-descriptions-item>
        </el-descriptions>
        <el-table :data="replayLevels" empty-text="暂无自动回归结果">
          <el-table-column label="回归级别" width="190">
            <template #default="scope">{{ replayLevelLabel(scope.row.level) }}</template>
          </el-table-column>
          <el-table-column label="验证状态" width="150">
            <template #default="scope">
              <el-tag :type="replayStatusType(scope.row.status)">{{ replayStatusLabel(scope.row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="error" label="异常说明" min-width="260" />
        </el-table>

        <h3>人工证明</h3>
        <p class="section-note">
          人工记录只表达风险例外、替代证明或拒绝，不改变自动回归的原始事实。
        </p>
        <el-table :data="manualAttestations" empty-text="暂无人工证明">
          <el-table-column label="类型" width="170">
            <template #default="scope">{{ attestationTypeLabel(String(scope.row.attestation_type || '')) }}</template>
          </el-table-column>
          <el-table-column label="人工决定" width="220">
            <template #default="scope">{{ attestationDecisionLabel(String(scope.row.decision || '')) }}</template>
          </el-table-column>
          <el-table-column prop="operator" label="操作者" width="160" />
          <el-table-column prop="reason" label="原因" min-width="260" />
          <el-table-column label="时间" width="180">
            <template #default="scope">
              {{ formatTime(String(scope.row.create_time || '')) }}
            </template>
          </el-table-column>
        </el-table>

        <h3>发布门禁决定</h3>
        <p class="section-note">发布门禁决定独立于自动回归结果和人工证明保存。</p>
        <el-table :data="releaseDecisions" empty-text="暂无发布门禁决定">
          <el-table-column label="决定" width="140">
            <template #default="scope">{{ releaseDecisionLabel(String(scope.row.decision || '')) }}</template>
          </el-table-column>
          <el-table-column label="自动回归结果" width="160">
            <template #default="scope">{{ replayStatusLabel(String(scope.row.automated_replay_result || '')) }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="依据" min-width="320" />
          <el-table-column label="时间" width="180">
            <template #default="scope">
              {{ formatTime(String(scope.row.create_time || '')) }}
            </template>
          </el-table-column>
        </el-table>

        <h3>证据链</h3>
        <p class="section-note">
          这里只展示用于判断变更可信度的证据类型和权重；原始运行载荷仅保留在诊断与审计链路中。
        </p>
        <el-table :data="selected.evidence || []" empty-text="暂无证据">
          <el-table-column label="类型" min-width="180">
            <template #default="scope">{{ evidenceTypeLabel(scope.row.evidence_type) }}</template>
          </el-table-column>
          <el-table-column prop="weight" label="权重" width="100" />
        </el-table>

        <h3>不可变审计事件</h3>
        <el-table :data="selected.events || []" empty-text="暂无审计事件">
          <el-table-column prop="sequence" label="#" width="70" />
          <el-table-column label="事件" min-width="210">
            <template #default="scope">{{ auditEventLabel(scope.row.event_type) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="250">
            <template #default="scope">
              {{ scope.row.from_status ? statusLabel(scope.row.from_status) : '-' }} →
              {{ scope.row.to_status ? statusLabel(scope.row.to_status) : '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="actor" label="操作者" width="160" />
          <el-table-column label="时间" min-width="180">
            <template #default="scope">{{ formatTime(scope.row.create_time) }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
  import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import {
    semEvoSQLService,
    type SemanticEvolutionCandidate,
    type SemanticPatch,
    type SemanticPatchOperation,
    type SemanticPatchValidationReport,
    type SemanticReplayRun,
  } from '@/services/semevosql';

  const props = defineProps<{
    projectId: number;
    canGovern?: boolean;
    focusCandidateId?: string;
  }>();
  const canGovern = computed(() => props.canGovern !== false);
  const candidates = ref<SemanticEvolutionCandidate[]>([]);
  const selected = ref<SemanticEvolutionCandidate>();
  const openedFocusCandidateId = ref('');
  const status = ref('');
  const loading = ref(false);
  const savingPatch = ref(false);
  const drawerVisible = ref(false);
  const preflight = ref<SemanticPatchValidationReport>();
  const operationValues = ref<string[]>([]);
  const operationEvidence = ref<string[]>([]);
  const replayRuns = ref<Record<string, SemanticReplayRun>>({});
  const automatedReplayResults = ref<Array<Record<string, unknown>>>([]);
  const manualAttestations = ref<Array<Record<string, unknown>>>([]);
  const releaseDecisions = ref<Array<Record<string, unknown>>>([]);
  let replayPollTimer: ReturnType<typeof setInterval> | undefined;

  const statuses = [
    'CANDIDATE',
    'APPROVED',
    'DRAFT_CREATED',
    'PATCH_APPLIED',
    'REPLAY_RUNNING',
    'REPLAY_PASSED',
    'REPLAY_FAILED',
    'READY_FOR_PUBLISH',
    'PUBLISHED',
    'REJECTED',
    'STALE',
  ];
  const operationNames: SemanticPatchOperation['operation'][] = [
    'ADD_COLUMN_SYNONYM',
    'ADD_ENUM_ALIAS',
    'ADD_ENUM_VALUE',
    'ADD_METRIC',
    'UPDATE_METRIC',
    'ADD_DIMENSION',
    'UPDATE_DIMENSION',
    'ADD_RELATIONSHIP',
    'UPDATE_RELATIONSHIP',
    'ADD_GRAIN',
    'UPDATE_GRAIN',
    'ADD_RULE',
    'UPDATE_RULE',
    'ADD',
    'UPDATE',
  ];
  const levelNames = [
    'ASSET',
    'IR',
    'SQL',
    'EXECUTION',
    'GOLDEN_ASSET',
    'GOLDEN_IR',
    'GOLDEN_SQL',
    'GOLDEN_EXECUTION',
  ];
  const patch = ref<SemanticPatch>({
    schemaVersion: 1,
    sourceVersionId: 0,
    sourceCatalogHash: '',
    operations: [],
  });

  type MappingDistributionView = {
    dominantRatio: number;
    conflictRatio: number;
    normalizedEntropy: number;
    resolutionCounts: Record<string, number>;
    independentEvidenceCount: number;
  };

  const distribution = (candidate: SemanticEvolutionCandidate): MappingDistributionView => {
    try {
      const parsed = JSON.parse(
        candidate.evidence_distribution_json || '{}',
      ) as Partial<MappingDistributionView>;
      return {
        dominantRatio: Number(parsed.dominantRatio || 0),
        conflictRatio: Number(parsed.conflictRatio || 0),
        normalizedEntropy: Number(parsed.normalizedEntropy || 0),
        resolutionCounts: parsed.resolutionCounts || {},
        independentEvidenceCount: Number(
          parsed.independentEvidenceCount || candidate.distinct_root_evidence_count || 0,
        ),
      };
    } catch {
      return {
        dominantRatio: 0,
        conflictRatio: 0,
        normalizedEntropy: 0,
        resolutionCounts: {},
        independentEvidenceCount: Number(candidate.distinct_root_evidence_count || 0),
      };
    }
  };

  const hydratePatch = (candidate: SemanticEvolutionCandidate) => {
    try {
      patch.value = JSON.parse(candidate.patch_json) as SemanticPatch;
    } catch {
      patch.value = {
        schemaVersion: 1,
        sourceVersionId: candidate.source_version_id,
        sourceCatalogHash: candidate.source_catalog_hash,
        operations: [],
      };
    }
    operationValues.value = patch.value.operations.map(item =>
      JSON.stringify(item.values || {}, null, 2),
    );
    operationEvidence.value = patch.value.operations.map(item =>
      (item.evidenceCaseIds || []).join(', '),
    );
    preflight.value = undefined;
  };

  const syncOperationValues = (index: number) => {
    try {
      const parsed = JSON.parse(operationValues.value[index] || '{}');
      if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object')
        throw new Error('values 必须是 JSON 对象');
      patch.value.operations[index].values = parsed;
    } catch (error) {
      ElMessage.error(
        error instanceof Error ? error.message : `Operation #${index + 1} values JSON 无效`,
      );
    }
  };
  const syncOperationEvidence = (index: number) => {
    patch.value.operations[index].evidenceCaseIds = (operationEvidence.value[index] || '')
      .split(',')
      .map(item => item.trim())
      .filter(Boolean);
  };
  const syncEditors = () => {
    patch.value.operations.forEach((_, index) => {
      syncOperationValues(index);
      syncOperationEvidence(index);
    });
  };

  const clientValidation = computed(() => {
    const errors: string[] = [];
    if (patch.value.schemaVersion !== 1) errors.push('变更格式版本不受支持');
    if (!patch.value.sourceVersionId) errors.push('变更基线缺失');
    if (!patch.value.sourceCatalogHash?.trim()) errors.push('业务模型基线校验信息缺失');
    if (!patch.value.operations.length) errors.push('至少需要一个业务资产变更');
    const seen = new Set<string>();
    patch.value.operations.forEach((operation, index) => {
      const prefix = `变更 #${index + 1}`;
      if (!operationNames.includes(operation.operation))
        errors.push(`${prefix} 的操作类型不受支持`);
      if (!operation.assetType?.trim()) errors.push(`${prefix} 缺少资产类型`);
      if (!operation.assetKey?.trim()) errors.push(`${prefix} 缺少业务资产`);
      if (!operation.values || Array.isArray(operation.values))
        errors.push(`${prefix} 的变更内容格式无效`);
      const isAdd = operation.operation === 'ADD' || operation.operation.startsWith('ADD_');
      if (isAdd && operation.expectedCurrentFingerprint)
        errors.push(`${prefix} 新增资产不应携带旧版本校验信息`);
      if (!isAdd && !operation.expectedCurrentFingerprint)
        errors.push(`${prefix} 更新资产缺少当前版本校验信息`);
      const key = `${operation.operation}:${operation.assetType}:${operation.assetKey}`;
      if (seen.has(key)) errors.push(`${prefix} 与同一变更中的其他操作重复`);
      seen.add(key);
    });
    return errors;
  });

  const assetDiff = computed(() =>
    patch.value.operations.map(operation => {
      const server = selected.value?.assetDiff?.find(
        item =>
          item.operation === operation.operation &&
          item.assetType === operation.assetType &&
          item.assetKey === operation.assetKey,
      );
      const before = server?.before || {};
      const after = { ...before, ...(operation.values || {}) };
      return {
        operation: operation.operation,
        assetType: operation.assetType,
        assetKey: operation.assetKey,
        before: Object.keys(before).length ? JSON.stringify(before, null, 2) : '不存在（ADD）',
        after: JSON.stringify(after, null, 2),
      };
    }),
  );

  const resolutionDistribution = computed(() => {
    if (!selected.value) return [];
    const value = distribution(selected.value);
    const total =
      value.independentEvidenceCount ||
      Object.values(value.resolutionCounts).reduce((sum, count) => sum + Number(count || 0), 0);
    return Object.entries(value.resolutionCounts)
      .map(([resolution, count]) => ({
        resolution: resolution === '__CONFLICT__' ? '同一根证据存在竞争解析' : resolution,
        count: Number(count || 0),
        ratio: total ? Number(count || 0) / total : 0,
      }))
      .sort(
        (left, right) =>
          right.count - left.count || left.resolution.localeCompare(right.resolution),
      );
  });

  const replayLevels = computed(() => {
    const results = automatedReplayResults.value;
    return levelNames.map(level => {
      const rows = results.filter(row => String(row.replay_level || row.level || '') === level);
      const latest = rows[rows.length - 1] || {};
      return {
        level,
        status: String(latest.status || 'NOT_RUN'),
        error: String(latest.error_message || latest.error || '-'),
        proof: latest.proof_json || latest.proof || latest.result_json || '-',
      };
    });
  });

  const load = async () => {
    loading.value = true;
    try {
      candidates.value = await semEvoSQLService.semanticEvolutionCandidates(
        props.projectId,
        status.value,
      );
      await resumeKnownReplays();
      if (props.focusCandidateId && openedFocusCandidateId.value !== props.focusCandidateId) {
        openedFocusCandidateId.value = props.focusCandidateId;
        const target =
          candidates.value.find(item => item.id === props.focusCandidateId) ||
          ({ id: props.focusCandidateId } as SemanticEvolutionCandidate);
        await open(target);
      }
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '语义候选加载失败');
    } finally {
      loading.value = false;
    }
  };
  const loadReplayGovernance = async (candidateId: string) => {
    [automatedReplayResults.value, manualAttestations.value, releaseDecisions.value] =
      await Promise.all([
        semEvoSQLService.semanticEvolutionReplayResults(candidateId),
        semEvoSQLService.semanticEvolutionAttestations(candidateId),
        semEvoSQLService.semanticEvolutionReleaseDecisions(candidateId),
      ]);
  };
  const open = async (candidate: SemanticEvolutionCandidate) => {
    drawerVisible.value = true;
    try {
      selected.value = await semEvoSQLService.semanticEvolutionCandidate(candidate.id);
      hydratePatch(selected.value);
      await Promise.all([refreshReplay(candidate.id), loadReplayGovernance(candidate.id)]);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '候选详情加载失败');
    }
  };
  const reloadSelected = async () => {
    if (!selected.value) return;
    selected.value = await semEvoSQLService.semanticEvolutionCandidate(selected.value.id);
    hydratePatch(selected.value);
    await loadReplayGovernance(selected.value.id);
  };
  const runPreflight = async () => {
    if (selected.value && isTrueAmbiguity(selected.value)) {
      ElMessage.error('真实歧义必须先完成显式语义消歧，不能执行变更校验');
      return false;
    }
    syncEditors();
    if (clientValidation.value.length) {
      ElMessage.error('请先修复前端 Schema 校验错误');
      return false;
    }
    preflight.value = isPolicyCandidate(selected.value!)
      ? await semEvoSQLService.preflightMultiSourcePolicyPatch(selected.value!.id, patch.value)
      : await semEvoSQLService.preflightSemanticEvolutionPatch(selected.value!.id, patch.value);
    if (!preflight.value.valid) ElMessage.error('服务端 Preflight 未通过');
    else ElMessage.success('Preflight 通过');
    return preflight.value.valid;
  };
  const savePatch = async () => {
    if (!selected.value || !(await runPreflight())) return;
    savingPatch.value = true;
    try {
      if (isPolicyCandidate(selected.value)) {
        await semEvoSQLService.updateMultiSourcePolicyPatch(selected.value.id, patch.value);
      } else {
        await semEvoSQLService.updateSemanticEvolutionPatch(selected.value.id, patch.value);
      }
      await reloadSelected();
      await load();
      ElMessage.success('变更已保存并重新加载');
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '变更保存失败');
    } finally {
      savingPatch.value = false;
    }
  };
  const review = async (candidate: SemanticEvolutionCandidate, approved: boolean) => {
    if (approved && isTrueAmbiguity(candidate)) {
      ElMessage.error('真实歧义不能直接批准；请补充语义材料并完成显式消歧');
      return;
    }
    try {
      let comment = '';
      if (!approved) {
        const response = await ElMessageBox.prompt('说明拒绝原因。', '拒绝业务模型建议', {
          inputType: 'textarea',
          inputValidator: value => Boolean(value.trim()) || '必须填写原因',
        });
        comment = response.value;
      } else {
        selected.value = await semEvoSQLService.semanticEvolutionCandidate(candidate.id);
        hydratePatch(selected.value);
        if (!(await runPreflight())) return;
        await ElMessageBox.confirm(
          '批准后只会进入草稿版本；创建草稿时变更会一次性应用，之后仍需自动回归验证与发布门禁。',
          '批准业务模型建议',
          { type: 'warning' },
        );
      }
      await semEvoSQLService.reviewSemanticEvolution(candidate.id, approved, comment);
      ElMessage.success(approved ? '建议已批准' : '建议已拒绝');
      await load();
    } catch (error) {
      if (error instanceof Error && error.message !== 'cancel') ElMessage.error(error.message);
    }
  };
  const createDraft = async (candidate: SemanticEvolutionCandidate) => {
    if (isTrueAmbiguity(candidate)) {
      ElMessage.error('真实歧义不能创建或应用草稿；请先完成显式语义消歧');
      return;
    }
    try {
      const response = await ElMessageBox.prompt(
        '输入新的用户可见版本号，例如 1.2.0。当前变更会一次性应用到新草稿版本。',
        '创建草稿版本',
        { inputValidator: value => Boolean(value.trim()) || '版本号不能为空' },
      );
      await semEvoSQLService.createSemanticEvolutionDraft(candidate.id, response.value.trim());
      ElMessage.success('草稿版本已创建，变更已一次性应用');
      await load();
    } catch (error) {
      if (error instanceof Error && error.message !== 'cancel') ElMessage.error(error.message);
    }
  };
  const startReplay = async (candidate: SemanticEvolutionCandidate) => {
    try {
      const run = await semEvoSQLService.replaySemanticEvolution(candidate.id);
      replayRuns.value[candidate.id] = run;
      localStorage.setItem(replayStorageKey(candidate.id), run.replayRunId);
      ElMessage.success(`自动回归验证已开始：${run.replayRunId}`);
      await load();
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '自动回归验证启动失败');
    }
  };
  const cancelReplay = async (candidate: SemanticEvolutionCandidate) => {
    const run = replayFor(candidate.id);
    if (!run) {
      ElMessage.error('未找到可恢复的回归任务');
      return;
    }
    await ElMessageBox.confirm(
      '取消后会记录取消状态，并尝试终止正在执行的数据库查询。',
      '取消自动回归验证',
      {
        type: 'warning',
      },
    );
    replayRuns.value[candidate.id] = await semEvoSQLService.cancelSemanticEvolutionReplay(
      run.replayRunId,
    );
    ElMessage.success('自动回归验证取消请求已提交');
  };
  const refreshReplay = async (candidateId: string) => {
    const replayRunId =
      replayRuns.value[candidateId]?.replayRunId ||
      localStorage.getItem(replayStorageKey(candidateId));
    if (!replayRunId) return;
    try {
      const run = await semEvoSQLService.semanticEvolutionReplayRun(replayRunId);
      replayRuns.value[candidateId] = run;
      if (isReplayTerminal(run.status)) {
        localStorage.removeItem(replayStorageKey(candidateId));
        if (selected.value?.id === candidateId) await reloadSelected();
      }
    } catch {
      localStorage.removeItem(replayStorageKey(candidateId));
    }
  };
  const resumeKnownReplays = async () => {
    await Promise.all(
      candidates.value
        .filter(
          candidate =>
            candidate.status === 'REPLAY_RUNNING' ||
            localStorage.getItem(replayStorageKey(candidate.id)),
        )
        .map(candidate => refreshReplay(candidate.id)),
    );
  };
  const pollReplays = async () => {
    const running = candidates.value.filter(
      candidate =>
        candidate.status === 'REPLAY_RUNNING' ||
        replayRuns.value[candidate.id]?.status === 'RUNNING',
    );
    await Promise.all(running.map(candidate => refreshReplay(candidate.id)));
    if (running.some(candidate => isReplayTerminal(replayRuns.value[candidate.id]?.status)))
      await load();
  };
  const recordReplay = async (candidate: SemanticEvolutionCandidate, passed: boolean) => {
    try {
      const response = await ElMessageBox.prompt(
        '该记录不会改写自动回归结果。请填写替代证明、风险接受或人工拒绝的完整依据。',
        passed ? '登记人工证明 / 风险例外' : '登记人工拒绝',
        {
          inputType: 'textarea',
          inputValidator: value => Boolean(value.trim()) || '必须填写审计摘要',
        },
      );
      await semEvoSQLService.recordSemanticEvolutionReplay(candidate.id, passed, response.value);
      ElMessage.success('人工证明与发布门禁决定已独立登记');
      await Promise.all([load(), loadReplayGovernance(candidate.id)]);
    } catch (error) {
      if (error instanceof Error && error.message !== 'cancel') ElMessage.error(error.message);
    }
  };
  const manualReplayCommand = (candidate: SemanticEvolutionCandidate, command: unknown) =>
    recordReplay(candidate, command === 'pass');
  const ready = async (candidate: SemanticEvolutionCandidate) => {
    await action(
      () => semEvoSQLService.readySemanticEvolution(candidate.id),
      '学习建议已提交发布门禁，等待独立发布决定',
    );
  };
  const stale = async (candidate: SemanticEvolutionCandidate) => {
    try {
      const response = await ElMessageBox.prompt(
        '说明过期原因，例如来源业务模型内容已经变化。',
        '标记建议过期',
        {
          inputType: 'textarea',
          inputValidator: value => Boolean(value.trim()) || '必须填写原因',
        },
      );
      await action(
        () => semEvoSQLService.staleSemanticEvolution(candidate.id, response.value),
        '建议已标记过期',
      );
    } catch (error) {
      if (error instanceof Error && error.message !== 'cancel') ElMessage.error(error.message);
    }
  };
  const action = async (operation: () => Promise<SemanticEvolutionCandidate>, success: string) => {
    try {
      await operation();
      ElMessage.success(success);
      await load();
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '操作失败');
    }
  };

  const replayFor = (candidateId: string) => replayRuns.value[candidateId];
  const replayStorageKey = (candidateId: string) => `qw-semantic-replay:${candidateId}`;
  const isReplayTerminal = (value?: string) =>
    ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(value || '');
  const isHighRisk = (operation: SemanticPatchOperation) =>
    (selected.value && isPolicyCandidate(selected.value)) ||
    ['METRIC', 'RELATIONSHIP', 'GRAIN', 'RULE'].includes(operation.assetType?.toUpperCase()) ||
    /formula|expression|join|cardinality|grain/i.test(JSON.stringify(operation.values || {}));
  const isPolicyCandidate = (candidate: SemanticEvolutionCandidate) =>
    candidate.asset_type?.startsWith('POLICY_') ||
    ['DATASOURCE_AUTHORITY_INCORRECT', 'MULTI_SOURCE_POLICY_INCORRECT'].includes(
      candidate.candidate_type,
    );
  const isTrueAmbiguity = (candidate: SemanticEvolutionCandidate) =>
    candidate.mapping_classification === 'TRUE_AMBIGUITY';
  const suggestionTitle = (candidate: SemanticEvolutionCandidate) => {
    const asset = candidate.asset_key || '业务资产';
    const labels: Record<string, string> = {
      ALIAS: `统一“${asset}”的业务叫法`,
      ENUM: `完善“${asset}”的枚举含义`,
      ENUM_VALUE: `完善“${asset}”的枚举含义`,
      METRIC: `修正指标“${asset}”`,
      DIMENSION: `修正维度“${asset}”`,
      RELATIONSHIP: `修正“${asset}”的业务关系`,
      GRAIN: `修正“${asset}”的数据粒度`,
      RULE: `完善业务规则“${asset}”`,
    };
    return labels[String(candidate.asset_type || '').toUpperCase()] || `改进“${asset}”的业务理解`;
  };
  const suggestionDescription = (candidate: SemanticEvolutionCandidate) =>
    isTrueAmbiguity(candidate)
      ? '现有独立证据仍指向多个含义，需要补充业务材料或人工确认。'
      : `基于 ${candidate.distinct_root_evidence_count || 0} 个独立证据形成，批准后只进入草稿，不会直接修改正式版本。`;
  const replaySummaryText = (candidateId: string) => {
    const candidate = candidates.value.find(item => item.id === candidateId);
    if (
      candidate?.status === 'REPLAY_PASSED' ||
      candidate?.status === 'READY_FOR_PUBLISH' ||
      candidate?.status === 'PUBLISHED'
    ) {
      return '自动回归通过';
    }
    if (candidate?.status === 'REPLAY_FAILED') return '自动回归存在失败';
    if (candidate?.status === 'REPLAY_RUNNING')
      return `自动回归 ${replayFor(candidateId)?.progress || 0}%`;
    return '尚未运行自动回归';
  };
  const mappingLabels: Record<string, string> = {
    STABLE_MAPPING: '稳定映射',
    TRUE_AMBIGUITY: '真实歧义',
    LOW_SAMPLE: '样本不足',
  };
  const mappingLabel = (value?: string) => (value ? mappingLabels[value] || value : '未分类');
  const mappingType = (value?: string) =>
    value === 'STABLE_MAPPING' ? 'success' : value === 'TRUE_AMBIGUITY' ? 'danger' : 'warning';
  const activeStatus = (value: string) =>
    [
      'CANDIDATE',
      'APPROVED',
      'DRAFT_CREATED',
      'PATCH_APPLIED',
      'REPLAY_RUNNING',
      'REPLAY_PASSED',
      'REPLAY_FAILED',
      'READY_FOR_PUBLISH',
    ].includes(value);
  const statusLabels: Record<string, string> = {
    CANDIDATE: '待审核',
    APPROVED: '已批准',
    DRAFT_CREATED: '草稿已创建',
    PATCH_APPLIED: '已写入草稿',
    REPLAY_RUNNING: '回归验证中',
    REPLAY_PASSED: '回归通过',
    REPLAY_FAILED: '回归失败',
    READY_FOR_PUBLISH: '等待发布决定',
    PUBLISHED: '已发布',
    REJECTED: '已忽略',
    STALE: '已过期',
  };
  const statusLabel = (value: string) => statusLabels[value] || '未知状态';
  const validationSeverityLabel = (value?: string) =>
    ({ ERROR: '错误', WARNING: '警告', INFO: '提示' })[value || ''] || '提示';
  const patchOperationLabel = (value?: string) =>
    ({
      ADD_COLUMN_SYNONYM: '新增字段同义词',
      ADD_ENUM_ALIAS: '新增枚举别名',
      ADD_ENUM_VALUE: '新增枚举值',
      ADD_METRIC: '新增指标',
      UPDATE_METRIC: '更新指标',
      ADD_DIMENSION: '新增维度',
      UPDATE_DIMENSION: '更新维度',
      ADD_RELATIONSHIP: '新增模型关系',
      UPDATE_RELATIONSHIP: '更新模型关系',
      ADD_GRAIN: '新增统计粒度',
      UPDATE_GRAIN: '更新统计粒度',
      ADD_RULE: '新增业务规则',
      UPDATE_RULE: '更新业务规则',
      ADD: '新增资产',
      UPDATE: '更新资产',
    })[value || ''] || '调整业务资产';
  const semanticAssetTypeLabel = (value?: string) =>
    ({
      MODEL: '业务模型',
      COLUMN: '字段',
      METRIC: '指标',
      DIMENSION: '维度',
      RELATIONSHIP: '模型关系',
      GRAIN: '统计粒度',
      ENUM_VALUE: '枚举值',
      RULE: '业务规则',
      GOLDEN_CASE: '验证案例',
    })[value || ''] || '业务资产';
  const candidateTypeLabel = (value?: string) =>
    ({
      ALIAS: '叫法统一',
      ENUM: '枚举含义',
      ENUM_VALUE: '枚举含义',
      METRIC: '指标定义',
      DIMENSION: '维度定义',
      RELATIONSHIP: '业务关系',
      GRAIN: '统计粒度',
      RULE: '业务规则',
      DATASOURCE_AUTHORITY_INCORRECT: '数据来源职责',
      MULTI_SOURCE_POLICY_INCORRECT: '多数据源策略',
    })[value || ''] || '业务模型改进';
  const replayStatusLabel = (value?: string) =>
    ({
      QUEUED: '等待执行',
      RUNNING: '执行中',
      SUCCEEDED: '已完成',
      FAILED: '失败',
      CANCELLED: '已取消',
      PASSED: '通过',
      REVIEW_REQUIRED: '需要复核',
    })[value || ''] || '待确认';
  const replayLevelLabel = (value?: string) =>
    ({
      ASSET: '业务资产',
      IR: '查询规划',
      SQL: 'SQL 生成',
      EXECUTION: '执行结果',
      GOLDEN_ASSET: '基准资产',
      GOLDEN_IR: '基准查询规划',
      GOLDEN_SQL: '基准 SQL',
      GOLDEN_EXECUTION: '基准执行结果',
    })[value || ''] || '等待执行';
  const evidenceTypeLabel = (value?: string) =>
    ({
      USER_CORRECTION: '用户纠正',
      BUSINESS_QUERY_SCENARIO: '业务查询场景',
      QUERY_EPISODE: '历史查询经验',
      VALIDATED_QUERY_CASE: '已验证案例',
      MANUAL_REVIEW: '人工复核',
    })[value || ''] || '查询证据';
  const attestationTypeLabel = (value?: string) =>
    ({ EXCEPTION: '风险例外', ALTERNATIVE_PROOF: '替代证明', REJECTION: '拒绝' })[value || ''] || '人工证明';
  const attestationDecisionLabel = (value?: string) =>
    ({
      APPROVED_WITH_EXCEPTION: '接受风险后通过',
      APPROVED_AS_ALTERNATIVE_PROOF: '基于替代证明通过',
      REJECTED: '拒绝发布',
    })[value || ''] || '待确认';
  const releaseDecisionLabel = (value?: string) =>
    ({ ALLOW: '允许发布', BLOCK: '阻止发布', REJECT: '拒绝发布' })[value || ''] || '待确认';
  const auditEventLabel = (value?: string) =>
    ({
      MANUAL_ATTESTATION_RECORDED: '已记录人工证明',
      VERSION_PUBLISHED: '业务模型版本已发布',
      QUERY_CASE_REBIND_COMPLETED: '验证案例版本适配完成',
      QUERY_CASE_REBIND_FAILED: '验证案例版本适配失败',
      REPLAY_STATE_RECONCILED: '回归状态已恢复',
      REPLAY_STARTED: '自动回归已开始',
      REPLAY_CANCELLED: '自动回归已取消',
      REPLAY_COMPLETED: '自动回归已完成',
      CANDIDATE_APPROVED: '变更候选已批准',
      CANDIDATE_REJECTED: '变更候选已拒绝',
      DRAFT_CREATED: '草稿版本已创建',
      PATCH_APPLIED: '变更已写入草稿',
      READY_FOR_PUBLISH: '已通过发布准备',
    })[value || ''] || '治理事件';
  const statusType = (value: string) =>
    ['PUBLISHED', 'REPLAY_PASSED', 'READY_FOR_PUBLISH'].includes(value)
      ? 'success'
      : ['REJECTED', 'REPLAY_FAILED'].includes(value)
        ? 'danger'
        : value === 'STALE'
          ? 'info'
          : 'warning';
  const replayStatusType = (value: string) =>
    value === 'PASSED'
      ? 'success'
      : ['FAILED', 'REVIEW_REQUIRED'].includes(value)
        ? 'danger'
        : 'info';
  const riskType = (value: string) =>
    value === 'HIGH' ? 'danger' : value === 'MEDIUM' ? 'warning' : 'success';
  const riskLabel = (value: string) =>
    value === 'HIGH' ? '高风险' : value === 'MEDIUM' ? '中风险' : '低风险';
  const percent = (value?: number) => `${(Number(value || 0) * 100).toFixed(0)}%`;
  const decimal = (value?: number) => Number(value || 0).toFixed(3);
  const formatTime = (value?: string) => (value ? new Date(value).toLocaleString('zh-CN') : '-');

  onMounted(async () => {
    await load();
    replayPollTimer = setInterval(() => void pollReplays(), 2000);
  });
  onBeforeUnmount(() => {
    if (replayPollTimer) clearInterval(replayPollTimer);
  });
</script>

<style scoped>
  .governance {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }
  .toolbar,
  .section-title {
    display: flex;
    justify-content: space-between;
    gap: 20px;
    align-items: flex-start;
  }
  .toolbar h2,
  .section-title h3 {
    margin: 0 0 6px;
    color: #0f172a;
  }
  .toolbar p,
  .section-title p,
  .section-note {
    margin: 0;
    color: #64748b;
  }
  .filters,
  .actions {
    display: flex;
    gap: 10px;
  }
  .filters .el-select {
    width: 180px;
  }
  .subtle {
    margin-top: 5px;
    color: #94a3b8;
    font-size: 12px;
  }
  .source-lock {
    margin: 12px 0 16px;
  }
  .preflight {
    display: grid;
    gap: 10px;
    margin: 14px 0;
  }
  .operation-card {
    padding: 16px;
    margin-top: 14px;
    border: 1px solid #e2e8f0;
    border-radius: 12px;
  }
  .operation-card.high-risk {
    border-color: #fca5a5;
    background: #fffafa;
  }
  .operation-head {
    display: flex;
    gap: 10px;
    align-items: center;
    margin-bottom: 12px;
  }
  .form-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0 16px;
  }
  pre {
    max-height: 260px;
    margin: 0;
    padding: 10px;
    overflow: auto;
    border-radius: 8px;
    background: #f8fafc;
    white-space: pre-wrap;
    word-break: break-word;
  }
  code {
    white-space: normal;
    word-break: break-all;
    font-size: 12px;
  }
  h3 {
    margin: 24px 0 10px;
  }
  h4 {
    margin-bottom: 8px;
  }
  @media (max-width: 900px) {
    .toolbar,
    .section-title {
      flex-direction: column;
    }
    .form-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
