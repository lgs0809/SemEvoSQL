<!-- Copyright 2026 the original author or authors. Licensed under the Apache License, Version 2.0. -->
<template>
  <section v-loading="loading" class="definition-governance">
    <div class="heading">
      <div>
        <h3>项目成员建议</h3>
        <p>
          个人口径一直保留。首次公共发布需至少 3 人、合计 5
          次有效使用；已有公共口径的修改需管理员批准。
        </p>
      </div>
      <el-button :loading="loading" @click="refresh">刷新建议</el-button>
    </div>
    <el-alert
      v-if="error"
      :title="error"
      type="warning"
      :closable="false"
      show-icon
    />
    <div class="candidate-summary" aria-label="项目建议概况">
      <span
        ><strong>{{ candidates.length }}</strong> 个建议</span
      >
      <span
        ><strong>{{
          candidates.filter((row) => row.threshold_reached).length
        }}</strong>
        个贡献达标</span
      >
      <span
        ><strong>{{
          candidates.filter(
            (row) => row.assessment_json?.alignment.relation === "CONFLICT",
          ).length
        }}</strong>
        个比较记录含冲突</span
      >
    </div>
    <div class="candidate-filters" aria-label="项目建议筛选">
      <el-input
        v-model="filters.search"
        placeholder="搜索业务名称或完整含义"
        aria-label="搜索项目建议"
        clearable
      />
      <el-select v-model="filters.lifecycle" aria-label="建议演进状态">
        <el-option label="全部演进状态" value="ALL" />
        <el-option
          v-for="state in lifecycleStates"
          :key="state"
          :label="lifecycleLabel(state)"
          :value="state"
        />
      </el-select>
      <el-select v-model="filters.threshold" aria-label="建议贡献门槛">
        <el-option label="全部贡献情况" value="ALL" />
        <el-option label="已达到 3 人 5 次" value="REACHED" />
        <el-option label="仍在累计贡献" value="PENDING" />
      </el-select>
      <el-select v-model="filters.alignment" aria-label="建议公共口径比较">
        <el-option label="全部比较结果" value="ALL" />
        <el-option
          v-for="state in alignmentStates"
          :key="state"
          :label="alignmentLabel(state)"
          :value="state"
        />
      </el-select>
    </div>
    <div class="filter-result" role="status">
      <span
        >显示 {{ filteredCandidates.length }} /
        {{ candidates.length }} 个建议</span
      >
      <el-button v-if="filtersActive" link @click="clearFilters"
        >清除筛选</el-button
      >
    </div>
    <el-table
      class="candidate-desktop"
      :data="filteredCandidates"
      :empty-text="
        filtersActive
          ? '没有符合当前筛选的建议，可清除筛选查看全部'
          : '暂无允许分享的项目建议'
      "
      row-key="id"
    >
      <el-table-column type="expand">
        <template #default="{ row }: { row: ProjectDefinitionCandidate }">
          <div class="detail">
            <DefinitionCandidateMeaning
              :project-id="projectId"
              :candidate="row"
            />
            <div
              v-if="row.publication || row.lifecycle === 'READY_FOR_PUBLISH'"
              class="publication-progress"
              role="region"
              aria-label="公共口径发布进度"
            >
              <h4>{{ publicationLabel(row) }}</h4>
              <p>{{ publicationHint(row) }}</p>
              <p v-if="row.publication">
                已尝试 {{ row.publication.attempts }} 次<span
                  v-if="
                    row.publication.state === 'RETRYABLE_FAILURE' &&
                    row.publication.nextAttemptAt
                  "
                >
                  · 下次尝试：{{
                    publicationTime(row.publication.nextAttemptAt)
                  }}（服务器时间）</span
                >
              </p>
              <el-button
                v-if="row.publication?.state === 'RETRYABLE_FAILURE'"
                :loading="checkingPublication === row.id"
                :disabled="checkingPublication !== undefined"
                @click="checkPublication(row)"
                >检查发布准备</el-button
              >
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="business_name" label="业务名称" min-width="160" />
      <el-table-column label="演进状态" min-width="150">
        <template #default="{ row }">
          <el-tag :type="row.lifecycle === 'PUBLISHED' ? 'success' : 'info'">{{
            lifecycleLabel(row.lifecycle)
          }}</el-tag>
          <small v-if="!row.assessment_current && row.lifecycle !== 'PUBLISHED'"
            >依据已变化，等待重新评估</small
          >
        </template>
      </el-table-column>
      <el-table-column label="当前有效贡献" min-width="160">
        <template #default="{ row }">
          <strong
            >{{ row.contributions.validUsers }} 人 ·
            {{ row.contributions.validUses }} 次</strong
          >
          <small>{{
            row.threshold_reached
              ? "已达到人数与次数门槛"
              : "尚未达到人数与次数门槛"
          }}</small>
        </template>
      </el-table-column>
      <el-table-column label="公共口径比较" min-width="180">
        <template #default="{ row }">
          <small v-if="row.lifecycle === 'PUBLISHED'">发布前的比较记录</small>
          <span>{{
            alignmentLabel(row.assessment_json?.alignment.relation)
          }}</span>
          <small>{{ blockedLabel(row.blocked_reason) }}</small>
        </template>
      </el-table-column>
      <el-table-column label="评估与发布" min-width="200">
        <template #default="{ row }">
          <span>{{ taskLabel(row.assessment_state) }}</span>
          <small
            v-if="row.publication || row.lifecycle === 'READY_FOR_PUBLISH'"
            >{{ publicationLabel(row) }}</small
          >
        </template>
      </el-table-column>
      <el-table-column label="管理员处理" width="140">
        <template #default="{ row }">
          <el-button
            v-if="row.lifecycle !== 'PUBLISHED'"
            :disabled="!row.current_base_version_id"
            @click="review(row)"
            >审核建议</el-button
          >
          <span v-else>已保留发布记录</span>
        </template>
      </el-table-column>
    </el-table>
    <div class="candidate-mobile" aria-label="项目建议列表">
      <el-empty
        v-if="!loading && !filteredCandidates.length"
        :description="
          filtersActive
            ? '没有符合当前筛选的建议，可清除筛选查看全部'
            : '暂无允许分享的项目建议'
        "
        :image-size="72"
      />
      <article
        v-for="row in filteredCandidates"
        :key="row.id"
        class="candidate-card"
      >
        <div class="card-heading">
          <h4>{{ row.business_name }}</h4>
          <el-tag :type="row.lifecycle === 'PUBLISHED' ? 'success' : 'info'">{{
            lifecycleLabel(row.lifecycle)
          }}</el-tag>
        </div>
        <p v-if="!row.assessment_current && row.lifecycle !== 'PUBLISHED'">
          依据已变化，等待重新评估
        </p>
        <dl>
          <div>
            <dt>当前有效贡献</dt>
            <dd>
              {{ row.contributions.validUsers }} 人 ·
              {{ row.contributions.validUses }} 次
            </dd>
          </div>
          <div>
            <dt>贡献门槛</dt>
            <dd>
              {{
                row.threshold_reached
                  ? "已达到人数与次数门槛"
                  : "尚未达到人数与次数门槛"
              }}
            </dd>
          </div>
          <div>
            <dt>
              {{
                row.lifecycle === "PUBLISHED" ? "发布前比较" : "公共口径比较"
              }}
            </dt>
            <dd>
              {{ alignmentLabel(row.assessment_json?.alignment.relation) }}
            </dd>
          </div>
          <div>
            <dt>口径评估</dt>
            <dd>{{ taskLabel(row.assessment_state) }}</dd>
          </div>
          <div v-if="row.publication || row.lifecycle === 'READY_FOR_PUBLISH'">
            <dt>验证与发布</dt>
            <dd>
              {{ publicationLabel(row)
              }}<small>{{ publicationHint(row) }}</small>
              <small v-if="row.publication"
                >已尝试 {{ row.publication.attempts }} 次</small
              >
              <small
                v-if="
                  row.publication?.state === 'RETRYABLE_FAILURE' &&
                  row.publication.nextAttemptAt
                "
                >下次尝试：{{
                  publicationTime(row.publication.nextAttemptAt)
                }}（服务器时间）</small
              >
            </dd>
          </div>
        </dl>
        <el-button
          v-if="row.publication?.state === 'RETRYABLE_FAILURE'"
          :loading="checkingPublication === row.id"
          :disabled="checkingPublication !== undefined"
          @click="checkPublication(row)"
          >检查发布准备</el-button
        >
        <p v-if="row.blocked_reason">{{ blockedLabel(row.blocked_reason) }}</p>
        <details>
          <summary>查看完整含义与依据</summary>
          <DefinitionCandidateMeaning
            :project-id="projectId"
            :candidate="row"
          />
        </details>
        <el-button
          v-if="row.lifecycle !== 'PUBLISHED'"
          class="review-button"
          :disabled="!row.current_base_version_id"
          @click="review(row)"
          >审核建议</el-button
        >
        <p v-else class="published-note">已保留发布记录</p>
      </article>
    </div>
    <el-dialog
      v-model="reviewing"
      title="审核项目公共口径"
      width="720px"
      :close-on-click-modal="false"
    >
      <template v-if="selected">
        <p class="meaning">{{ selected.definition_text }}</p>
        <p>
          {{ selected.contributions.validUsers }} 人 ·
          {{ selected.contributions.validUses }} 次；内容修订
          {{ selected.content_revision }}，证据修订
          {{ selected.evidence_revision }}，当前公共基线
          {{ selected.current_base_version_id }}。
        </p>
        <el-alert
          v-if="!selected.assessment_current"
          :closable="false"
          type="warning"
          :title="`上次评估基线为 ${selected.assessed_base_version_id ?? '尚未评估'}，依据已经变化。恢复后台评估并完成核对后才能批准发布。`"
        />
        <p>{{ selected.assessment_json?.alignment.reason }}</p>
        <ul v-if="selected.assessment_json?.alignment.differences?.length">
          <li
            v-for="difference in selected.assessment_json.alignment.differences"
            :key="difference"
          >
            {{ difference }}
          </li>
        </ul>
        <el-form label-position="top">
          <el-form-item label="处理方式">
            <el-select v-model="action" aria-label="公共口径处理方式">
              <el-option
                label="提前批准首次发布"
                value="EARLY_CREATE"
                :disabled="
                  selected.assessment_json?.alignment.relation !== 'NEW' ||
                  !selected.assessment_current ||
                  !selected.assessment_json?.decision?.administratorMayApprove
                "
              />
              <el-option
                label="用明确的新名称另建指标"
                value="RENAME"
                :disabled="
                  !selected.assessment_current ||
                  !selected.assessment_json?.decision?.administratorMayApprove
                "
              />
              <el-option
                label="覆盖所选公共指标（保留旧版本）"
                value="OVERWRITE"
                :disabled="
                  !selected.assessment_current ||
                  !selected.assessment_json?.decision?.administratorMayApprove
                "
              />
              <el-option
                label="关联已等价的公共指标"
                value="ASSOCIATE"
                :disabled="
                  !selected.assessment_current ||
                  selected.assessment_json?.alignment.relation !== 'EQUIVALENT'
                "
              />
              <el-option label="拒绝项目推广，保留个人口径" value="REJECT" />
              <el-option label="暂缓处理，保留个人口径" value="DEFER" />
              <el-option label="恢复后台评估" value="RESUME" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="action === 'RENAME'" label="另建的公共名称">
            <el-input
              v-model="publicName"
              maxlength="255"
              aria-label="另建的公共名称"
            />
          </el-form-item>
          <el-form-item
            v-if="action === 'OVERWRITE' || action === 'ASSOCIATE'"
            label="当前公共目标"
          >
            <el-select v-model="targetAsset" aria-label="当前公共目标">
              <el-option
                v-for="target in selected.assessment_json?.alignment.targets ??
                []"
                :key="target"
                :label="target"
                :value="target"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="审批理由">
            <el-input
              v-model="reason"
              type="textarea"
              maxlength="2000"
              :rows="3"
              aria-label="审批理由"
            />
          </el-form-item>
        </el-form>
        <el-alert
          title="批准后仍会经过正常验证和发布；如果内容、贡献、权限或公共基线变化，需要重新审核。"
          type="info"
          :closable="false"
        />
        <el-alert
          v-if="decisionError"
          :title="decisionError"
          type="error"
          :closable="false"
        />
      </template>
      <template #footer>
        <el-button :disabled="submitting" @click="reviewing = false"
          >取消</el-button
        >
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="
            !reason.trim() ||
            (action === 'RENAME' && !publicName.trim()) ||
            ((action === 'OVERWRITE' || action === 'ASSOCIATE') && !targetAsset)
          "
          @click="submitDecision"
          >提交管理员决定</el-button
        >
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import {
  computed,
  onMounted,
  onScopeDispose,
  reactive,
  ref,
  toRaw,
  watch,
} from "vue";
import DefinitionCandidateMeaning from "./DefinitionCandidateMeaning.vue";
import {
  filterDefinitionCandidates,
  type CandidateFilter,
} from "@/utils/definition-candidate-filters";
import { ElMessage } from "element-plus";
import {
  semEvoSQLService,
  type ProjectDefinitionCandidate,
} from "@/services/semevosql";

