<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section class="semantic-governance" v-loading="loading">
    <div class="governance-heading">
      <div>
        <span class="eyebrow">业务模型治理</span>
        <h2>业务模型版本与资料更新</h2>
        <p>
          新查询始终使用当前生效的业务模型版本；资料更新和查询纠错会先形成待审核变更，通过验证后再自动或人工切换版本。
        </p>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-alert
      v-if="error"
      type="warning"
      show-icon
      :closable="false"
      :title="error"
    />

    <div v-if="readiness" class="status-grid">
      <div class="status-card">
        <span>查询入口</span>
        <strong>{{ readiness.queryReady ? "已就绪" : "暂不可用" }}</strong>
        <el-tag
          :type="readiness.queryReady ? 'success' : 'danger'"
          effect="plain"
        >
          {{ readiness.queryReady ? "查询入口已就绪" : "暂不可用" }}
        </el-tag>
      </div>
      <div class="status-card">
        <span>当前业务模型版本</span>
        <strong>v{{ activeVersionLabel }}</strong>
        <small>当前所有新查询都会使用此版本</small>
      </div>
      <div class="status-card">
        <span>知识更新</span>
        <strong>{{
          readiness.knowledgeUpdateInProgress ? "处理中" : "空闲"
        }}</strong>
        <el-tag
          :type="readiness.knowledgeUpdateInProgress ? 'warning' : 'info'"
          effect="plain"
        >
          {{ readiness.knowledgeUpdateCount }} 个待处理变更
        </el-tag>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="governance-tabs">
      <el-tab-pane label="业务模型版本" name="versions">
        <el-table :data="versions" empty-text="暂无业务模型版本">
          <el-table-column label="版本" min-width="150">
            <template #default="{ row }">
              <div class="version-cell">
                <strong>v{{ row.versionNumber }}</strong>
                <el-tag
                  v-if="row.id === timeline?.activeVersionId"
                  size="small"
                  type="success"
                >
                  当前生效
                </el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="级别" width="100">
            <template #default="{ row }">{{ versionLevelLabel(row.versionLevel) }}</template>
          </el-table-column>
          <el-table-column label="原因" min-width="180">
            <template #default="{ row }">{{ versionCauseLabel(row.versionCause) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">{{ versionStatusLabel(row.status) }}</template>
          </el-table-column>
          <el-table-column label="激活时间" min-width="175">
            <template #default="{ row }">{{
              formatTime(row.activatedTime || row.publishedTime)
            }}</template>
          </el-table-column>
          <el-table-column
            v-if="canManage"
            label="操作"
            width="100"
            fixed="right"
          >
            <template #default="{ row }">
              <el-button
                v-if="
                  row.id !== timeline?.activeVersionId &&
                  row.status === 'PUBLISHED'
                "
                link
                type="warning"
                @click="rollback(row)"
              >
                回滚到此版本
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="subsection-heading">
          <h3>版本切换记录</h3>
          <span>回滚只切换当前生效版本，不会制造新的业务模型版本。</span>
        </div>
        <el-timeline v-if="timeline?.activationEvents?.length">
          <el-timeline-item
            v-for="event in timeline.activationEvents"
            :key="String(event.id)"
            :timestamp="formatTime(String(event.create_time || ''))"
          >
            <strong>{{ activationEventLabel(String(event.event_type || '')) }}</strong>
            <span class="timeline-copy">
              {{ event.from_version_id || "-" }} →
              {{ event.to_version_id || "-" }}
              <template v-if="event.reason"> · {{ event.reason }}</template>
            </span>
          </el-timeline-item>
        </el-timeline>
      </el-tab-pane>

      <el-tab-pane label="资料修订" name="corpus">
        <el-table :data="corpus" empty-text="还没有资料修订记录">
          <el-table-column prop="revisionNo" label="修订号" width="100" />
          <el-table-column label="来源" width="120">
            <template #default="{ row }">{{ materialSourceTypeLabel(row.sourceType) }}</template>
          </el-table-column>
          <el-table-column
            prop="sourceRef"
            label="资料"
            min-width="220"
            show-overflow-tooltip
          />
          <el-table-column label="业务模型变化" width="130">
            <template #default="{ row }">
              <el-tag
                :type="row.semanticDiffDetected ? 'warning' : 'info'"
                effect="plain"
              >
                {{ row.semanticDiffDetected ? "有语义变化" : "无语义变化" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="关联变更" min-width="180">
            <template #default="{ row }">
              <el-button
                v-if="row.semanticChangeSetId"
                link
                @click="openChangeSet(row.semanticChangeSetId)"
              >
                查看变更
              </el-button>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="时间" min-width="175">
            <template #default="{ row }">{{
              formatTime(row.createTime)
            }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="变更记录" name="changesets">
        <el-table :data="changeSets" empty-text="暂无业务模型变更">
          <el-table-column label="详情" width="100">
            <template #default="{ row }">
              <el-button link @click="openChangeSet(row.changeSetId)">查看</el-button>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="110">
            <template #default="{ row }">{{ changeOriginLabel(row.originType) }}</template>
          </el-table-column>
          <el-table-column label="版本级别" width="110">
            <template #default="{ row }">{{ versionLevelLabel(row.targetVersionLevel) }}</template>
          </el-table-column>
          <el-table-column label="变更原因" min-width="150">
            <template #default="{ row }">{{ rootCauseLabel(row.rootCause) }}</template>
          </el-table-column>
          <el-table-column label="风险" width="90">
            <template #default="{ row }">{{ riskLevelLabel(row.riskLevel) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="changeSetTag(row.status)" effect="plain">{{
                changeSetStatusLabel(row.status)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="affectedAssetCount" label="资产" width="80" />
          <el-table-column label="目标版本" width="100">
            <template #default="{ row }">{{
              row.materializedVersionId || "-"
            }}</template>
          </el-table-column>
          <el-table-column
            v-if="canManage"
            label="操作"
            width="100"
            fixed="right"
          >
            <template #default="{ row }">
              <el-button
                v-if="
                  row.targetVersionLevel === 'MAJOR' && row.status === 'READY'
                "
                link
                type="primary"
                @click="promote(row)"
              >
                设为新业务基线
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="detailVisible" title="业务模型变更详情" size="680px">
      <div v-loading="detailLoading" class="detail-drawer">
        <el-descriptions v-if="detail" :column="1" border>
          <el-descriptions-item label="变更编号">{{
            detail.changeSet.changeSetId
          }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{
            changeSetStatusLabel(detail.changeSet.status)
          }}</el-descriptions-item>
          <el-descriptions-item label="来源">
            {{ changeOriginLabel(detail.changeSet.originType) }} ·
            {{ detail.changeSet.originRef || "-" }}
          </el-descriptions-item>
          <el-descriptions-item label="版本变化">{{
            versionLevelLabel(detail.changeSet.targetVersionLevel)
          }}</el-descriptions-item>
          <el-descriptions-item label="基准版本">{{
            semanticVersionLabel(detail.changeSet.baseSemanticVersionId)
          }}</el-descriptions-item>
          <el-descriptions-item label="生成版本">
            {{ semanticVersionLabel(detail.changeSet.materializedVersionId) }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="subsection-heading"><h3>变更项</h3></div>
        <el-table v-if="detail" :data="detail.items" size="small">
          <el-table-column label="操作" width="90">
            <template #default="{ row }">{{ operationLabel(row.operation) }}</template>
          </el-table-column>
          <el-table-column label="资产类型" width="120">
            <template #default="{ row }">{{ assetTypeLabel(row.assetType) }}</template>
          </el-table-column>
          <el-table-column prop="assetKey" label="业务资产" min-width="220" />
        </el-table>

        <div class="subsection-heading"><h3>回归验证</h3></div>
        <el-table
          v-if="detail"
          :data="detail.replayResults"
          size="small"
          empty-text="尚无回归结果"
        >
          <el-table-column prop="case_id" label="案例" min-width="150" />
          <el-table-column label="级别" width="120">
            <template #default="{ row }">{{ replayLevelLabel(row.replay_level) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">{{ replayResultStatusLabel(row.status) }}</template>
          </el-table-column>
          <el-table-column
            prop="error_message"
            label="错误"
            min-width="220"
            show-overflow-tooltip
          />
        </el-table>
      </div>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  semEvoSQLService,
  type CorpusRevision,
  type ProjectSemanticReadiness,
  type SemanticChangeSet,
  type SemanticChangeSetDetail,
  type SemanticProjectVersion,
  type SemanticVersionTimeline,
} from "@/services/semevosql";

