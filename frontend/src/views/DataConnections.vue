<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <BaseLayout>
    <section class="page-shell">
      <div class="page-heading">
        <div>
          <h1>数据连接</h1>
          <p>管理项目可使用的 MySQL / PostgreSQL 数据源，测试连接后再选择具体业务表。密码不会回显。</p>
        </div>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建连接</el-button>
      </div>

      <el-card shadow="never" class="connection-card">
        <div class="table-scroll" role="region" aria-label="数据连接列表" tabindex="0">
          <el-table v-loading="loading" :data="connections" empty-text="还没有数据源连接">
          <el-table-column label="名称" min-width="180">
            <template #default="scope">
              <div class="connection-name">
                <strong>{{ connectionDisplayName(scope.row) }}</strong>
                <span>
                  {{
                    connectionDescription(scope.row) ||
                    '只读数据连接'
                  }}
                </span>
                <small class="connection-location">
                  {{ connectionHostLabel(scope.row.host) }}{{ scope.row.port ? `:${scope.row.port}` : '' }}
                  · {{ scope.row.databaseName || '未指定数据库' }}
                </small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="110">
            <template #default="scope">{{ datasourceTypeLabel(scope.row.type) }}</template>
          </el-table-column>
          <el-table-column prop="databaseName" label="数据库" min-width="145" show-overflow-tooltip />
          <el-table-column label="最近测试" min-width="160">
            <template #default="scope">
              <el-tag :type="connectionStatusType(scope.row)" effect="plain">
                {{ connectionStatusLabel(scope.row) }}
              </el-tag>
              <div class="subtle status-time">{{ formatTime(scope.row.lastTestTime) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="项目使用情况" min-width="165">
            <template #default="scope">
              <div v-if="usageFor(scope.row.id).length" class="usage-list">
                <el-tag
                  v-for="usage in usageFor(scope.row.id).slice(0, 3)"
                  :key="usage.projectId"
                  size="small"
                  :type="usage.usedByActiveVersion ? 'success' : 'info'"
                  effect="plain"
                >
                  {{ usage.projectName }}{{ usage.usedByActiveVersion ? ' · 当前正式版本' : '' }}
                </el-tag>
                <span v-if="usageFor(scope.row.id).length > 3" class="subtle">
                  另有 {{ usageFor(scope.row.id).length - 3 }} 个项目
                </span>
              </div>
              <span v-else class="subtle">尚未被数据项目使用</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="175">
            <template #default="scope">
              <el-button
                link
                type="primary"
                :loading="testingId === scope.row.id"
                @click="test(scope.row)"
              >
                测试连接
              </el-button>
              <el-button link @click="openEdit(scope.row)">编辑</el-button>
              <el-button link type="danger" @click="remove(scope.row)">删除</el-button>
            </template>
          </el-table-column>
          </el-table>
        </div>

        <el-empty v-if="!loading && connections.length === 0" description="从第一个数据库连接开始">
          <el-button type="primary" @click="openCreate">新建数据源连接</el-button>
        </el-empty>
      </el-card>
    </section>

    <DataConnectionDialog v-model="dialogVisible" :datasource="editing" @saved="handleSaved" />
  </BaseLayout>
</template>

<script setup lang="ts">
  import { onMounted, ref } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { Plus } from '@element-plus/icons-vue';
  import BaseLayout from '@/layouts/BaseLayout.vue';
  import DataConnectionDialog from '@/components/datasource/DataConnectionDialog.vue';
  import datasourceService, { type Datasource, type DatasourceUsage } from '@/services/datasource';
  import { getApiErrorMessage } from '@/services/common';
  import {
    datasourceDescriptionLabel,
    datasourceDisplayName,
    datasourceHostLabel,
    datasourceTypeLabel,
  } from '@/services/displayLabels';

  const connections = ref<Datasource[]>([]);
  const usages = ref<DatasourceUsage[]>([]);
  const loading = ref(false);
  const testingId = ref<number>();
  const dialogVisible = ref(false);
  const editing = ref<Datasource | null>(null);

  const load = async () => {
    loading.value = true;
    try {
      const [nextConnections, nextUsages] = await Promise.all([
        datasourceService.getAllDatasource(),
        datasourceService.getDatasourceUsage(),
      ]);
      connections.value = nextConnections;
      usages.value = nextUsages;
    } catch (error) {
      ElMessage.error(getApiErrorMessage(error, '数据连接加载失败'));
    } finally {
      loading.value = false;
    }
  };

  const openCreate = () => {
    editing.value = null;
    dialogVisible.value = true;
  };

  const openEdit = (datasource: Datasource) => {
    editing.value = datasource;
    dialogVisible.value = true;
  };

  const handleSaved = async () => {
    await load();
  };

  const test = async (datasource: Datasource) => {
    if (!datasource.id) return;
    testingId.value = datasource.id;
    try {
      await datasourceService.testConnection(datasource.id);
      ElMessage.success('连接测试成功');
      await load();
    } catch (error) {
      ElMessage.error(getApiErrorMessage(error, '连接测试失败'));
      await load();
    } finally {
      testingId.value = undefined;
    }
  };

  const remove = async (datasource: Datasource) => {
    if (!datasource.id) return;
    try {
      await ElMessageBox.confirm(
        `确定删除“${datasource.name || `连接 ${datasource.id}`}”吗？仍被项目使用的连接会由服务端拒绝删除。`,
        '删除数据连接',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
      );
      await datasourceService.deleteDatasource(datasource.id);
      ElMessage.success('数据连接已删除');
      await load();
    } catch (error) {
      if (error === 'cancel' || error === 'close') return;
      ElMessage.error(getApiErrorMessage(error, '删除数据连接失败'));
    }
  };

  const connectionDisplayName = datasourceDisplayName;
  const connectionDescription = datasourceDescriptionLabel;
  const connectionHostLabel = datasourceHostLabel;

  const connectionStatusLabel = (datasource: Datasource) => {
    if (datasource.status && datasource.status !== 'active') return '配置已停用';
    if (datasource.testStatus === 'success') return '上次测试通过';
    if (datasource.testStatus === 'failed') return '上次测试失败';
    return '尚未测试';
  };

  const connectionStatusType = (datasource: Datasource) => {
    if (datasource.status && datasource.status !== 'active') return 'info';
    if (datasource.testStatus === 'success') return 'success';
    if (datasource.testStatus === 'failed') return 'danger';
    return 'info';
  };
  const usageFor = (datasourceId?: number) =>
    datasourceId ? usages.value.filter(item => item.datasourceId === datasourceId) : [];
  const formatTime = (value?: string) =>
    value ? new Date(value.replace(' ', 'T')).toLocaleString('zh-CN') : '尚无测试记录';
  onMounted(load);
</script>

<style scoped>
  .page-shell {
    max-width: 1320px;
    margin: 0 auto;
    padding: 28px;
  }
  .page-heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 20px;
  }
  .page-heading h1 {
    margin: 0 0 8px;
    color: #0f172a;
    font-size: 30px;
  }
  .page-heading p {
    margin: 0;
    color: #64748b;
  }
  .connection-card {
    margin-top: 22px;
    border-radius: 16px;
  }
  .table-scroll {
    overflow-x: auto;
    border-radius: 12px;
    outline: none;
  }
  .table-scroll:focus-visible {
    box-shadow: 0 0 0 3px rgb(23 125 115 / 16%);
  }
  .connection-card :deep(.el-table) {
    min-width: 920px;
  }
  .connection-name {
    display: grid;
    gap: 4px;
  }
  .connection-name span,
  .subtle {
    color: #64748b;
    font-size: 12px;
  }
  .connection-location {
    overflow: hidden;
    color: #7a8c92;
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .status-time {
    margin-top: 5px;
  }
  .usage-list {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  .usage-list :deep(.el-tag) {
    max-width: 150px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  @media (max-width: 760px) {
    .page-shell {
      padding: 16px 10px;
    }
    .page-heading {
      flex-direction: column;
    }
  }
</style>