const props = defineProps<{ projectId: number; baseVersionId?: number }>();
const emit = defineEmits<{ changed: [] }>();
const candidates = ref<ProjectDefinitionCandidate[]>([]);
const lifecycleStates = [
  "ACCUMULATING",
  "NEEDS_ADMIN_REVIEW",
  "READY_FOR_PUBLISH",
  "PUBLISHED",
  "REJECTED",
  "DEFERRED",
];
const alignmentStates = [
  "NEW",
  "EQUIVALENT",
  "CONFLICT",
  "UNCERTAIN",
  "WITHDRAWN",
  "PENDING",
];
const filters = reactive<CandidateFilter>({
  search: "",
  lifecycle: "ALL",
  threshold: "ALL",
  alignment: "ALL",
});
const filteredCandidates = computed(() =>
  filterDefinitionCandidates(candidates.value, filters),
);
const filtersActive = computed(() =>
  Boolean(
    filters.search.trim() ||
    filters.lifecycle !== "ALL" ||
    filters.threshold !== "ALL" ||
    filters.alignment !== "ALL",
  ),
);
const clearFilters = () =>
  Object.assign(filters, {
    search: "",
    lifecycle: "ALL",
    threshold: "ALL",
    alignment: "ALL",
  });
const loading = ref(false);
const checkingPublication = ref<number>();
const checkPublication = async (row: ProjectDefinitionCandidate) => {
  if (
    !row.publication ||
    !row.representation_hash ||
    checkingPublication.value !== undefined
  )
    return;
  checkingPublication.value = row.id;
  try {
    const result = await semEvoSQLService.checkProjectDefinitionPublication(
      props.projectId,
      row.id,
      {
        publicationId: row.publication.id,
        contentRevision: row.content_revision,
        representationHash: row.representation_hash,
      },
    );
    if (result.status === "NOT_READY") {
      ElMessage.info(
        `索引已准备 ${result.readyModels} / ${result.totalModels} 项。审批与当前正式版本已保留，后台将继续准备。`,
      );
    } else {
      ElMessage.success(
        "索引已准备完成，已安排原审批任务继续发布。请稍后刷新进度。",
      );
    }
    await refresh();
  } catch (cause) {
    ElMessage.error(
      cause instanceof Error ? cause.message : "检查未完成，请刷新当前发布进度",
    );
  } finally {
    checkingPublication.value = undefined;
  }
};
const error = ref("");
const reviewing = ref(false);
const selected = ref<ProjectDefinitionCandidate>();
const action = ref<
  | "EARLY_CREATE"
  | "RENAME"
  | "OVERWRITE"
  | "ASSOCIATE"
  | "REJECT"
  | "DEFER"
  | "RESUME"
