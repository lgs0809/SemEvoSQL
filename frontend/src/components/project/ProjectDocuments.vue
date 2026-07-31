<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section v-loading="loading" class="project-documents">
    <div class="toolbar">
      <div>
        <h2>业务资料</h2>
        <p>业务资料只参与业务模型建设和来源审计，不会直接作为问答上下文拼接。</p>
      </div>
      <div class="toolbar-actions">
        <el-select
          v-model="selectedVersionId"
          class="version-select"
          placeholder="选择版本"
          @change="loadDocuments"
        >
          <el-option
            v-for="version in versions"
            :key="version.id"
            :label="`${version.versionNumber} · ${versionStatusLabel(version.status)}`"
            :value="version.id"
          />
        </el-select>
        <el-button v-if="canEdit" type="primary" @click="openUploadDialog">上传资料</el-button>
      </div>
    </div>

    <el-alert
      v-if="selectedVersion && !canEdit"
      class="readonly-alert"
      type="info"
      show-icon
      :closable="false"
      :title="
        selectedVersion?.status !== 'DRAFT'
          ? '正式或已验证的业务模型保持只读；请创建新草稿后上传、重解析或删除资料。'
          : '当前运行权限仅允许查看，不能修改业务资料。'
      "
    />

    <el-table :data="documents" empty-text="当前版本尚未上传项目文档">
      <el-table-column label="文档" min-width="220">
        <template #default="scope">
          <strong>
            {{ scope.row.originalFilename || scope.row.sourceName || '未命名业务资料' }}
          </strong>
          <div class="subtle">{{ materialCategoryLabel(scope.row.materialCategory) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="来源" min-width="170">
        <template #default="scope">
          <el-tag size="small" effect="plain">{{ sourceTypeLabel(scope.row.sourceType) }}</el-tag>
          <div v-if="scope.row.sourceMaterialId" class="subtle">
            来源于 {{ parentDocumentName(scope.row.sourceMaterialId) }}
          </div>
          <div v-if="scope.row.sourceLocation" class="subtle">{{ scope.row.sourceLocation }}</div>
        </template>
      </el-table-column>
      <el-table-column label="解析" min-width="180">
        <template #default="scope">
          <el-tag :type="statusTagType(scope.row.status)" size="small">
            {{ documentStatusLabel(scope.row.status) }}
          </el-tag>
          <div class="subtle">
            {{ materialTypeLabel(scope.row.materialType) }} · {{ formatBytes(scope.row.fileSize) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="parseSummary" label="解析摘要" min-width="280" show-overflow-tooltip />
      <el-table-column label="更新时间" min-width="170">
        <template #default="scope">{{ formatTime(scope.row.updateTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="290" fixed="right">
        <template #default="scope">
          <el-button link type="primary" @click="showAttempts(scope.row)">解析历史</el-button>
          <el-button link type="primary" @click="showProvenance(scope.row)">来源证据</el-button>
          <el-button
            v-if="canEdit && canModifyDocument(scope.row)"
            link
            type="primary"
            @click="reparse(scope.row)"
          >
            重解析
          </el-button>
          <el-button
            v-if="canEdit && canModifyDocument(scope.row)"
            link
            type="danger"
            @click="removeDocument(scope.row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="uploadDialogVisible" title="上传项目文档" width="640px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="批量类别 / ZIP 默认类别" required>
            <div class="category-batch-control">
              <el-select v-model="uploadForm.materialCategory" filterable>
                <el-option
                  v-for="item in materialCategoryOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
              <el-button :disabled="selectedMaterials.length === 0" @click="applyBatchCategory">
                应用到全部
              </el-button>
            </div>
          </el-form-item>
          <el-form-item label="材料状态">
            <el-select v-model="uploadForm.lifecycle">
              <el-option label="当前有效" value="CURRENT" />
              <el-option label="历史材料" value="HISTORICAL" />
              <el-option label="已废弃" value="DEPRECATED" />
              <el-option label="不确定" value="UNKNOWN" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-grid">
          <el-form-item label="关联数据源">
            <el-select v-model="uploadForm.datasourceId" clearable placeholder="可选">
              <el-option
                v-for="binding in datasourceBindings"
                :key="binding.datasourceId"
                :label="`${binding.domainName} · 数据源 ${binding.datasourceId}`"
                :value="binding.datasourceId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="来源名称">
            <el-input v-model="uploadForm.sourceName" placeholder="默认使用原文件名" />
          </el-form-item>
        </div>
        <el-form-item label="来源位置">
          <el-input
            v-model="uploadForm.sourceLocation"
            placeholder="填写资料所在章节、页码或表格位置，可稍后细化"
          />
        </el-form-item>
        <el-form-item label="文件" required>
          <el-upload
            drag
            multiple
            :auto-upload="false"
            :limit="50"
            :on-change="onFileChange"
            :on-remove="onFileRemove"
            accept=".pdf,.doc,.docx,.xls,.xlsx,.csv,.txt,.md,.markdown,.json,.yaml,.yml,.sql,.java,.kt,.xml,.properties,.zip"
          >
            <div class="upload-copy">拖拽文件到此处，或点击选择</div>
            <template #tip>
              <div class="upload-tip">
                单文件不超过 20 MB；可一次选择多份文档/源码，ZIP
                项目包会在服务端安全展开后逐文件解析。
              </div>
            </template>
          </el-upload>
        </el-form-item>
        <el-table
          v-if="selectedMaterials.length"
          :data="selectedMaterials"
          size="small"
          class="selected-materials"
        >
          <el-table-column label="待上传材料" min-width="260">
            <template #default="scope">{{ scope.row.file.name }}</template>
          </el-table-column>
          <el-table-column label="分类建议 / 可修改" min-width="220">
            <template #default="scope">
              <el-select v-model="scope.row.category" filterable>
                <el-option
                  v-for="item in materialCategoryOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
      </el-form>
      <template #footer>
        <el-button @click="uploadDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="uploadDocument">
          上传并解析
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="attemptDialogVisible" title="文档解析历史" width="760px">
      <el-descriptions v-if="attemptDocument" :column="2" border class="document-summary">
        <el-descriptions-item label="文档">
          {{ attemptDocument.originalFilename || attemptDocument.sourceName }}
        </el-descriptions-item>
        <el-descriptions-item label="来源位置">
          {{ attemptDocument.sourceLocation || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="当前状态">
          {{ documentStatusLabel(attemptDocument.status) }}
        </el-descriptions-item>
      </el-descriptions>
      <el-table v-loading="attemptsLoading" :data="attempts" empty-text="暂无解析历史">
        <el-table-column prop="attemptNo" label="解析批次" width="100" />
        <el-table-column label="状态" width="140">
          <template #default="scope">
            <el-tag :type="statusTagType(scope.row.status)">
              {{ documentStatusLabel(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="extractionModel" label="解析器 / 模型" min-width="150" />
        <el-table-column prop="parseSummary" label="摘要" min-width="230" show-overflow-tooltip />
        <el-table-column label="开始时间" min-width="170">
          <template #default="scope">{{ formatTime(scope.row.startTime) }}</template>
        </el-table-column>
      </el-table>
      <el-alert
        v-if="attemptDocument?.errorMessage"
        class="error-alert"
        type="error"
        :closable="false"
        :title="attemptDocument.errorMessage"
      />
    </el-dialog>

    <el-dialog v-model="provenanceDialogVisible" title="语义资产来源证据" width="980px">
      <el-descriptions v-if="provenanceDocument" :column="2" border class="document-summary">
        <el-descriptions-item label="文档">
          {{ provenanceDocument.originalFilename || provenanceDocument.sourceName }}
        </el-descriptions-item>
        <el-descriptions-item label="来源位置">
          {{ provenanceDocument.sourceLocation || '-' }}
        </el-descriptions-item>
      </el-descriptions>
      <el-alert
        class="evidence-alert"
        type="info"
        :closable="false"
        title="已应用表示该定义已进入当前业务模型草稿；冲突表示定义与已有资产不一致，系统不会覆盖现有内容，并会生成待处理缺口。"
      />
      <el-table
        v-loading="provenanceLoading"
        :data="provenance"
        empty-text="该文档尚未产生结构化语义资产"
      >
        <el-table-column label="资产类型" width="130">
          <template #default="scope">{{ semanticAssetTypeLabel(scope.row.assetType) }}</template>
        </el-table-column>
        <el-table-column prop="assetKey" label="资产 Key" min-width="170" />
        <el-table-column label="处置" width="110">
          <template #default="scope">
            <el-tag :type="scope.row.disposition === 'APPLIED' ? 'success' : 'danger'">
              {{ dispositionLabel(scope.row.disposition) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="100">
          <template #default="scope">{{ formatConfidence(scope.row.confidence) }}</template>
        </el-table-column>
        <el-table-column prop="extractionModel" label="解析器 / 模型" min-width="150" />
        <el-table-column
          prop="sourceLocation"
          label="来源位置"
          min-width="170"
          show-overflow-tooltip
        />
        <el-table-column prop="evidence" label="证据摘要" min-width="220" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref, watch } from 'vue';
  import { ElMessage, ElMessageBox, type UploadFile, type UploadRawFile } from 'element-plus';
  import {
    semEvoSQLService,
    type MaterialCategory,
    type MaterialLifecycle,
    type ProjectDatasourceBinding,
    type ProjectDocument,
    type ProjectDocumentAttempt,
    type ProjectDocumentProvenance,
    type ProjectDocumentType,
    type SemanticProjectVersion,
  } from '@/services/semevosql';
  import { versionStatusLabel } from '@/services/displayLabels';

  const props = defineProps<{
    projectId: number;
    versions: SemanticProjectVersion[];
    canEdit?: boolean;
  }>();

  const materialCategoryOptions: Array<{ label: string; value: MaterialCategory }> = [
    { label: '数据库结构 / DDL', value: 'DATABASE_SCHEMA' },
    { label: '数据字典', value: 'DATA_DICTIONARY' },
    { label: '指标口径', value: 'METRIC_DEFINITION' },
    { label: '后端业务源码', value: 'BACKEND_SOURCE' },
    { label: 'DAO / Repository / Mapper', value: 'DATA_ACCESS_CODE' },
    { label: '历史 SQL', value: 'SQL_QUERY' },
    { label: '数据库迁移脚本', value: 'DATABASE_MIGRATION' },
    { label: '接口文档', value: 'API_DOCUMENTATION' },
    { label: '产品 / 业务需求', value: 'PRODUCT_REQUIREMENT' },
    { label: '系统设计', value: 'SYSTEM_DESIGN' },
    { label: '业务规则', value: 'BUSINESS_RULE' },
    { label: '测试材料', value: 'TEST_MATERIAL' },
    { label: '报表 / BI', value: 'REPORT_OR_BI' },
    { label: '业务术语', value: 'BUSINESS_GLOSSARY' },
    { label: '其他', value: 'OTHER' },
  ];

  const suggestedMaterialCategory = (filename: string): MaterialCategory => {
    const lower = filename.toLowerCase();
    if (/^(v\d+(?:_\d+)*__.+\.sql)$/i.test(filename) || lower.includes('migration'))
      return 'DATABASE_MIGRATION';
    if (/(^|[._-])(test|tests|spec)([._-]|$)/i.test(filename)) return 'TEST_MATERIAL';
    if (lower.includes('mapper') || lower.includes('repository') || lower.includes('dao'))
      return 'DATA_ACCESS_CODE';
    if (lower.includes('openapi') || lower.includes('swagger') || lower.includes('api-doc'))
      return 'API_DOCUMENTATION';
    if (lower.endsWith('.sql')) return 'SQL_QUERY';
    if (/\.(java|kt|py|go|ts|tsx|js)$/.test(lower)) return 'BACKEND_SOURCE';
    if (lower.includes('metric') || lower.includes('指标')) return 'METRIC_DEFINITION';
    if (lower.includes('prd') || lower.includes('requirement') || lower.includes('需求'))
      return 'PRODUCT_REQUIREMENT';
    if (lower.includes('report') || lower.includes('bi')) return 'REPORT_OR_BI';
    if (lower.includes('glossary') || lower.includes('术语')) return 'BUSINESS_GLOSSARY';
    return 'OTHER';
  };

  const selectedVersionId = ref<number>();
  const documents = ref<ProjectDocument[]>([]);
  const datasourceBindings = ref<ProjectDatasourceBinding[]>([]);
  const loading = ref(false);
  const uploadDialogVisible = ref(false);
  const uploading = ref(false);
  const selectedMaterials = ref<Array<{ file: UploadRawFile; category: MaterialCategory }>>([]);
  const attemptDialogVisible = ref(false);
  const attemptsLoading = ref(false);
  const attempts = ref<ProjectDocumentAttempt[]>([]);
  const attemptDocument = ref<ProjectDocument>();
  const provenanceDialogVisible = ref(false);
  const provenanceLoading = ref(false);
  const provenance = ref<ProjectDocumentProvenance[]>([]);
  const provenanceDocument = ref<ProjectDocument>();
  const uploadForm = reactive({
    materialCategory: 'DATA_DICTIONARY' as MaterialCategory,
    lifecycle: 'CURRENT' as MaterialLifecycle,
    datasourceId: undefined as number | undefined,
    sourceName: '',
    sourceLocation: '',
  });

  const selectedVersion = computed(() =>
    props.versions.find(version => version.id === selectedVersionId.value),
  );
  const canEdit = computed(
    () => selectedVersion.value?.status === 'DRAFT' && props.canEdit !== false,
  );

  const selectDefaultVersion = () => {
    if (selectedVersionId.value && props.versions.some(item => item.id === selectedVersionId.value))
      return;
    selectedVersionId.value =
      props.versions.find(item => item.status === 'DRAFT')?.id || props.versions[0]?.id;
  };

  const loadDocuments = async () => {
    if (!selectedVersionId.value) {
      documents.value = [];
      datasourceBindings.value = [];
      return;
    }
    loading.value = true;
    try {
      [documents.value, datasourceBindings.value] = await Promise.all([
        semEvoSQLService.projectDocuments(props.projectId, selectedVersionId.value),
        semEvoSQLService.projectDatasourceBindings(props.projectId, selectedVersionId.value),
      ]);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '项目文档加载失败');
    } finally {
      loading.value = false;
    }
  };

  const openUploadDialog = () => {
    uploadForm.materialCategory = 'DATA_DICTIONARY';
    uploadForm.lifecycle = 'CURRENT';
    uploadForm.datasourceId = undefined;
    uploadForm.sourceName = '';
    uploadForm.sourceLocation = '';
    selectedMaterials.value = [];
    uploadDialogVisible.value = true;
  };

  const refreshSelectedMaterials = (files: UploadFile[]) => {
    const existing = new Map(selectedMaterials.value.map(item => [item.file.uid, item.category]));
    selectedMaterials.value = files.flatMap(item =>
      item.raw
        ? [
            {
              file: item.raw,
              category: existing.get(item.raw.uid) || suggestedMaterialCategory(item.raw.name),
            },
          ]
        : [],
    );
  };
  const onFileChange = (_file: UploadFile, files: UploadFile[]) => refreshSelectedMaterials(files);
  const onFileRemove = (_file: UploadFile, files: UploadFile[]) => refreshSelectedMaterials(files);
  const applyBatchCategory = () => {
    selectedMaterials.value.forEach(item => {
      item.category = uploadForm.materialCategory;
    });
  };

  const uploadDocument = async () => {
    if (!selectedVersionId.value || selectedMaterials.value.length === 0) {
      ElMessage.warning('请选择版本和文件');
      return;
    }
    uploading.value = true;
    try {
      let created = 0;
      let duplicates = 0;
      for (const material of selectedMaterials.value) {
        const { file, category } = material;
        if (file.name.toLowerCase().endsWith('.zip')) {
          const result = await semEvoSQLService.uploadProjectBundle(
            props.projectId,
            selectedVersionId.value,
            {
              materialCategory: category,
              lifecycle: uploadForm.lifecycle,
              datasourceId: uploadForm.datasourceId,
              sourceName: uploadForm.sourceName || undefined,
              sourceLocation: uploadForm.sourceLocation || undefined,
              file,
            },
          );
          created += result.createdCount;
          duplicates += result.duplicateCount;
          continue;
        }
        const result = await semEvoSQLService.uploadProjectDocument(
          props.projectId,
          selectedVersionId.value,
          {
            documentType: legacyDocumentType(category),
            materialCategory: category,
            lifecycle: uploadForm.lifecycle,
            datasourceId: uploadForm.datasourceId,
            sourceName: uploadForm.sourceName || undefined,
            sourceLocation: uploadForm.sourceLocation || undefined,
            file,
          },
        );
        if (result.duplicate) duplicates += 1;
        else created += 1;
      }
      ElMessage.success(
        `已解析 ${created} 份材料${duplicates ? `，跳过 ${duplicates} 份重复内容` : ''}`,
      );
      uploadDialogVisible.value = false;
      await loadDocuments();
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '项目材料上传失败');
    } finally {
      uploading.value = false;
    }
  };

  const showAttempts = async (document: ProjectDocument) => {
    if (!selectedVersionId.value) return;
    attemptDocument.value = document;
    attemptDialogVisible.value = true;
    attemptsLoading.value = true;
    try {
      attempts.value = await semEvoSQLService.projectDocumentAttempts(
        props.projectId,
        selectedVersionId.value,
        document.id,
      );
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '解析历史加载失败');
    } finally {
      attemptsLoading.value = false;
    }
  };

  const showProvenance = async (document: ProjectDocument) => {
    if (!selectedVersionId.value) return;
    provenanceDocument.value = document;
    provenanceDialogVisible.value = true;
    provenanceLoading.value = true;
    try {
      provenance.value = await semEvoSQLService.projectDocumentProvenance(
        props.projectId,
        selectedVersionId.value,
        document.id,
      );
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '来源证据加载失败');
    } finally {
      provenanceLoading.value = false;
    }
  };

  const reparse = async (document: ProjectDocument) => {
    if (!selectedVersionId.value) return;
    try {
      await ElMessageBox.confirm(
        `确认重新解析“${document.originalFilename || document.sourceName || document.id}”？历史解析批次会保留。`,
        '重解析项目文档',
        { type: 'warning' },
      );
      await semEvoSQLService.reparseProjectDocument(
        props.projectId,
        selectedVersionId.value,
        document.id,
      );
      ElMessage.success('文档已生成新的解析批次');
      await loadDocuments();
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '文档重解析失败');
      }
    }
  };

  const removeDocument = async (document: ProjectDocument) => {
    if (!selectedVersionId.value) return;
    try {
      await ElMessageBox.confirm(
        `确认删除“${document.originalFilename || document.sourceName || document.id}”及其解析历史？`,
        '删除项目文档',
        { type: 'warning' },
      );
      await semEvoSQLService.deleteProjectDocument(
        props.projectId,
        selectedVersionId.value,
        document.id,
      );
      ElMessage.success('项目文档已删除');
      await loadDocuments();
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '文档删除失败');
      }
    }
  };

  const legacyDocumentType = (category: MaterialCategory): ProjectDocumentType => {
    if (category === 'DATA_DICTIONARY' || category === 'DATABASE_SCHEMA') return 'DATA_DICTIONARY';
    if (category === 'METRIC_DEFINITION') return 'METRIC_SPEC';
    if (category === 'BUSINESS_GLOSSARY') return 'GLOSSARY';
    if (category === 'REPORT_OR_BI') return 'REPORT_SPEC';
    if (category === 'SQL_QUERY') return 'HISTORICAL_SQL';
    if (category === 'SYSTEM_DESIGN') return 'SYSTEM_RESPONSIBILITY';
    if (category === 'BUSINESS_RULE') return 'SYNC_POLICY';
    return 'REQUIREMENT';
  };
  const materialCategoryLabel = (category?: MaterialCategory) =>
    category
      ? materialCategoryOptions.find(item => item.value === category)?.label || category
      : '未分类材料';
  const sourceTypeLabel = (sourceType: ProjectDocument['sourceType']) =>
    ({
      INLINE: '内联材料',
      UPLOAD: '上传文件',
      CLONE: '版本继承',
      DATABASE_SCAN: '数据库扫描',
    })[sourceType] || '其他来源';
  const materialTypeLabel = (materialType?: string) =>
    ({
      JSON: 'JSON 配置',
      YAML: 'YAML 配置',
      MARKDOWN: 'Markdown 文档',
      DDL: '数据库结构',
      HISTORICAL_SQL: '历史 SQL',
    })[String(materialType || '').toUpperCase()] || materialType || '资料';
  const semanticAssetTypeLabel = (assetType?: string) =>
    ({
      MODEL: '业务对象',
      COLUMN: '字段',
      METRIC: '指标',
      DIMENSION: '维度',
      RELATIONSHIP: '业务关系',
      GRAIN: '统计粒度',
      ENUM_VALUE: '枚举值',
      RULE: '业务规则',
      GOLDEN_CASE: '验证案例',
    })[String(assetType || '').toUpperCase()] || assetType || '业务资产';
  const dispositionLabel = (disposition?: string) =>
    ({ APPLIED: '已应用', CONFLICT: '存在冲突', QUARANTINED: '已隔离' })[
      String(disposition || '').toUpperCase()
    ] || disposition || '待处理';
  const parentDocumentName = (documentId: number) => {
    const parent = documents.value.find(item => item.id === documentId);
    return parent?.originalFilename || parent?.sourceName || '其他业务资料';
  };
  const documentStatusLabel = (status: ProjectDocument['status']) =>
    ({
      RECEIVED: '已接收',
      PENDING: '等待解析',
      PARSING: '正在解析',
      PARSED: '解析完成',
      APPLIED: '已应用',
      REVIEW_REQUIRED: '需要复核',
      FAILED: '解析失败',
    })[status] || status;
  const canModifyDocument = (document: ProjectDocument) => document.sourceType !== 'DATABASE_SCAN';
  const statusTagType = (
    status: ProjectDocument['status'],
  ): 'success' | 'warning' | 'danger' | 'info' => {
    if (status === 'APPLIED') return 'success';
    if (status === 'REVIEW_REQUIRED') return 'warning';
    if (status === 'FAILED') return 'danger';
    return 'info';
  };
  const formatTime = (value?: string) => (value ? new Date(value).toLocaleString('zh-CN') : '-');
  const formatConfidence = (value?: number) =>
    value == null || Number.isNaN(Number(value)) ? '-' : `${(Number(value) * 100).toFixed(1)}%`;
  const formatBytes = (value?: number) => {
    if (value == null) return '内联内容';
    if (value < 1024) return `${value} B`;
    if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
    return `${(value / 1024 / 1024).toFixed(1)} MB`;
  };

  watch(
    () => props.versions,
    async () => {
      selectDefaultVersion();
      await loadDocuments();
    },
    { deep: true },
  );

  onMounted(async () => {
    selectDefaultVersion();
    await loadDocuments();
  });
</script>

<style scoped>
  .project-documents {
    min-height: 340px;
  }
  .toolbar {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 20px;
    margin-bottom: 18px;
  }
  .toolbar h2 {
    margin: 0 0 6px;
    color: #0f172a;
  }
  .toolbar p {
    margin: 0;
    color: #64748b;
  }
  .toolbar-actions {
    display: flex;
    gap: 10px;
  }
  .version-select {
    width: 220px;
  }
  .readonly-alert,
  .document-summary,
  .error-alert,
  .evidence-alert {
    margin-bottom: 16px;
  }
  .subtle {
    margin-top: 4px;
    color: #94a3b8;
    font-size: 12px;
  }
  .form-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 14px;
  }
  .upload-copy {
    padding: 18px;
    color: #334155;
  }
  .upload-tip {
    color: #94a3b8;
    font-size: 12px;
  }
  @media (max-width: 800px) {
    .toolbar,
    .toolbar-actions {
      width: 100%;
      flex-direction: column;
    }
    .version-select {
      width: 100%;
    }
    .form-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
