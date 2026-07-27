<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <el-card class="offline-import" shadow="never">
    <h3>{{ readonly ? "导出数据库结构" : "从初始化文件建立业务模型" }}</h3>
    <p>
      {{
        readonly
          ? "查看当前版本绑定的数据结构，可用于核对业务模型和准备后续草稿。"
          : "结合数据库结构与业务资料准备初始化文件，先预览完整含义，再导入当前草稿。"
      }}
    </p>
    <WorkflowGuide
      v-if="!readonly"
      label="业务模型初始化步骤"
      :steps="initializationSteps"
    />
    <div class="import-actions">
      <el-button :loading="exporting" @click="exportSchema"
        >导出数据库结构</el-button
      >
      <label v-if="!readonly" class="file-picker" :class="{ disabled: busy }">
        <i class="bi bi-upload" aria-hidden="true"></i>选择语义资产文件
        <input
          type="file"
          accept="application/json,.json"
          aria-label="选择语义资产文件"
          :disabled="busy"
          @change="selectFile"
        />
      </label>
      <el-button
        v-if="!readonly"
        type="primary"
        :disabled="!file || busy"
        :loading="previewing"
        @click="previewFile"
        >检查并预览</el-button
      >
    </div>
    <p v-if="file && !readonly">已选择：{{ file.name }}</p>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
    />
    <template v-if="preview && !readonly">
      <p>文件检查通过，将写入当前草稿：</p>
      <ul>
        <li
          v-for="model in preview.plannedCatalog.models"
          :key="String(model.modelCode)"
        >
          {{ model.businessName || model.modelCode }}：{{ model.description }}
        </li>
      </ul>
      <p>
        {{ preview.plannedCatalog.models.length }} 个业务对象 ·
        {{ preview.plannedCatalog.metrics.length }} 个指标 ·
        {{ preview.plannedCatalog.dimensions.length }} 个维度
      </p>
      <el-alert
        v-for="issue in preview.unresolvedIssues"
        :key="issue.target + issue.question"
        :title="issue.question"
        type="warning"
        :closable="false"
      />
      <p>确认后替换当前草稿目录。正式使用前仍需完成验证、发布和激活。</p>
      <el-button
        type="success"
        :disabled="busy || committed"
        :loading="committing"
        @click="commit"
      >
        {{ committed ? "已导入草稿" : "确认导入草稿" }}
      </el-button>
    </template>
    <el-alert
      v-if="committed && !readonly"
      title="业务模型已导入草稿，可继续检查定义并验证发布。"
      type="success"
      show-icon
      :closable="false"
    />
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import WorkflowGuide from "@/components/common/WorkflowGuide.vue";
import {
  semEvoSQLService,
  type OfflineCatalogPreview,
} from "@/services/semevosql";
const props = withDefaults(
  defineProps<{ projectId: number; versionId: number; readonly?: boolean }>(),
  { readonly: false },
);
const initializationSteps = [
  {
    title: "导出数据结构",
    description: "获取当前版本实际绑定的业务表与字段。",
  },
  {
    title: "结合业务资料建模",
    description: "在 Codex 中核对对象、指标、时间与权限，生成初始化文件。",
  },
  {
    title: "预览后导入草稿",
    description: "检查完整含义和待确认问题，再进入验证与发布。",
  },
];
const emit = defineEmits<{ imported: [] }>();
const file = ref<File>();
const preview = ref<OfflineCatalogPreview>();
const error = ref("");
const committed = ref(false);
const exporting = ref(false);
const previewing = ref(false);
const committing = ref(false);
const busy = computed(
  () => exporting.value || previewing.value || committing.value,
);
const reset = () => {
  preview.value = undefined;
  error.value = "";
  committed.value = false;
};
watch(
  () => [props.projectId, props.versionId, props.readonly],
  () => {
    file.value = undefined;
    reset();
  },
);
const selectFile = (event: Event) => {
  file.value = (event.target as HTMLInputElement).files?.[0];
  reset();
};
const report = (failure: unknown) => {
  error.value =
    failure instanceof Error ? failure.message : "文件处理失败，请重试。";
};
const exportSchema = async () => {
  exporting.value = true;
  error.value = "";
  try {
    const source = await semEvoSQLService.exportSourceSchema(
      props.projectId,
      props.versionId,
    );
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(source, null, 2)], {
        type: "application/json;charset=utf-8",
      }),
    );
    const link = document.createElement("a");
    link.href = url;
    link.download = "source-schema.json";
    link.click();
    URL.revokeObjectURL(url);
  } catch (failure) {
    report(failure);
  } finally {
    exporting.value = false;
  }
};
const previewFile = async () => {
  if (props.readonly || !file.value) return;
  reset();
  previewing.value = true;
  try {
    if (file.value.size > 4 * 1024 * 1024)
      throw new Error("资产文件不能超过 4 MiB。");
    const raw = new TextDecoder("utf-8", { fatal: true }).decode(
      await file.value.arrayBuffer(),
    );
    preview.value = await semEvoSQLService.previewOfflineCatalog(
      props.projectId,
      props.versionId,
      raw,
    );
    committed.value = preview.value.status === "COMMITTED";
  } catch (failure) {
    report(failure);
  } finally {
    previewing.value = false;
  }
};
const commit = async () => {
  if (props.readonly || !preview.value) return;
  committing.value = true;
  error.value = "";
  try {
    await semEvoSQLService.commitOfflineCatalog(
      props.projectId,
      props.versionId,
      preview.value.importId,
    );
    committed.value = true;
    emit("imported");
  } catch (failure) {
    report(failure);
  } finally {
    committing.value = false;
  }
};
</script>

<style scoped>
.offline-import {
  margin: 18px 0;
}
h3 {
  margin: 0 0 12px;
}
p {
  line-height: 1.7;
}
.import-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.file-picker {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 34px;
  padding: 7px 12px;
  border: 1px solid #b9d9d2;
  border-radius: 8px;
  color: #177d73;
  font-size: 13px;
  cursor: pointer;
}
.file-picker input {
  position: absolute;
  inset: 0;
  width: 100%;
  opacity: 0;
  cursor: pointer;
}
.file-picker:focus-within {
  outline: 2px solid #177d73;
  outline-offset: 3px;
}
.file-picker.disabled {
  opacity: 0.6;
  cursor: default;
}
</style>