>("DEFER");
const reason = ref("");
const publicName = ref("");
const targetAsset = ref("");
const submitting = ref(false);
const decisionError = ref("");
let decisionKey = "";
const review = (row: ProjectDefinitionCandidate) => {
  selected.value = structuredClone(toRaw(row));
  action.value = !row.assessment_current
    ? "RESUME"
    : row.assessment_json?.alignment.relation === "NEW" &&
        row.assessment_json?.decision?.administratorMayApprove
      ? "EARLY_CREATE"
      : "DEFER";
  reason.value = "";
  publicName.value = "";
  targetAsset.value = "";
  decisionError.value = "";
  decisionKey = crypto.randomUUID();
  reviewing.value = true;
};
const submitDecision = async () => {
  const row = selected.value;
  if (
    !row?.current_base_version_id ||
    !row.current_catalog_hash ||
    submitting.value
  )
    return;
  submitting.value = true;
  decisionError.value = "";
  try {
    await semEvoSQLService.decideProjectDefinition(
      props.projectId,
      row.id,
      {
        action: action.value,
        reason: reason.value.trim(),
        contentRevision: row.content_revision,
        evidenceRevision: row.evidence_revision,
        baseVersion: row.current_base_version_id,
        catalogHash: row.current_catalog_hash,
        contributionFingerprint: row.contributions.fingerprint,
        representationHash: row.representation_hash,
        targetAsset: targetAsset.value || undefined,
        publicName: publicName.value.trim() || undefined,
      },
      decisionKey,
    );
    reviewing.value = false;
    ElMessage.success("管理员决定已保存，发布结果由后台继续更新");
    await refresh();
  } catch (cause) {
    decisionError.value =
      cause instanceof Error ? cause.message : "提交未完成，请检查当前状态";
  } finally {
    submitting.value = false;
  }
};
const lifecycleLabel = (value: string) =>
  ({
    ACCUMULATING: "继续累计",
    NEEDS_ADMIN_REVIEW: "待管理员处理",
    READY_FOR_PUBLISH: "已批准待发布",
    PUBLISHED: "已发布",
    REJECTED: "已拒绝",
    DEFERRED: "已暂缓",
  })[value] ?? value;
