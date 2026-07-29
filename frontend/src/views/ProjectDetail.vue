<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <BaseLayout>
    <section class="detail-page" v-loading="loading">
      <div class="detail-heading">
        <div>
          <el-button link :icon="ArrowLeft" @click="router.push('/projects')">返回项目</el-button>
          <h1>{{ projectView?.project.name || '项目详情' }}</h1>
          <p>{{ projectView?.project.description || projectView?.project.businessDomain }}</p>
        </div>
        <div class="heading-actions">
          <el-tag v-if="health" :type="health.queryReady ? 'success' : 'warning'" effect="plain">
            {{ health.queryReady ? '可以问数' : '准备中' }}
          </el-tag>
          <el-button
            type="primary"
            :disabled="
              health
                ? !health.queryReady && !primaryAction
                : !projectView?.project.activePublishedVersionId
            "
            @click="handlePrimaryAction"
          >
            {{ primaryActionLabel }}
          </el-button>
        </div>
      </div>

      <el-alert
        v-if="healthError"
        class="health-error-alert"
        type="warning"
        show-icon
        :closable="false"
        title="项目状态暂时无法完整读取"
      >
        <template #default>
          <div class="health-error-content">
            <span>{{ healthError }}。已有正式版本和页面数据仍可继续查看。</span>
            <el-button size="small" :loading="healthLoading" @click="loadHealth">
              重新读取状态
            </el-button>
          </div>
        </template>
      </el-alert>

      <ProjectLifecycle
        :health="health"
        :show-action="!firstRunMode"
        @action="handleLifecycleAction"
      />

      <el-card v-if="firstRunMode" shadow="never" class="first-run-card">
        <div class="first-run-heading">
          <div>
            <span>首次设置引导</span>
            <h2>{{ firstRunActionLabel }}</h2>
            <p>{{ firstRunDescription }}</p>
          </div>
          <el-button link @click="exitFirstRun">退出引导</el-button>
        </div>
        <div class="first-run-action">
          <span>{{ firstRunActionHint }}</span>
          <el-button
            type="primary"
            :loading="firstRunSubmitting"
            :disabled="!firstRunActionAllowed"
            @click="continueFirstRun"
          >
            {{ firstRunActionLabel }}
          </el-button>
        </div>
      </el-card>

      <el-card shadow="never" class="content-card">
        <el-tabs v-model="activeSection" class="primary-tabs" @tab-change="syncSectionRoute">
          <el-tab-pane label="概览" name="overview">
            <ProjectOverview
              :health="health"
              :loading="healthLoading"
              :error="healthError"
              :can-edit="canEditProject"
              :can-review="canReviewProject"
              @navigate="navigateSection"
              @chat="openChat"
            />
          </el-tab-pane>

          <el-tab-pane v-if="canManageProject" label="外部 Agent" name="external" lazy>
            <ProjectMcpDeployment
              :project-id="projectId"
              :can-manage="canManageProject"
              :query-ready="Boolean(health?.queryReady)"
            />
          </el-tab-pane>

          <el-tab-pane v-if="canEditProject" label="准备" name="prepare" lazy>
            <el-alert
              class="group-intro"
              type="info"
              show-icon
              :closable="false"
              title="从数据连接和业务资料形成业务模型，只在无法安全推断时要求业务确认。"
            />
            <el-tabs v-model="prepareTab" class="secondary-tabs" @tab-change="syncPrepareTabRoute">
              <el-tab-pane label="数据连接" name="datasources" lazy>
                <ProjectDatasourceBindings
                  :project-id="projectId"
                  :versions="versions"
                  :can-edit="canEditProject"
                />
              </el-tab-pane>
              <el-tab-pane label="业务资料" name="documents" lazy>
                <ProjectDocuments
                  :project-id="projectId"
                  :versions="versions"
                  :can-edit="canEditProject"
                />
              </el-tab-pane>
              <el-tab-pane label="业务模型" name="semantic" lazy>
                <ProjectSemanticWorkspace
                  :project-id="projectId"
                  :versions="versions"
                  :can-edit="canEditProject"
                />
              </el-tab-pane>
              <el-tab-pane label="待确认问题" name="grill" lazy>
                <ProjectGrillMe
                  :project-id="projectId"
                  :versions="versions"
                  :can-edit="canEditProject"
                  @completed="handleOnboardingCompleted"
                />
              </el-tab-pane>
            </el-tabs>
          </el-tab-pane>

          <el-tab-pane v-if="canReviewProject" label="改进" name="improve" lazy>
            <el-alert
              class="group-intro"
              type="info"
              show-icon
              :closable="false"
              title="这里汇总系统从真实查询、纠错和运行轨迹中形成的改进信号；正式业务模型仍需经过验证、回归和发布。"
            />
            <el-tabs v-model="improveTab" class="secondary-tabs">
              <el-tab-pane label="改进建议" name="inbox" lazy>
                <ProjectLearningInbox :project-id="projectId" @open="openImprovementDetail" />
              </el-tab-pane>
              <el-tab-pane label="业务模型建议" name="semantic" lazy>
                <ProjectSemanticEvolution
                  :project-id="projectId"
                  :can-govern="canReviewProject"
                  :focus-candidate-id="evolutionCandidateId"
                />
              </el-tab-pane>
              <el-tab-pane label="已验证案例" name="examples" lazy>
                <ProjectQueryExamples
                  :project-id="projectId"
                  :versions="versions"
                  :active-version-id="projectView?.project.activePublishedVersionId"
                  :can-govern="canReviewProject"
                  :can-reindex="canAdminOperations"
                />
              </el-tab-pane>
              <el-tab-pane label="运行优化" name="optimization" lazy>
                <ProjectRuntimeOptimization
                  :project-id="projectId"
                  :can-govern="canReviewProject"
                />
              </el-tab-pane>
              <el-tab-pane label="高级执行证据" name="trajectory" lazy>
                <ProjectTrajectory
                  :project-id="projectId"
                  :versions="versions"
                  :active-version-id="projectView?.project.activePublishedVersionId"
                />
              </el-tab-pane>
            </el-tabs>
          </el-tab-pane>

          <el-tab-pane v-if="canReviewProject" label="验证与发布" name="governance" lazy>
            <el-tabs
              v-model="governanceTab"
              class="secondary-tabs"
              @tab-change="syncGovernanceTabRoute"
            >
              <el-tab-pane label="测试与回归" name="test" lazy>
                <div class="section-copy">
                  <h2>验证业务模型</h2>
                  <p>
                    使用现有评估与自动回归记录验证业务模型变更，并将自动验证结果与人工发布决策明确区分。
                  </p>
                </div>
                <ProjectEvaluations
                  :project-id="projectId"
                  :versions="versions"
                  :active-version-id="projectView?.project.activePublishedVersionId"
                  :can-run="canReviewProject"
                />
              </el-tab-pane>

              <el-tab-pane label="语义治理" name="release" lazy>
                <ProjectSemanticGovernance
                  :project-id="projectId"
                  :can-manage="canManageProject"
                  @changed="load"
                />
              </el-tab-pane>
            </el-tabs>
          </el-tab-pane>
        </el-tabs>
      </el-card>

    </section>
  </BaseLayout>