const props = defineProps<{ projectId: number; canManage?: boolean }>();
const emit = defineEmits<{ changed: [] }>();

const loading = ref(false);
const error = ref("");
const activeTab = ref("versions");
const readiness = ref<ProjectSemanticReadiness>();
const timeline = ref<SemanticVersionTimeline>();
const corpus = ref<CorpusRevision[]>([]);
const changeSets = ref<SemanticChangeSet[]>([]);
const detail = ref<SemanticChangeSetDetail>();
const detailVisible = ref(false);
const detailLoading = ref(false);

const versions = computed<SemanticProjectVersion[]>(
  () => timeline.value?.versions || [],
);
const activeVersionLabel = computed(() => {
  const version = readiness.value?.activeVersion?.version;
  return version ? `${version.major}.${version.minor}.${version.patch}` : "-";
});
const canManage = computed(() => Boolean(props.canManage));
const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof Error ? cause.message : fallback;

const load = async () => {
  loading.value = true;
  error.value = "";
  try {
    const [nextReadiness, nextTimeline, nextCorpus, nextChangeSets] =
      await Promise.all([
        semEvoSQLService.semanticReadiness(props.projectId),
        semEvoSQLService.semanticVersionTimeline(props.projectId),
        semEvoSQLService.corpusRevisions(props.projectId),
        semEvoSQLService.semanticChangeSets(props.projectId),
      ]);
    readiness.value = nextReadiness;
    timeline.value = nextTimeline;
    corpus.value = nextCorpus;
    changeSets.value = nextChangeSets;
  } catch (cause: unknown) {
    error.value = errorMessage(cause, "语义治理状态读取失败");
  } finally {
    loading.value = false;
  }
};