const taskLabel = (value: string) =>
  ({
    PENDING: "等待后台评估",
    RUNNING: "评估中",
    RETRYABLE_FAILURE: "已保存，稍后重试",
    DONE: "评估完成",
  })[value] ?? value;
const publicationLabel = (row: ProjectDefinitionCandidate) =>
  ({
    PENDING: "等待验证与发布",
    BUILDING: "正在验证新版本",
    RETRYABLE_FAILURE: "发布暂未完成，等待重试",
    DONE: "发布已完成",
    STALE: "发布依据已变化",
  })[row.publication?.state ?? "PENDING"];
const publicationHint = (row: ProjectDefinitionCandidate) =>
  ({
    PENDING: "管理员决定已保存，系统将继续验证和发布。",
    BUILDING: "系统正在准备并验证新版本，通过后才会切换正式版本。",
    RETRYABLE_FAILURE:
      "发布准备暂未完成，任务和审批决定已保留，系统将稍后重试。当前正式版本保持可用。",
    DONE: "发布记录已保留。新查询使用项目上方显示的当前正式版本。",
    STALE: "内容、共享来源或公共基线已变化，需要重新审核。",
  })[row.publication?.state ?? "PENDING"];
const publicationTime = (value: string) =>
  value.replace("T", " ").replace(/\.\d+/, "");