</template>

<script setup lang="ts">
  import { computed, defineAsyncComponent, onMounted, ref } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { ElMessage } from 'element-plus';
  import { ArrowLeft } from '@element-plus/icons-vue';
  import BaseLayout from '@/layouts/BaseLayout.vue';
  import ProjectLifecycle from '@/components/project/ProjectLifecycle.vue';
  import { projectSectionVisible } from '@/services/projectCapabilities.mjs';
  import ProjectOverview from '@/components/project/ProjectOverview.vue';
  import {
    projectDetailSectionForTarget,
    projectDetailSubsectionForTarget,
    projectPrimaryAction,
    type ProjectHealthAction,
  } from '@/services/projectExperience';
  import {
    semEvoSQLService,
    type ProjectHealth,
    type ProjectInitializationView,
    type SemanticProjectVersion,
  } from '@/services/semevosql';

  const ProjectDatasourceBindings = defineAsyncComponent(
    () => import('@/components/project/ProjectDatasourceBindings.vue'),
  );
  const ProjectDocuments = defineAsyncComponent(
    () => import('@/components/project/ProjectDocuments.vue'),
  );
  const ProjectEvaluations = defineAsyncComponent(
    () => import('@/components/project/ProjectEvaluations.vue'),
  );
  const ProjectGrillMe = defineAsyncComponent(
    () => import('@/components/project/ProjectGrillMe.vue'),
  );
  const ProjectLearningInbox = defineAsyncComponent(
    () => import('@/components/project/ProjectLearningInbox.vue'),
  );
  const ProjectMcpDeployment = defineAsyncComponent(
    () => import('@/components/project/ProjectMcpDeployment.vue'),
  );
  const ProjectQueryExamples = defineAsyncComponent(
    () => import('@/components/project/ProjectQueryExamples.vue'),
  );
  const ProjectSemanticGovernance = defineAsyncComponent(
    () => import('@/components/project/ProjectSemanticGovernance.vue'),
  );
  const ProjectRuntimeOptimization = defineAsyncComponent(
    () => import('@/components/project/ProjectRuntimeOptimization.vue'),
  );
  const ProjectSemanticEvolution = defineAsyncComponent(
    () => import('@/components/project/ProjectSemanticEvolution.vue'),
  );
  const ProjectSemanticWorkspace = defineAsyncComponent(
    () => import('@/components/project/ProjectSemanticWorkspace.vue'),
  );
  const ProjectTrajectory = defineAsyncComponent(
    () => import('@/components/project/ProjectTrajectory.vue'),
  );

  type ProjectSection = 'overview' | 'external' | 'prepare' | 'improve' | 'governance';

  const route = useRoute();
  const router = useRouter();
  const projectId = Number(route.params.id);
  const evolutionCandidateId = computed(() => {
    const value = String(route.query.candidateId || '').trim();
    return value || undefined;
  });
  const projectView = ref<ProjectInitializationView>();
  const health = ref<ProjectHealth>();
  const healthLoading = ref(false);
  const healthError = ref('');
  const versions = ref<SemanticProjectVersion[]>([]);
  const loading = ref(false);

  const legacySectionMap: Record<string, ProjectSection> = {
    overview: 'overview',
    external: 'external',
    mcp: 'external',
    integration: 'external',
    prepare: 'prepare',
    data: 'prepare',
    business: 'prepare',
    datasources: 'prepare',
    documents: 'prepare',
    semantic: 'prepare',
    grill: 'prepare',
    improve: 'improve',
    inbox: 'improve',
    examples: 'improve',
    trajectory: 'improve',
    evolution: 'improve',
    optimization: 'improve',
    governance: 'governance',
    test: 'governance',
    evaluations: 'governance',
    release: 'governance',
    versions: 'governance',
    releases: 'governance',
  };
  const requestedSection = String(route.query.section || route.query.tab || 'overview');
  const activeSection = ref<ProjectSection>(legacySectionMap[requestedSection] || 'overview');
  const prepareTab = ref(
    ['datasources', 'documents', 'semantic', 'grill'].includes(requestedSection)
      ? requestedSection
      : requestedSection === 'business'
        ? 'semantic'
        : 'datasources',
  );
  const requestedImproveTab = requestedSection === 'evolution' ? 'semantic' : requestedSection;
  const improveTab = ref(
    ['inbox', 'semantic', 'examples', 'trajectory', 'optimization'].includes(requestedImproveTab)
      ? requestedImproveTab
      : 'inbox',
  );
  const governanceTab = ref(
    ['release', 'versions', 'releases'].includes(requestedSection) ? 'release' : 'test',
  );
  const canEditProject = computed(() => true);
  const canReviewProject = computed(() => true);
  const canPublishProject = computed(() => true);
  const canAdminOperations = computed(() => true);
  const canManageProject = computed(() => true);
  const sectionVisible = (section: ProjectSection) => projectSectionVisible(section);
  const primaryAction = computed(() => projectPrimaryAction(health.value));
  const primaryActionLabel = computed(() => {
    if (
      health.value?.queryReady ||
      (!health.value && projectView.value?.project.activePublishedVersionId)
    ) {
      return '开始问数';
    }
    return primaryAction.value?.label || '继续准备';
  });
  const firstRunSubmitting = ref(false);
  const firstRunMode = computed(() => route.query.onboarding === '1' && !health.value?.queryReady);
  const firstRunRulesReady = computed(
    () =>
      Boolean(health.value?.understanding.catalogReady) &&
      (health.value?.understanding.openGapCount || 0) === 0 &&
      (health.value?.understanding.unresolvedConflictCount || 0) === 0,
  );
  const firstRunDescription = computed(() => {
    if (!health.value) return '正在读取项目准备状态。';
    if (!firstRunRulesReady.value)
      return `系统已完成基础理解，还需要确认 ${health.value.understanding.openGapCount} 个业务问题和 ${health.value.understanding.unresolvedConflictCount} 个冲突。`;
    if (health.value.workingVersion?.status === 'DRAFT')
      return '关键业务规则已确认，下一步验证当前业务模型。';
    if (
      health.value.workingVersion?.status === 'VALIDATED' ||
      health.value.workingVersion?.status === 'READY'
    )
      return '业务模型已经通过验证，下一步发布并激活供新会话使用。';
    if (health.value.workingVersion?.status === 'PUBLISHED')
      return '业务模型已经发布，下一步激活并开始第一次问数。';
    return '按照当前项目事实完成剩余准备步骤。';
  });
  const firstRunActionLabel = computed(() => {
    if (!health.value || !firstRunRulesReady.value) return '继续确认业务规则';
    if (health.value.workingVersion?.status === 'DRAFT') return '验证业务模型';
    if (
      health.value.workingVersion?.status === 'VALIDATED' ||
      health.value.workingVersion?.status === 'READY'
    )
      return '发布并继续';
    if (health.value.workingVersion?.status === 'PUBLISHED') return '激活并开始问数';
    return '继续设置';
  });
  const firstRunActionAllowed = computed(() => {
    if (!health.value || !firstRunRulesReady.value) return canEditProject.value;
    const status = health.value.workingVersion?.status;
    if (status === 'DRAFT') return canReviewProject.value;
    if (status === 'VALIDATED' || status === 'READY' || status === 'PUBLISHED') {
      return canPublishProject.value;
    }
    return canEditProject.value;
  });
  const firstRunActionHint = computed(
    () => health.value?.nextActions[0]?.description || '系统只会执行当前事实允许的下一步。',
  );

  const loadHealth = async () => {
    healthLoading.value = true;
    healthError.value = '';
    try {
      health.value = await semEvoSQLService.projectHealth(projectId);
    } catch (error) {
      healthError.value = error instanceof Error ? error.message : '项目健康信息加载失败';
    } finally {
      healthLoading.value = false;
    }
  };

  const load = async () => {
    loading.value = true;
    try {
      const [nextProjectView, nextVersions] = await Promise.all([
        semEvoSQLService.project(projectId),
        semEvoSQLService.projectVersions(projectId),
      ]);
      projectView.value = nextProjectView;
      versions.value = nextVersions;
      if (!sectionVisible(activeSection.value)) {
        activeSection.value = 'overview';
        void router.replace({ query: { ...route.query, section: 'overview', tab: undefined } });
      }
      await loadHealth();
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '项目详情加载失败');
    } finally {
      loading.value = false;
    }
  };

  const syncSectionRoute = (name: string | number) => {
    void router.replace({ query: { ...route.query, section: String(name), tab: undefined } });
  };

  const syncPrepareTabRoute = (name: string | number) => {
    activeSection.value = 'prepare';
    syncSectionRoute(name);
  };

  const syncGovernanceTabRoute = (name: string | number) => {
    activeSection.value = 'governance';
    syncSectionRoute(name);
  };

  const openChat = () => router.push({ path: '/chat', query: { projectId } });

  const navigateSection = (target: ProjectHealthAction['target']) => {
    if (target === 'chat') {
      void openChat();
      return;
    }
    const section = projectDetailSectionForTarget(target);
    if (section === 'chat') {
      void openChat();
      return;
    }
    activeSection.value = section;
    const subsection = projectDetailSubsectionForTarget(target);
    if (section === 'prepare' && subsection) prepareTab.value = subsection;
    if (section === 'governance' && subsection) governanceTab.value = subsection;
    if (section === 'improve' && subsection) improveTab.value = subsection;
    syncSectionRoute(subsection || section);
  };

  const handleLifecycleAction = (target: ProjectHealthAction['target']) => navigateSection(target);

  const handlePrimaryAction = () => {
    if (
      health.value?.queryReady ||
      (!health.value && projectView.value?.project.activePublishedVersionId)
    ) {
      void openChat();
      return;
    }
    if (primaryAction.value) navigateSection(primaryAction.value.target);
  };

  const openImprovementDetail = (target: 'semantic' | 'examples' | 'optimization') => {
    activeSection.value = 'improve';
    improveTab.value = target;
    syncSectionRoute(target === 'semantic' ? 'evolution' : target);
  };

  const exitFirstRun = () =>
    router.replace({
      query: { ...route.query, onboarding: undefined, section: activeSection.value },
    });

  const handleOnboardingCompleted = async () => {
    await load();
    if (health.value?.understanding.catalogReady) activeSection.value = 'overview';
  };

  const continueFirstRun = async () => {
    if (!health.value) return;
    if (!firstRunRulesReady.value) {
      activeSection.value = 'prepare';
      prepareTab.value = 'grill';
      syncSectionRoute('grill');
      return;
    }
    const working = health.value.workingVersion;
    if (!working) return;
    firstRunSubmitting.value = true;
    try {
      if (working.status === 'DRAFT') {
        await semEvoSQLService.validateProjectVersion(projectId, working.id);
        ElMessage.success('业务模型验证通过');
        await load();
        return;
      }
      if (working.status === 'VALIDATED' || working.status === 'READY') {
        await semEvoSQLService.publishProjectVersion(projectId, working.id);
        await load();
        if (health.value?.queryReady) {
          ElMessage.success('业务模型已发布并激活，可以开始第一次问数');
          await openChat();
        } else {
          ElMessage.success('业务模型已发布，下一步激活正式版本');
        }
        return;
      }
      if (working.status === 'PUBLISHED') {
        await semEvoSQLService.activateProjectVersion(projectId, working.id);
        ElMessage.success('业务模型已激活，可以开始问数');
        await openChat();
        return;
      }
      const target = health.value.nextActions[0]?.target;
      if (target === 'chat') await openChat();
      else if (target) navigateSection(target);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '当前步骤未完成');
    } finally {
      firstRunSubmitting.value = false;
    }
  };

  onMounted(load);