const openChangeSet = async (changeSetId: string) => {
  detailVisible.value = true;
  detailLoading.value = true;
  try {
    detail.value = await semEvoSQLService.semanticChangeSet(changeSetId);
  } catch (cause: unknown) {
    ElMessage.error(errorMessage(cause, "业务模型变更读取失败"));
  } finally {
    detailLoading.value = false;
  }
};

const promote = async (changeSet: SemanticChangeSet) => {
  try {
    await ElMessageBox.confirm(
      '将该 MAJOR 级变更设为新的业务基线？',
      "设为新业务基线",
      {
        type: "warning",
        confirmButtonText: "确认设为新基线",
        cancelButtonText: "取消",
      },
    );
    await semEvoSQLService.promoteSemanticChangeSet(
      changeSet.changeSetId,
      "manual business baseline promotion",
    );
    ElMessage.success("新的 MAJOR 业务模型版本已生效");
    await load();
    emit("changed");
  } catch (cause: unknown) {
    if (cause === "cancel" || cause === "close") return;
    ElMessage.error(errorMessage(cause, "设置新业务基线失败"));
  }
};

const rollback = async (version: SemanticProjectVersion) => {
  try {
    await ElMessageBox.confirm(
      `将当前生效版本回滚到 v${version.versionNumber}？此操作不会创建新的业务模型版本。`,
      "回滚业务模型版本",
      {
        type: "warning",
        confirmButtonText: "确认回滚",
        cancelButtonText: "取消",
      },
    );
    await semEvoSQLService.rollbackSemanticVersion(
      props.projectId,
      version.id,
      `rollback to semantic version ${version.versionNumber}`,
    );
    ElMessage.success(`已回滚到 v${version.versionNumber}`);
    await load();
    emit("changed");
  } catch (cause: unknown) {
    if (cause === "cancel" || cause === "close") return;
    ElMessage.error(errorMessage(cause, "回滚失败"));
  }
};

const versionLevelLabel = (value?: string) =>
  ({ INITIAL: "初始", PATCH: "修订", MINOR: "资料更新", MAJOR: "业务基线" })[value || ""] || value || "-";
const versionCauseLabel = (value?: string) =>
  ({
    INITIALIZATION: "项目初始化",
    EPISODE_LEARNING: "查询经验沉淀",
    MANUAL_SEMANTIC_FIX: "人工语义修正",
    CORPUS_SEMANTIC_DIFF: "资料语义变化",
    BUSINESS_BASELINE_PROMOTION: "业务基线升级",
  })[value || ""] || value || "-";
const versionStatusLabel = (value?: string) =>
  ({ DRAFT: "草稿", VALIDATED: "已验证", PUBLISHED: "已发布", ARCHIVED: "已归档" })[
    value || ""
  ] || value || "-";
const semanticVersionLabel = (versionId?: number) => {
  if (!versionId) return "-";
  const version = versions.value.find(item => item.id === versionId);
  return version ? `v${version.versionNumber}` : "历史版本";
};
const changeOriginLabel = (value?: string) =>
  ({ EPISODE: "查询经验", CORPUS: "资料更新", MANUAL: "人工调整", BASELINE_PROMOTION: "业务基线升级" })[
    value || ""
  ] || "其他";