const alignmentLabel = (value?: string) =>
  ({
    NEW: "首次公共定义，无冲突",
    EQUIVALENT: "与现有公共定义等价",
    CONFLICT: "与现有公共定义存在冲突",
    UNCERTAIN: "尚不能确定对应关系",
    WITHDRAWN: "共享授权已撤回",
    PENDING: "待比较",
  })[value ?? "PENDING"] ?? "待比较";
const blockedLabel = (value?: string) =>
  ({
    ADMINISTRATOR_DEFER: "管理员已暂缓，保留个人口径",
    ADMINISTRATOR_REJECT: "管理员已拒绝公共推广",
    INSUFFICIENT_CONTRIBUTIONS: "继续累计真实使用",
    STRUCTURE_PENDING: "后台正在整理可执行定义",
    PUBLIC_ALIGNMENT_PENDING: "等待核对公共含义",
    PUBLIC_CHANGE_REQUIRES_ADMIN: "修改公共含义必须审批",
    DEPENDENCY_INVALID: "基础依赖已变化，需要重新核对",
    SHARING_WITHDRAWN: "没有当前有效共享来源",
  })[value ?? ""] ?? "";
let loadGeneration = 0;
const load = async () => {
  const generation = ++loadGeneration;
  const project = props.projectId;
  loading.value = true;
  error.value = "";
  try {
    const rows = await semEvoSQLService.projectDefinitionCandidates(project);
    if (generation !== loadGeneration || project !== props.projectId)
      return false;
    candidates.value = rows;
    return true;
  } catch (cause) {
    if (generation !== loadGeneration) return false;
    error.value =
      cause instanceof Error ? cause.message : "暂时无法读取项目建议";
    return false;
  } finally {
    if (generation === loadGeneration) loading.value = false;
  }
};
const refresh = async () => {
  if (await load()) emit("changed");
};
onMounted(load);
onScopeDispose(() => {
  loadGeneration++;
});
watch(
  () => [props.projectId, props.baseVersionId],
  () => {
    reviewing.value = false;
    selected.value = undefined;
    candidates.value = [];
    clearFilters();
    void load();
  },
);
</script>

