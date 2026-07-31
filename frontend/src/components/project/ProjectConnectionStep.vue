<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <div>
    <div class="section-title">
      <div>
        <h2>连接业务数据</h2>
        <p>选择已有连接，或直接在这里新建。系统会读取表结构并默认纳入可访问业务表。</p>
      </div>
      <div class="section-actions">
        <el-button @click="connectionDialogVisible = true">新建连接</el-button>
        <el-button :icon="Plus" @click="addBinding">添加连接</el-button>
      </div>
    </div>

    <el-alert
      v-if="availableDatasources.length === 0"
      class="empty-alert"
      type="warning"
      show-icon
      :closable="false"
      title="还没有数据连接。新建并测试成功后会直接回到当前步骤，不需要离开创建流程。"
    />

    <el-card v-for="(item, index) in bindings" :key="item.key" shadow="never" class="binding-card">
      <div class="binding-heading">
        <strong>{{ index === 0 ? '主要数据连接' : `数据连接 ${index + 1}` }}</strong>
        <el-button v-if="bindings.length > 1" link type="danger" @click="removeBinding(index)">
          移除
        </el-button>
      </div>
      <div class="connection-row">
        <el-form-item label="数据库连接" required>
          <el-select
            v-model="item.datasourceId"
            filterable
            placeholder="选择数据连接"
            @change="selectDatasource(item)"
          >
            <el-option
              v-for="datasource in availableDatasources"
              :key="datasource.id"
              :label="`${datasourceDisplayName(datasource)} · ${datasourceTypeLabel(datasource.type)}`"
              :value="datasource.id"
            >
              <span>{{ datasourceDisplayName(datasource) }}</span>
              <span class="option-meta">
                {{ datasource.testStatus === 'success' ? '已测试' : '待测试' }}
              </span>
            </el-option>
          </el-select>
        </el-form-item>
        <div class="connection-actions">
          <el-button
            :disabled="!item.datasourceId"
            :loading="testingId === item.datasourceId"
            @click="testSelected(item)"
          >
            测试连接
          </el-button>
          <el-tag v-if="selectedDatasource(item)?.testStatus === 'success'" type="success">
            连接正常
          </el-tag>
        </div>
      </div>

      <el-form-item label="供查询使用的表" required>
        <div v-loading="item.loadingTables" class="table-scope">
          <div v-if="item.tableOptions.length" class="table-toolbar">
            <span>
              已选择 {{ item.exposedTables.length }} / {{ item.tableOptions.length }} 张表
            </span>
            <div>
              <el-button link @click="item.exposedTables = [...item.tableOptions]">全选</el-button>
              <el-button link @click="item.exposedTables = []">清空</el-button>
            </div>
          </div>
          <el-checkbox-group v-if="item.tableOptions.length" v-model="item.exposedTables">
            <el-checkbox v-for="table in item.tableOptions" :key="table" :value="table">
              {{ table }}
            </el-checkbox>
          </el-checkbox-group>
          <el-empty v-else :image-size="60" description="选择连接后自动读取表结构" />
        </div>
      </el-form-item>

      <el-collapse class="advanced-collapse">
        <el-collapse-item title="高级设置">
          <div class="form-grid">
            <el-form-item label="优先级">
              <el-input-number v-model="item.priority" :min="0" :max="10000" />
            </el-form-item>
            <el-form-item label="业务域编码">
              <el-input v-model="item.domainCode" />
            </el-form-item>
            <el-form-item label="业务域名称">
              <el-input v-model="item.domainName" />
            </el-form-item>
            <el-form-item label="数据职责">
              <el-input v-model="item.responsibility" />
            </el-form-item>
          </div>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <DataConnectionDialog v-model="connectionDialogVisible" @saved="handleConnectionSaved" />
  </div>
</template>