const rootCauseLabel = (value?: string) =>
  ({
    SEMANTIC_LAYER: "业务语义需要调整",
    RETRIEVAL_LAYER: "知识召回偏差",
    PLANNING_LAYER: "查询规划偏差",
    SQL_GENERATION: "SQL 生成偏差",
    DATA_QUALITY: "数据质量问题",
    SOURCE_SCHEMA: "数据结构变化",
    USER_AMBIGUITY: "问题表达存在歧义",
    MODEL_RANDOMNESS: "模型输出波动",
    INFRASTRUCTURE: "运行环境异常",
    UNKNOWN: "待确认",
  })[value || ""] || "待确认";
const riskLevelLabel = (value?: string) =>
  ({ LOW: "低", MEDIUM: "中", HIGH: "高", CRITICAL: "严重" })[value || ""] || "待评估";
const changeSetStatusLabel = (value?: string) =>
  ({
    DRAFT: "草稿",
    VALIDATING: "验证中",
    REPLAYING: "回归验证中",
    INDEXING: "索引更新中",
    READY: "待生效",
    ACTIVATING: "生效中",
    ACTIVE: "已生效",
    REJECTED: "未通过",
    FAILED: "失败",
    STALE: "已过期",
  })[value || ""] || "未知状态";
const operationLabel = (value?: string) =>
  ({ ADD: "新增", UPDATE: "更新", DELETE: "删除" })[value || ""] || "调整";
const assetTypeLabel = (value?: string) =>
  ({
    MODEL: "业务模型",
    COLUMN: "字段",
    METRIC: "指标",
    DIMENSION: "维度",
    RELATIONSHIP: "模型关系",
    GRAIN: "统计粒度",
    ENUM_VALUE: "枚举值",
    RULE: "业务规则",
    GOLDEN_CASE: "验证案例",
    PLANNING_POLICY: "规划策略",
    MERGE_POLICY: "跨源合并策略",
    AUTHORITY_RULE: "跨源授权规则",
  })[value || ""] || "业务资产";
const activationEventLabel = (value?: string) =>
  ({
    VERSION_PUBLISHED: "业务模型版本已发布",
    VERSION_ACTIVATED: "业务模型版本已激活",
    VERSION_ROLLED_BACK: "业务模型版本已回滚",
    ROLLBACK: "业务模型版本已回滚",
  })[value || ""] || "版本状态已更新";
const materialSourceTypeLabel = (value?: string) =>
  ({
    MARKDOWN: "Markdown 文档",
    YAML: "YAML 配置",
    JSON: "JSON 配置",
    DDL: "数据库结构",
    HISTORICAL_SQL: "历史 SQL",
  })[value || ""] || value || "资料";
const replayLevelLabel = (value?: string) =>
  ({
    ASSET: "业务资产",
    IR: "查询规划",
    SQL: "SQL 生成",
    EXECUTION: "执行结果",
    GOLDEN_ASSET: "基准资产",
    GOLDEN_IR: "基准查询规划",
    GOLDEN_SQL: "基准 SQL",
    GOLDEN_EXECUTION: "基准执行结果",
  })[value || ""] || "回归阶段";
const replayResultStatusLabel = (value?: string) =>
  ({ PASSED: "通过", FAILED: "失败", REVIEW_REQUIRED: "需复核", RUNNING: "执行中" })[
    value || ""
  ] || value || "待确认";
const formatTime = (value?: string) =>
  value ? new Date(value).toLocaleString("zh-CN") : "-";
const changeSetTag = (status: string) => {
  if (status === "ACTIVE") return "success";
  if (["REJECTED", "FAILED", "STALE"].includes(status)) return "danger";
  if (["READY", "ACTIVATING"].includes(status)) return "warning";
  return "info";
};

onMounted(load);
watch(() => props.projectId, load);
</script>

<style scoped>
.semantic-governance {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.governance-heading,
.subsection-heading,
.version-cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.governance-heading {
  align-items: flex-start;
}
.governance-heading h2,
.subsection-heading h3 {
  margin: 4px 0 0;
}
.governance-heading p,
.subsection-heading span,
.timeline-copy {
  color: #64748b;
}
.governance-heading p {
  margin: 8px 0 0;
  line-height: 1.65;
}
.eyebrow {
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.status-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.status-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: var(--el-bg-color-page);
}
.status-card span {
  color: #64748b;
  font-size: 12px;
}
.status-card strong {
  color: #0f172a;
  font-size: 20px;
}
.status-card code,
.json-code {
  max-width: 100%;
  overflow-wrap: anywhere;
  color: #475569;
  font-size: 12px;
}
.governance-tabs {
  min-width: 0;
}
.subsection-heading {
  margin: 24px 0 14px;
  justify-content: flex-start;
}
.detail-drawer {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
@media (max-width: 900px) {
  .status-grid {
    grid-template-columns: 1fr;
  }
}
</style>
