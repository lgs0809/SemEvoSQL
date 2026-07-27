<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section v-loading="loading" class="datasource-bindings">
    <div class="toolbar">
      <div>
        <h2>业务模型数据源</h2>
        <p>为每个业务模型版本指定数据源职责、优先级和允许使用的物理表。</p>
      </div>
      <div class="toolbar-actions">
        <el-select
          v-model="selectedVersionId"
          class="version-select"
          placeholder="选择版本"
          @change="loadBindings"
        >
          <el-option
            v-for="version in versions"
            :key="version.id"
            :label="`${version.versionNumber} · ${versionStatusLabel(version.status)}`"
            :value="version.id"
          />
        </el-select>
        <el-button v-if="canEdit" type="primary" @click="openCreateDialog">绑定数据源</el-button>
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
          ? '正式或已验证的业务模型保持只读；请创建新草稿后修改数据源。'
          : '当前运行权限仅允许查看，不能修改数据源绑定。'
      "
    />

    <el-table :data="bindings" empty-text="当前版本尚未绑定数据源">
      <el-table-column label="数据源" min-width="180">
        <template #default="scope">
          <strong>{{ datasourceName(scope.row) }}</strong>
          <div v-if="scope.row.datasourceType" class="subtle">
            {{ datasourceTypeLabel(scope.row.datasourceType) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="业务域" min-width="160">
        <template #default="scope">
          {{ businessDomainNameLabel(scope.row.domainName, scope.row.domainCode) }}
          <div class="subtle">
            {{ businessDomainCodeLabel(scope.row.domainCode, scope.row.domainName) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="职责" min-width="260" show-overflow-tooltip>
        <template #default="scope">
          {{
            responsibilityLabel(scope.row.responsibility, scope.row.domainName, scope.row.domainCode)
          }}
        </template>
      </el-table-column>
      <el-table-column prop="priority" label="优先级" width="90" />
      <el-table-column label="暴露表" min-width="260">
        <template #default="scope">
          <div class="table-tags">
            <el-tag
              v-for="table in scope.row.exposedTables"
              :key="table"
              size="small"
              effect="plain"
            >
              {{ table }}
            </el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column v-if="canEdit" label="操作" width="140" fixed="right">
        <template #default="scope">
          <el-button link type="primary" @click="openEditDialog(scope.row)">编辑</el-button>
          <el-button link type="danger" @click="removeBinding(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑数据源绑定' : '绑定数据源'"
      width="640px"
    >
      <el-form label-position="top">
        <el-form-item label="数据源" required>
          <el-select
            v-model="form.datasourceId"
            filterable
            :disabled="editing"
            placeholder="选择已配置的数据源"
            @change="loadDatasourceTables"
          >
      <el-option
              v-for="datasource in availableDatasources"
              :key="datasource.id"
              :label="`${datasourceDisplayName(datasource)} · ${datasourceTypeLabel(datasource.type)}`"
              :value="datasource.id"
            />
          </el-select>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="业务域编码" required>
            <el-input v-model="form.domainCode" placeholder="输入业务域编码" />
          </el-form-item>
          <el-form-item label="业务域名称" required>
            <el-input v-model="form.domainName" placeholder="输入业务域名称" />
          </el-form-item>
        </div>
        <el-form-item label="数据源职责" required>
          <el-input
            v-model="form.responsibility"
            type="textarea"
            :rows="3"
            placeholder="说明该数据源在项目中的业务职责和可用范围"
          />
        </el-form-item>
        <el-form-item label="候选来源优先级">
          <el-input-number v-model="form.priority" :min="0" :max="10000" />
          <span class="priority-hint">数值越小，后续 Planner 选择时优先级越高。</span>
        </el-form-item>
        <el-form-item label="暴露表范围" required>
          <div v-loading="tablesLoading" class="table-scope">
            <el-checkbox-group v-if="datasourceTables.length" v-model="form.exposedTables">
              <el-checkbox v-for="table in datasourceTables" :key="table" :value="table">
                {{ table }}
              </el-checkbox>
            </el-checkbox-group>
            <el-empty v-else :image-size="70" description="选择数据源后读取物理表" />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveBinding">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref, watch } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import datasourceService, { type Datasource } from '@/services/datasource';
  import {
    semEvoSQLService,
    type ProjectDatasourceBinding,
    type SemanticProjectVersion,
  } from '@/services/semevosql';
  import {
    businessDomainCodeLabel,
    businessDomainNameLabel,
    datasourceDisplayName,
    datasourceTypeLabel,
    responsibilityLabel,
    versionStatusLabel,
  } from '@/services/displayLabels';

  const props = defineProps<{
    projectId: number;
    versions: SemanticProjectVersion[];
    canEdit?: boolean;
  }>();

  const selectedVersionId = ref<number>();
  const bindings = ref<ProjectDatasourceBinding[]>([]);
  const datasources = ref<Datasource[]>([]);
  const datasourceTables = ref<string[]>([]);
  const loading = ref(false);
  const tablesLoading = ref(false);
  const dialogVisible = ref(false);
  const saving = ref(false);
  const editing = ref(false);
  const form = reactive({
    datasourceId: undefined as number | undefined,
    domainCode: '',
    domainName: '',
    responsibility: '',
    priority: 100,
    exposedTables: [] as string[],
  });

  const selectedVersion = computed(() =>
    props.versions.find(version => version.id === selectedVersionId.value),
  );
  const canEdit = computed(
    () => selectedVersion.value?.status === 'DRAFT' && props.canEdit !== false,
  );
  const availableDatasources = computed(() => datasources.value.filter(item => item.id != null));

  const errorMessage = (error: unknown, fallback: string) => {
    if (error instanceof Error && error.message) return error.message;
    return fallback;
  };

  const datasourceName = (binding: ProjectDatasourceBinding) => {
    const datasource = datasources.value.find(item => item.id === binding.datasourceId);
    return datasource
      ? datasourceDisplayName(datasource)
      : datasourceDisplayName({ id: binding.datasourceId, name: binding.datasourceName });
  };

  const selectDefaultVersion = () => {
    if (selectedVersionId.value && props.versions.some(item => item.id === selectedVersionId.value))
      return;
    selectedVersionId.value =
      props.versions.find(item => item.status === 'DRAFT')?.id || props.versions[0]?.id;
  };

  const loadBindings = async () => {
    if (!selectedVersionId.value) {
      bindings.value = [];
      return;
    }
    loading.value = true;
    try {
      bindings.value = await semEvoSQLService.projectDatasourceBindings(
        props.projectId,
        selectedVersionId.value,
      );
    } catch (error) {
      ElMessage.error(errorMessage(error, '数据源绑定加载失败'));
    } finally {
      loading.value = false;
    }
  };

  const resetForm = () => {
    form.datasourceId = undefined;
    form.domainCode = '';
    form.domainName = '';
    form.responsibility = '';
    form.priority = 100;
    form.exposedTables = [];
    datasourceTables.value = [];
  };

  const openCreateDialog = () => {
    resetForm();
    editing.value = false;
    dialogVisible.value = true;
  };

  const loadDatasourceTables = async () => {
    datasourceTables.value = [];
    form.exposedTables = [];
    if (!form.datasourceId) return;
    tablesLoading.value = true;
    try {
      datasourceTables.value = await datasourceService.getDatasourceTables(form.datasourceId);
    } catch (error) {
      ElMessage.error(errorMessage(error, '物理表加载失败'));
    } finally {
      tablesLoading.value = false;
    }
  };

  const openEditDialog = async (binding: ProjectDatasourceBinding) => {
    editing.value = true;
    form.datasourceId = binding.datasourceId;
    form.domainCode = binding.domainCode;
    form.domainName = binding.domainName;
    form.responsibility = binding.responsibility;
    form.priority = binding.priority;
    dialogVisible.value = true;
    tablesLoading.value = true;
    try {
      datasourceTables.value = await datasourceService.getDatasourceTables(binding.datasourceId);
      form.exposedTables = [...binding.exposedTables];
    } catch (error) {
      ElMessage.error(errorMessage(error, '物理表加载失败'));
    } finally {
      tablesLoading.value = false;
    }
  };

  const saveBinding = async () => {
    if (!selectedVersionId.value || !form.datasourceId) {
      ElMessage.warning('请选择版本和数据源');
      return;
    }
    if (!form.domainCode.trim() || !form.domainName.trim() || !form.responsibility.trim()) {
      ElMessage.warning('请填写业务域和数据源职责');
      return;
    }
    if (!form.exposedTables.length) {
      ElMessage.warning('至少选择一个暴露表');
      return;
    }
    saving.value = true;
    try {
      await semEvoSQLService.saveProjectDatasourceBinding(
        props.projectId,
        selectedVersionId.value,
        form.datasourceId,
        {
          domainCode: form.domainCode.trim(),
          domainName: form.domainName.trim(),
          responsibility: form.responsibility.trim(),
          priority: form.priority,
          exposedTables: form.exposedTables,
        },
      );
      ElMessage.success('数据源绑定已保存');
      dialogVisible.value = false;
      await loadBindings();
    } catch (error) {
      ElMessage.error(errorMessage(error, '数据源绑定保存失败'));
    } finally {
      saving.value = false;
    }
  };

  const removeBinding = async (binding: ProjectDatasourceBinding) => {
    if (!selectedVersionId.value) return;
    try {
      await ElMessageBox.confirm(
        `确认从当前版本移除“${datasourceName(binding)}”及其表暴露范围？`,
        '移除数据源绑定',
        { type: 'warning' },
      );
      await semEvoSQLService.deleteProjectDatasourceBinding(
        props.projectId,
        selectedVersionId.value,
        binding.datasourceId,
      );
      ElMessage.success('数据源绑定已移除');
      await loadBindings();
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(errorMessage(error, '数据源绑定删除失败'));
      }
    }
  };

  watch(
    () => props.versions,
    async () => {
      selectDefaultVersion();
      await loadBindings();
    },
    { deep: true },
  );

  onMounted(async () => {
    loading.value = true;
    try {
      if (props.canEdit !== false) {
        datasources.value = await datasourceService.getAllDatasource();
      }
      selectDefaultVersion();
      await loadBindings();
    } catch (error) {
      ElMessage.error(errorMessage(error, '数据源加载失败'));
    } finally {
      loading.value = false;
    }
  });
</script>

<style scoped>
  .datasource-bindings {
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
  .readonly-alert {
    margin-bottom: 16px;
  }
  .subtle {
    margin-top: 4px;
    color: #94a3b8;
    font-size: 12px;
  }
  .table-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  .form-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 14px;
  }
  .priority-hint {
    margin-left: 12px;
    color: #94a3b8;
    font-size: 12px;
  }
  .table-scope {
    width: 100%;
    min-height: 100px;
    padding: 12px;
    border: 1px solid #e2e8f0;
    border-radius: 8px;
  }
  .table-scope :deep(.el-checkbox-group) {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 4px 12px;
  }
  @media (max-width: 800px) {
    .toolbar {
      flex-direction: column;
    }
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
    .table-scope :deep(.el-checkbox-group) {
      grid-template-columns: 1fr;
    }
  }
</style>