<style scoped>
.heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 20px;
}
.heading h3 {
  margin: 0 0 8px;
}
.heading p {
  color: var(--el-text-color-secondary);
  margin: 0;
  line-height: 1.6;
}
small {
  display: block;
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.detail {
  padding: 16px 24px;
  line-height: 1.7;
  background: var(--el-fill-color-light);
}
.meaning {
  white-space: pre-wrap;
}
.candidate-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 24px;
  margin: 18px 0;
  color: var(--text-secondary);
  font-size: 13px;
}
.candidate-summary strong {
  color: var(--text-primary);
  font-size: 20px;
  font-variant-numeric: tabular-nums;
}
.candidate-filters {
  display: grid;
  grid-template-columns: minmax(200px, 2fr) repeat(3, minmax(150px, 1fr));
  gap: 10px;
}
.filter-result {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 40px;
  font-size: 12px;
  color: var(--text-secondary);
}
summary {
  cursor: pointer;
  color: var(--text-secondary);
  font-size: 12px;
}
.candidate-mobile {
  display: none;
}
.candidate-card {
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  padding: 16px;
  background: var(--el-bg-color);
  min-width: 0;
  overflow-wrap: anywhere;
}
.card-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.card-heading h4 {
  margin: 0;
  font-size: 16px;
}
.candidate-card dl {
  margin: 16px 0;
  display: grid;
  gap: 12px;
}
.candidate-card dl > div {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr);
  gap: 8px;
  font-size: 13px;
}
.candidate-card dt,
.published-note {
  color: var(--text-secondary);
}
.candidate-card dd {
  margin: 0;
}
.candidate-card details > summary {
  color: var(--el-color-primary);
  padding: 8px 0;
}
.candidate-card details > .candidate-meaning {
  margin-top: 12px;
}
.review-button {
  margin-top: 16px;
  width: 100%;
}
@media (max-width: 700px) {
  .candidate-desktop {
    display: none;
  }
  .candidate-mobile {
    display: grid;
    gap: 14px;
  }
}
@media (max-width: 900px) {
  .candidate-filters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 560px) {
  .heading {
    flex-direction: column;
    gap: 12px;
  }
  .candidate-filters {
    grid-template-columns: 1fr;
  }
  .detail {
    padding: 12px;
  }
}
</style>
