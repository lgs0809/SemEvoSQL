<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <BaseLayout>
    <section class="project-page">
      <div class="page-heading">
        <div>
          <h1>项目工作台</h1>
          <p>
            每个项目包含一套数据连接、业务模型和可追溯的查询历史。选择一个项目，继续建设模型或打开查询工作台。
          </p>
        </div>
        <el-button
          v-if="canCreateProject"
          type="primary"
          size="large"
          :icon="Plus"
          @click="router.push('/projects/create')"
        >
          创建项目
        </el-button>
      </div>

      <div class="summary-grid">
        <el-card shadow="never" class="summary-card summary-card-neutral">
          <div class="summary-icon"><i class="bi bi-grid-1x2"></i></div>
          <strong>{{ projects.length }}</strong>
          <span>项目总数</span>
          <small>当前工作区全部项目</small>
        </el-card>
        <el-card shadow="never" class="summary-card summary-card-success">
          <div class="summary-icon"><i class="bi bi-check2-circle"></i></div>
          <strong>{{ readyCount }}</strong>
          <span>模型已发布</span>
          <small>已具备查询入口</small>
        </el-card>
        <el-card shadow="never" class="summary-card summary-card-warning">
          <div class="summary-icon"><i class="bi bi-cone-striped"></i></div>
          <strong>{{ buildingCount }}</strong>
          <span>正在建设</span>
          <small>还有准备步骤待完成</small>
        </el-card>
        <el-card
          shadow="never"
          class="summary-card summary-card-danger"
          :class="{ 'has-warning': unknownCount > 0 }"
        >
          <div class="summary-icon"><i class="bi bi-exclamation-circle"></i></div>
          <strong>{{ unknownCount }}</strong>
          <span>状态需检查</span>
          <small>健康状态暂时无法读取</small>
        </el-card>
      </div>

      <el-card shadow="never" class="project-table-card">
        <el-alert
          v-if="listError"
          class="list-error-alert"
          type="error"
          show-icon
          :closable="false"
          title="项目列表加载失败"
        >
          <template #default>
            <div class="inline-recovery">
              <span>{{ listError }}</span>
              <el-button size="small" type="primary" plain @click="loadProjects">
                重新加载
              </el-button>
            </div>
          </template>
        </el-alert>

        <div class="toolbar">
          <div class="toolbar-filters">
            <el-input
              v-model="keyword"
              clearable
              :prefix-icon="Search"
              placeholder="搜索项目名称或业务域"
            />
            <el-segmented
              v-model="statusFilter"
              :options="statusFilterOptions"
              aria-label="项目状态筛选"
            />
          </div>
          <el-button :icon="Refresh" :loading="loading" @click="loadProjects">刷新</el-button>
        </div>

        <el-empty
          v-if="!loading && !listError && projects.length === 0"
          description="还没有数据项目。创建项目并连接数据库，系统会从真实结构开始建立业务模型。"
        >
          <el-button
            v-if="canCreateProject"
            type="primary"
            :icon="Plus"
            @click="router.push('/projects/create')"
          >
            创建第一个项目
          </el-button>
        </el-empty>

        <div
          v-else-if="projects.length > 0"
          class="project-table-scroll"
          role="region"
          aria-label="项目列表"
          tabindex="0"
        >
          <el-table v-loading="loading" :data="displayedProjects" empty-text="没有匹配的项目">
          <el-table-column label="项目" min-width="220">
            <template #default="scope">
              <button class="project-link" @click="openProject(scope.row.id)">
                {{ scope.row.name }}
              </button>
              <div class="subtle">{{ scope.row.description || scope.row.businessDomain }}</div>
              <div class="subtle project-updated">最近更新：{{ formatTime(scope.row.updateTime) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="查询入口" width="165">
            <template #default="scope">
              <template v-if="health(scope.row.id)">
                <el-tag
                  :type="health(scope.row.id)?.queryReady ? 'success' : 'warning'"
                  effect="plain"
                >
                  {{ health(scope.row.id)?.queryReady ? '查询入口已就绪' : '业务模型待发布' }}
                </el-tag>
                <div class="subtle version-copy">
                  {{
                    health(scope.row.id)?.activeVersion?.versionNumber
                    ? `当前业务模型 v${health(scope.row.id)?.activeVersion?.versionNumber}`
                    : '尚未发布业务模型'
                  }}
                </div>
              </template>
              <template v-else>
                <el-tag type="info" effect="plain">状态暂不可用</el-tag>
                <div v-if="healthFailed(scope.row.id)" class="subtle version-copy">
                  健康状态读取失败，可单独重试
                </div>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="当前最重要的下一步" min-width="220">
            <template #default="scope">
              <template v-if="health(scope.row.id)?.nextAction">
                <strong class="next-action">
                  {{ health(scope.row.id)?.nextAction?.label }}
                </strong>
                <div class="subtle">{{ health(scope.row.id)?.nextAction?.description }}</div>
              </template>
              <span v-else-if="health(scope.row.id)" class="subtle">暂无待处理事项</span>
              <span v-else class="subtle">项目状态暂不可用</span>
            </template>
          </el-table-column>
          <el-table-column label="历史查询" width="150">
            <template #default="scope">
              <template v-if="(health(scope.row.id)?.totalQueries || 0) > 0">
                <strong>{{ percent(health(scope.row.id)?.querySuccessRate) }}</strong>
                <div class="subtle">
                  {{ health(scope.row.id)?.totalQueries }} 次执行 ·
                  {{ health(scope.row.id)?.correctionCount }} 次纠错
                </div>
              </template>
              <span v-else-if="health(scope.row.id)" class="subtle">暂无真实查询样本</span>
              <span v-else class="subtle">项目状态暂不可用</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="175">
            <template #default="scope">
              <el-button link type="primary" @click="openProject(scope.row.id)">打开项目</el-button>
              <el-button
                v-if="health(scope.row.id)"
                link
                type="primary"
                @click="handleProjectAction(scope.row.id)"
              >
                {{ projectActionLabel(scope.row.id) }}
              </el-button>
              <el-button
                v-else-if="healthFailed(scope.row.id)"
                link
                @click="retryProjectHealth(scope.row.id)"
              >
                重试状态
              </el-button>
            </template>
          </el-table-column>
          </el-table>
        </div>
      </el-card>
    </section>
  </BaseLayout>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue';
  import { useRouter } from 'vue-router';
  import { ElMessage } from 'element-plus';
  import { Plus, Refresh, Search } from '@element-plus/icons-vue';
  import BaseLayout from '@/layouts/BaseLayout.vue';
  import { projectListAction } from '@/services/projectCapabilities.mjs';
  import {
    semEvoSQLService,
    type ProjectHealth,
    type ProjectHealthSummary,
    type SemanticProject,
  } from '@/services/semevosql';

  const router = useRouter();
  const projects = ref<SemanticProject[]>([]);
  type StatusFilter = 'ALL' | 'ACTION' | 'READY' | 'UNKNOWN';

  const healthByProject = ref<Record<number, ProjectHealthSummary>>({});
  const healthFailureIds = ref<Set<number>>(new Set());
  const listError = ref('');
  const keyword = ref('');
  const statusFilter = ref<StatusFilter>('ALL');
  const statusFilterOptions: Array<{ label: string; value: StatusFilter }> = [
    { label: '全部', value: 'ALL' },
    { label: '待处理', value: 'ACTION' },
    { label: '已发布模型', value: 'READY' },
    { label: '状态异常', value: 'UNKNOWN' },
  ];
  const loading = ref(false);

  const canCreateProject = computed(() => true);
  const readyCount = computed(
    () => projects.value.filter(item => healthByProject.value[item.id]?.queryReady).length,
  );
  const buildingCount = computed(
    () =>
      projects.value.filter(item => {
        const projectHealth = healthByProject.value[item.id];
        return Boolean(projectHealth?.available && !projectHealth.queryReady);
      }).length,
  );
  const unknownCount = computed(
    () => projects.value.filter(item => !healthByProject.value[item.id]?.available).length,
  );
  const filteredProjects = computed(() => {
    const query = keyword.value.trim().toLowerCase();
    if (!query) return projects.value;
    return projects.value.filter(
      item =>
        item.name.toLowerCase().includes(query) ||
        item.projectCode.toLowerCase().includes(query) ||
        item.businessDomain.toLowerCase().includes(query),
    );
  });
  const displayedProjects = computed(() =>
    [...filteredProjects.value]
      .filter(item => {
        const projectHealth = healthByProject.value[item.id];
        if (statusFilter.value === 'READY') return Boolean(projectHealth?.queryReady);
        if (statusFilter.value === 'ACTION')
          return Boolean(projectHealth?.available && !projectHealth.queryReady);
        if (statusFilter.value === 'UNKNOWN') return !projectHealth?.available;
        return true;
      })
      .sort((left, right) => {
        const leftHealth = healthByProject.value[left.id];
        const rightHealth = healthByProject.value[right.id];
        const priority = (value?: ProjectHealthSummary) => {
          if (!value?.available) return 3;
          if (!value.queryReady && value.nextAction) return 0;
          if (!value.queryReady) return 1;
          return 2;
        };
        return priority(leftHealth) - priority(rightHealth);
      }),
  );

  const loadProjects = async () => {
    loading.value = true;
    listError.value = '';
    try {
      const [nextProjects, summaries] = await Promise.all([
        semEvoSQLService.listProjects(),
        semEvoSQLService.projectHealthSummaries(),
      ]);
      projects.value = nextProjects;
      healthByProject.value = Object.fromEntries(summaries.map(item => [item.projectId, item]));
      healthFailureIds.value = new Set(
        summaries.filter(item => !item.available).map(item => item.projectId),
      );
    } catch (error) {
      listError.value = error instanceof Error ? error.message : '项目列表加载失败';
    } finally {
      loading.value = false;
    }
  };

  const summaryFromHealth = (value: ProjectHealth): ProjectHealthSummary => ({
    projectId: value.projectId,
    available: true,
    queryReady: value.queryReady,
    activeVersion: value.activeVersion,
    nextAction: value.nextActions[0],
    totalQueries: value.quality.totalQueries,
    querySuccessRate: value.quality.querySuccessRate,
    correctionCount: value.quality.correctionCount,
  });
  const health = (projectId: number) => {
    const value = healthByProject.value[projectId];
    return value?.available ? value : undefined;
  };
  const healthFailed = (projectId: number) => healthFailureIds.value.has(projectId);
  const openProject = (projectId: number) => router.push(`/projects/${projectId}`);
  const startChat = (projectId: number) => router.push({ path: '/chat', query: { projectId } });
  const retryProjectHealth = async (projectId: number) => {
    try {
      const nextHealth = await semEvoSQLService.projectHealth(projectId);
      healthByProject.value = {
        ...healthByProject.value,
        [projectId]: summaryFromHealth(nextHealth),
      };
      const nextFailures = new Set(healthFailureIds.value);
      nextFailures.delete(projectId);
      healthFailureIds.value = nextFailures;
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '项目状态读取失败');
    }
  };
  const projectActionLabel = (projectId: number) => {
    const action = projectListAction(healthByProject.value[projectId]);
    if (action === 'CHAT') return '打开查询工作台';
    if (action === 'PREPARE') return '继续建设模型';
    return health(projectId) ? '查看状态' : '查看项目';
  };
  const handleProjectAction = (projectId: number) => {
    const projectHealth = healthByProject.value[projectId];
    const action = projectListAction(projectHealth);
    if (action === 'CHAT') {
      void startChat(projectId);
      return;
    }
    if (action === 'PREPARE') {
      const section = projectHealth?.nextAction?.target || 'overview';
      void router.push({ path: `/projects/${projectId}`, query: { section } });
      return;
    }
    void openProject(projectId);
  };
  const percent = (value?: number) => `${((value || 0) * 100).toFixed(1)}%`;
  const formatTime = (value: string) => (value ? new Date(value).toLocaleString('zh-CN') : '-');

  onMounted(loadProjects);
</script>

<style scoped>
  .project-page {
    position: relative;
  }
  .page-heading {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 24px;
    margin-bottom: 26px;
  }
  .page-heading > div {
    max-width: 760px;
  }
  .page-heading .el-button {
    margin-top: 8px;
  }
  .summary-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 13px;
    margin-bottom: 18px;
  }
  .summary-card {
    position: relative;
    min-height: 135px;
    overflow: hidden;
  }
  .summary-card::after {
    position: absolute;
    right: -25px;
    bottom: -38px;
    width: 105px;
    height: 105px;
    border-radius: 50%;
    background: currentColor;
    content: '';
    opacity: 0.04;
  }
  .summary-card :deep(.el-card__body) {
    display: grid;
    grid-template-columns: 34px 1fr;
    grid-template-rows: auto auto auto;
    column-gap: 10px;
    padding: 17px 18px !important;
  }
  .summary-icon {
    display: grid;
    width: 32px;
    height: 32px;
    grid-row: span 3;
    place-items: center;
    border-radius: 9px;
    background: #edf3f3;
    color: #4f7b7d;
    font-size: 15px;
  }
  .summary-card strong {
    align-self: end;
    color: #172a36;
    font-size: 27px;
    line-height: 1.1;
  }
  .summary-card span {
    color: #526770;
    font-size: 12px;
    font-weight: 650;
  }
  .summary-card small {
    color: #91a1a6;
    font-size: 10px;
  }
  .summary-card-success .summary-icon {
    background: #e8f7f0;
    color: #2b9274;
  }
  .summary-card-warning .summary-icon {
    background: #fff3df;
    color: #b97929;
  }
  .summary-card-danger .summary-icon {
    background: #fff0ef;
    color: #bd554f;
  }
  .summary-card-danger.has-warning strong {
    color: #bd554f;
  }
  .project-table-card {
    border-radius: 14px;
  }
  .project-table-card :deep(.el-card__body) {
    padding: 8px 0 0;
  }
  .project-table-scroll {
    overflow-x: auto;
    outline: none;
  }
  .project-table-scroll:focus-visible {
    box-shadow: 0 0 0 3px rgb(23 125 115 / 16%);
  }
  .project-table-scroll :deep(.el-table) {
    min-width: 920px;
  }
  .list-error-alert {
    margin: 12px 20px 16px;
  }
  .inline-recovery {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .toolbar {
    display: flex;
    justify-content: space-between;
    gap: 16px;
    padding: 10px 20px 18px;
  }
  .toolbar-filters {
    display: flex;
    min-width: 0;
    flex: 1;
    align-items: center;
    gap: 10px;
  }
  .toolbar .el-input {
    max-width: 420px;
  }
  .project-link {
    padding: 0;
    border: 0;
    background: transparent;
    color: #1a3440;
    cursor: pointer;
    font: inherit;
    font-weight: 700;
    text-align: left;
  }
  .project-link:hover {
    color: #177d73;
  }
  .next-action {
    color: #3d5962;
    font-size: 12px;
    font-weight: 700;
  }
  .version-copy {
    margin-top: 5px;
  }
  .project-updated {
    margin-top: 3px;
  }
  .subtle {
    color: #63777e;
    font-size: 11px;
  }
  @media (max-width: 1100px) {
    .summary-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }
  @media (max-width: 760px) {
    .page-heading {
      flex-direction: column;
    }
    .page-heading .el-button {
      margin-top: 0;
    }
    .summary-grid {
      grid-template-columns: 1fr;
    }
    .inline-recovery {
      align-items: stretch;
      flex-direction: column;
    }
    .toolbar {
      align-items: stretch;
      flex-direction: column;
    }
    .toolbar-filters {
      align-items: stretch;
      flex-direction: column;
    }
    .toolbar .el-input {
      max-width: none;
    }
    .project-table-scroll :deep(.el-table) {
      min-width: 920px;
    }
  }
</style>