<script setup lang="ts">
  import { ref } from 'vue';
  import { ElMessage } from 'element-plus';
  import { Plus } from '@element-plus/icons-vue';
  import DataConnectionDialog from '@/components/datasource/DataConnectionDialog.vue';
  import datasourceService, { type Datasource } from '@/services/datasource';
  import { getApiErrorMessage } from '@/services/common';
  import { datasourceDisplayName, datasourceTypeLabel } from '@/services/displayLabels';

  interface DatasourceBindingDraft {
    key: number;
    datasourceId?: number;
    domainCode: string;
    domainName: string;
    responsibility: string;
    priority: number;
    exposedTables: string[];
    tableOptions: string[];
    loadingTables: boolean;
  }

  const props = defineProps<{
    bindings: DatasourceBindingDraft[];
    availableDatasources: Datasource[];
    projectName: string;
    projectCode: string;
    businessDomain: string;
  }>();
  const emit = defineEmits<{
    'update:bindings': [value: DatasourceBindingDraft[]];
    'update:availableDatasources': [value: Datasource[]];
  }>();

  const connectionDialogVisible = ref(false);
  const testingId = ref<number>();

  const newBinding = (): DatasourceBindingDraft => ({
    key: Date.now() + Math.floor(Math.random() * 1000),
    datasourceId: undefined,
    domainCode: props.businessDomain || props.projectCode || 'business',
    domainName: props.projectName || props.businessDomain || '业务数据',
    responsibility: props.projectName
      ? `为${props.projectName}查询提供业务数据`
      : '为项目查询提供业务数据',
    priority: 100,
    exposedTables: [],
    tableOptions: [],
    loadingTables: false,
  });

  const addBinding = () => emit('update:bindings', [...props.bindings, newBinding()]);
  const removeBinding = (index: number) =>
    emit(
      'update:bindings',
      props.bindings.filter((_, current) => current !== index),
    );

  const selectedDatasource = (item: DatasourceBindingDraft) =>
    props.availableDatasources.find(datasource => datasource.id === item.datasourceId);

  const selectDatasource = async (item: DatasourceBindingDraft) => {
    const datasource = selectedDatasource(item);
    if (datasource) {
      item.responsibility = item.responsibility || `${datasourceDisplayName(datasource)}提供项目查询数据`;
    }
    await loadTables(item);
  };

  const loadTables = async (item: DatasourceBindingDraft) => {
    item.tableOptions = [];
    item.exposedTables = [];
    if (!item.datasourceId) return;
    item.loadingTables = true;
    try {
      item.tableOptions = await datasourceService.getDatasourceTables(item.datasourceId);
      item.exposedTables = [...item.tableOptions];
    } catch (error) {
      ElMessage.error(getApiErrorMessage(error, '表结构读取失败'));
    } finally {
      item.loadingTables = false;
    }
  };

  const testSelected = async (item: DatasourceBindingDraft) => {
    if (!item.datasourceId) return;
    testingId.value = item.datasourceId;
    try {
      await datasourceService.testConnection(item.datasourceId);
      const updated = props.availableDatasources.map(datasource =>
        datasource.id === item.datasourceId ? { ...datasource, testStatus: 'success' } : datasource,
      );
      emit('update:availableDatasources', updated);
      ElMessage.success('连接测试成功');
    } catch (error) {
      ElMessage.error(getApiErrorMessage(error, '连接测试失败'));
    } finally {
      testingId.value = undefined;
    }
  };

  const handleConnectionSaved = async (saved: Datasource) => {
    const refreshed = (await datasourceService.getAllDatasource()).filter(
      item => typeof item.id === 'number',
    );
    emit('update:availableDatasources', refreshed);
    if (!saved.id) return;

    const target =
      props.bindings.length > 0 ? props.bindings[props.bindings.length - 1] : newBinding();
    target.datasourceId = saved.id;
    target.responsibility = target.responsibility || `${saved.name || '该连接'}提供项目查询数据`;
    if (props.bindings.length === 0) {
      emit('update:bindings', [target]);
    }
    await loadTables(target);
  };
</script>

<style scoped>
  .section-title,
  .binding-heading,
  .connection-row,
  .table-toolbar {
    display: flex;
    justify-content: space-between;
    gap: 16px;
  }
  .section-title {
    align-items: flex-start;
    margin-bottom: 18px;
  }
  .section-title h2 {
    margin: 0 0 6px;
    color: #0f172a;
  }
  .section-title p {
    margin: 0;
    color: #64748b;
  }
  .section-actions,
  .connection-actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .empty-alert,
  .binding-card {
    margin-top: 14px;
  }
  .binding-heading {
    align-items: center;
    margin-bottom: 12px;
  }
  .connection-row {
    align-items: flex-end;
  }
  .connection-row :deep(.el-form-item) {
    flex: 1;
  }
  .option-meta {
    float: right;
    margin-left: 18px;
    color: #94a3b8;
    font-size: 12px;
  }
  .table-scope {
    width: 100%;
    min-height: 90px;
    padding: 12px;
    border: 1px solid #e2e8f0;
    border-radius: 8px;
  }
  .table-toolbar {
    align-items: center;
    margin-bottom: 8px;
    color: #64748b;
    font-size: 12px;
  }
  .table-scope :deep(.el-checkbox-group) {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 4px 12px;
  }
  .advanced-collapse {
    margin-top: 14px;
  }
  .form-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0 18px;
  }
  @media (max-width: 760px) {
    .section-title,
    .connection-row {
      flex-direction: column;
    }
    .table-scope :deep(.el-checkbox-group),
    .form-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