</script>

<style scoped>
  .detail-page {
    max-width: 1440px;
    min-height: 600px;
    margin: 0 auto;
    padding: 30px;
  }
  .detail-heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 20px;
  }
  .detail-heading h1 {
    margin: 10px 0 6px;
    color: #0f172a;
    font-size: 30px;
  }
  .detail-heading p {
    margin: 0;
    color: #64748b;
  }
  .heading-actions {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-top: 34px;
  }
  .health-error-alert {
    margin-top: 20px;
  }
  .health-error-content {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 14px;
  }
  .first-run-card {
    margin-top: 22px;
    border-color: #bfdbfe;
    border-radius: 16px;
    background: #f8fbff;
  }
  .first-run-heading,
  .first-run-action {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 18px;
  }
  .first-run-heading {
    margin-bottom: 18px;
  }
  .first-run-heading span {
    color: #2563eb;
    font-size: 12px;
    font-weight: 700;
  }
  .first-run-heading h2 {
    margin: 5px 0 6px;
    color: #0f172a;
    font-size: 20px;
  }
  .first-run-heading p,
  .first-run-action span {
    margin: 0;
    color: #64748b;
    line-height: 1.6;
  }
  .first-run-action {
    align-items: center;
    margin-top: 18px;
  }
  .content-card {
    min-height: 520px;
    margin-top: 22px;
    border-radius: 16px;
  }
  .primary-tabs :deep(> .el-tabs__header .el-tabs__item) {
    height: 48px;
    padding: 0 22px;
    font-weight: 600;
  }
  .secondary-tabs {
    margin-top: 4px;
  }
  .secondary-tabs :deep(.el-tabs__item) {
    font-weight: 500;
  }
  .group-intro {
    margin: 4px 0 16px;
  }
  .section-copy,
  .tab-toolbar {
    margin-bottom: 18px;
  }
  .section-copy h2,
  .tab-toolbar h2,
  .release-placeholder h2 {
    margin: 0 0 6px;
    color: #0f172a;
    font-size: 18px;
  }
  .section-copy p,
  .tab-toolbar p,
  .release-placeholder p {
    margin: 0;
    color: #64748b;
    line-height: 1.6;
  }
  .tab-toolbar {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
  }
  .subtle {
    color: #94a3b8;
    font-size: 12px;
  }
  .release-placeholder {
    padding: 8px 0;
  }
  @media (max-width: 800px) {
    .detail-page {
      padding: 18px 10px;
    }
    .detail-heading,
    .tab-toolbar {
      flex-direction: column;
    }
    .heading-actions {
      margin-top: 0;
    }
    .health-error-content,
    .first-run-heading,
    .first-run-action {
      align-items: stretch;
      flex-direction: column;
    }
  }
</style>
