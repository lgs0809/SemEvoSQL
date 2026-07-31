<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <BaseLayout>
    <section class="wizard-page">
      <div class="wizard-heading">
        <div>
          <el-button link :icon="ArrowLeft" @click="router.push('/projects')">返回项目</el-button>
          <h1>创建数据项目</h1>
          <p>连接业务数据库，按需补充资料，SemEvoSQL 会自动理解表结构并只追问必要的业务规则。</p>
        </div>
      </div>

      <el-alert
        v-if="modelReadyChecked && (!platformReadiness?.ready || chatModels.length === 0)"
        class="blocking-alert"
        type="error"
        show-icon
        :closable="false"
        title="项目理解所需模型能力尚未准备好。"
      >
        <template #default>
          <div class="blocker-actions">
            <span>{{ modelReadinessMessage }}</span>
            <el-button type="primary" size="small" @click="router.push('/admin/models')">
              配置模型
            </el-button>
          </div>
        </template>
      </el-alert>

      <el-card shadow="never" class="wizard-card">
        <el-steps :active="activeStep" finish-status="success" align-center>
          <el-step title="项目" />
          <el-step title="数据连接" />
          <el-step title="业务资料" />
          <el-step title="启动理解" />
        </el-steps>

        <div class="step-content">
          <el-form v-if="activeStep === 0" label-position="top" :model="form">
            <div class="intro-block">
              <h2>这个项目要回答什么业务问题？</h2>
              <p>项目名称和数据库连接是必要信息；其余工程参数会使用安全默认值。</p>
            </div>
            <el-form-item label="项目名称" required>
              <el-input v-model="form.name" size="large" placeholder="输入项目名称" />
            </el-form-item>
            <el-form-item label="项目描述（可选）">
              <el-input
                v-model="form.description"
                type="textarea"
                :rows="4"
                placeholder="说明项目要解决的业务问题和分析范围（可选）"
              />
            </el-form-item>

            <el-collapse class="advanced-collapse">
              <el-collapse-item title="高级设置">
                <div class="form-grid">
                  <el-form-item label="项目编码">
                    <el-input v-model="form.projectCode" placeholder="自动生成" />
                  </el-form-item>
                  <el-form-item label="业务域">
                    <el-input v-model="form.businessDomain" placeholder="自动使用项目编码" />
                  </el-form-item>
                  <el-form-item label="首个业务模型版本">
                    <el-input v-model="form.firstVersionNumber" />
                  </el-form-item>
                  <el-form-item label="项目理解模型">
                    <el-select
                      v-model="form.initializationModelId"
                      filterable
                      placeholder="平台默认对话模型"
                    >
                      <el-option
                        v-for="model in chatModels"
                        :key="model.id"
                        :label="modelDisplayLabel(model)"
                        :value="model.id"
                      />
                    </el-select>
                  </el-form-item>
                </div>
              </el-collapse-item>
            </el-collapse>
          </el-form>

          <ProjectConnectionStep
            v-else-if="activeStep === 1"
            :bindings="datasourceBindings"
            :available-datasources="availableDatasources"
            :project-name="form.name"
            :project-code="form.projectCode"
            :business-domain="form.businessDomain"
            @update:bindings="datasourceBindings = $event"
            @update:available-datasources="availableDatasources = $event"
          />

          <div v-else-if="activeStep === 2">
            <div class="section-title">
              <div>
                <h2>补充业务资料</h2>
                <p>可选。指标文档、数据字典、业务需求、历史 SQL 等会帮助系统更快理解业务口径。</p>
              </div>
              <el-button :icon="Plus" @click="addDocument">添加资料</el-button>
            </div>
            <el-alert
              type="info"
              show-icon
              :closable="false"
              title="资料类型会根据文件名和扩展名自动识别，你也可以手动修正。正式查询只使用发布后的结构化业务模型。"
            />
            <el-card
              v-for="(item, index) in documents"
              :key="item.key"
              shadow="never"
              class="document-card"
            >
              <div class="document-heading">
                <div>
                  <strong>{{ item.file?.name || `业务资料 ${index + 1}` }}</strong>
                  <span v-if="item.file">识别为：{{ documentTypeLabel(item.documentType) }}</span>
                </div>
                <el-button link type="danger" @click="documents.splice(index, 1)">删除</el-button>
              </div>
              <div class="document-grid">
                <el-form-item label="文件" required>
                  <input
                    class="file-input"
                    type="file"
                    accept=".pdf,.doc,.docx,.xls,.xlsx,.csv,.txt,.md,.markdown,.json,.yaml,.yml,.sql"
                    @change="selectDocumentFile($event, item)"
                  />
                </el-form-item>
                <el-form-item label="资料类型（可修正）">
                  <el-select v-model="item.documentType">
                    <el-option
                      v-for="option in documentTypeOptions"
                      :key="option.value"
                      :label="option.label"
                      :value="option.value"
                    />
                  </el-select>
                </el-form-item>
              </div>
              <el-collapse>
                <el-collapse-item title="关联信息（可选）">
                  <div class="document-grid">
                    <el-form-item label="关联数据连接">
                      <el-select v-model="item.datasourceId" clearable placeholder="系统自动判断">
                        <el-option
                          v-for="binding in datasourceBindings"
                          :key="binding.key"
                          :label="datasourceName(binding.datasourceId)"
                          :value="binding.datasourceId"
                          :disabled="!binding.datasourceId"
                        />
                      </el-select>
                    </el-form-item>
                    <el-form-item label="来源位置">
                      <el-input
                        v-model="item.sourceLocation"
                        placeholder="填写资料所在章节、页码或表格位置"
                      />
                    </el-form-item>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </el-card>
            <el-empty
              v-if="documents.length === 0"
              description="没有业务资料也可以继续，系统会先从数据库结构开始理解"
            />
          </div>

          <div v-else class="ready-step">
            <div class="intro-block">
              <h2>准备启动业务理解</h2>
              <p>
                接下来会使用真实数据库结构和你提供的资料构建业务模型，并生成必要的业务澄清问题。
              </p>
            </div>
            <div class="summary-grid">
              <el-card shadow="never">
                <span>项目</span>
                <strong>{{ form.name }}</strong>
                <small>{{ form.description || '未填写描述' }}</small>
              </el-card>
              <el-card shadow="never">
                <span>数据连接</span>
                <strong>{{ datasourceBindings.length }} 个</strong>
                <small>{{ selectedTableCount }} 张业务表</small>
              </el-card>
              <el-card shadow="never">
                <span>业务资料</span>
                <strong>{{ documents.length }} 份</strong>
                <small>{{ documents.length ? '创建后自动解析' : '从数据库结构开始' }}</small>
              </el-card>
              <el-card shadow="never">
                <span>业务模型</span>
                <strong>v{{ form.firstVersionNumber }}</strong>
                <small>分析完成并确认后才可发布</small>
              </el-card>
            </div>
            <el-alert
              class="flow-alert"
              type="success"
              show-icon
              :closable="false"
              title="创建后将依次启动模型、扫描所选数据库表、解析资料并进入必要业务澄清；不会跳过验证和发布门禁。"
            />
          </div>
        </div>

        <div class="wizard-actions">
          <el-button :disabled="activeStep === 0 || submitting" @click="activeStep--">
            上一步
          </el-button>
          <el-button
            v-if="activeStep < 3"
            type="primary"
            :disabled="!platformReadiness?.ready || chatModels.length === 0"
            @click="nextStep"
          >
            下一步
          </el-button>
          <el-button v-else type="primary" :loading="submitting" @click="createProject">
            创建项目并启动理解
          </el-button>
        </div>
      </el-card>
    </section>
  </BaseLayout>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue';
  import { useRouter } from 'vue-router';
  import { ElMessage } from 'element-plus';
  import { ArrowLeft, Plus } from '@element-plus/icons-vue';
  import BaseLayout from '@/layouts/BaseLayout.vue';
  import ProjectConnectionStep from '@/components/project/ProjectConnectionStep.vue';
  import { platformContext, type PlatformReadiness } from '@/services/platformContext';
  import datasourceService, { type Datasource } from '@/services/datasource';
  import modelConfigService, { type ModelConfig } from '@/services/modelConfig';
  import { getApiErrorMessage } from '@/services/common';
  import { semEvoSQLService, type ProjectDocumentType } from '@/services/semevosql';
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

  interface DocumentDraft {
    key: number;
    file?: File;
    documentType: ProjectDocumentType;
    datasourceId?: number;
    sourceLocation: string;
  }

  const router = useRouter();
  const activeStep = ref(0);
  const submitting = ref(false);
  const modelReadyChecked = ref(false);
  const platformReadiness = ref<PlatformReadiness>();
  const chatModels = ref<ModelConfig[]>([]);
  const availableDatasources = ref<Datasource[]>([]);
  const datasourceBindings = ref<DatasourceBindingDraft[]>([]);
  const documents = ref<DocumentDraft[]>([]);
  const form = reactive({
    name: '',
    projectCode: '',
    businessDomain: '',
    description: '',
    firstVersionNumber: '1.0.0',
    initializationModelId: undefined as number | undefined,
  });

  const versionPattern = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
  const documentTypeOptions: Array<{ value: ProjectDocumentType; label: string }> = [
    { value: 'DATA_DICTIONARY', label: '数据字典 / Schema 说明' },
    { value: 'METRIC_SPEC', label: '指标口径' },
    { value: 'GLOSSARY', label: '业务术语' },
    { value: 'REPORT_SPEC', label: '报表 / BI 说明' },
    { value: 'HISTORICAL_SQL', label: '历史 SQL' },
    { value: 'SYSTEM_RESPONSIBILITY', label: '系统职责 / 设计' },
    { value: 'SYNC_POLICY', label: '同步与时效说明' },
    { value: 'ENUM_SPEC', label: '枚举说明' },
    { value: 'REQUIREMENT', label: '业务需求' },
  ];

  const selectedTableCount = computed(() =>
    datasourceBindings.value.reduce((sum, item) => sum + item.exposedTables.length, 0),
  );
  const modelReadinessMessage = computed(() => {
    const missing: string[] = [];
    if (!platformReadiness.value?.chatModelReady || chatModels.value.length === 0)
      missing.push('对话模型');
    if (!platformReadiness.value?.embeddingModelReady) missing.push('向量模型');
    if (!platformReadiness.value?.rerankModelReady) missing.push('重排模型');
    return missing.length
      ? `${missing.join('、')} 当前不可用。可以查看已有项目，但创建和项目理解需要先恢复这些模型能力。`
      : '模型能力状态暂时无法确认，请检查系统模型配置。';
  });

  const createProjectCode = (name: string) => {
    const slug = name
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 40);
    return slug || `project-${Date.now().toString(36)}`;
  };

  const ensureGeneratedFields = () => {
    if (!form.projectCode.trim()) form.projectCode = createProjectCode(form.name);
    if (!form.businessDomain.trim()) form.businessDomain = form.projectCode;
    datasourceBindings.value.forEach((item, index) => {
      item.domainCode =
        item.domainCode.trim() ||
        (index === 0 ? form.businessDomain : `${form.businessDomain}-${index + 1}`);
      item.domainName = item.domainName.trim() || form.name;
      item.responsibility = item.responsibility.trim() || `为${form.name}查询提供业务数据`;
    });
  };

  const validateBindings = () => {
    ensureGeneratedFields();
    if (datasourceBindings.value.length === 0) {
      ElMessage.warning('请至少选择一个数据连接');
      return false;
    }
    const ids = new Set<number>();
    for (const item of datasourceBindings.value) {
      if (!item.datasourceId) {
        ElMessage.warning('请选择每条数据连接');
        return false;
      }
      if (ids.has(item.datasourceId)) {
        ElMessage.warning('同一个项目版本不能重复选择同一数据连接');
        return false;
      }
      ids.add(item.datasourceId);
      if (item.exposedTables.length === 0) {
      ElMessage.warning('每个数据连接至少选择一张供查询使用的表');
        return false;
      }
    }
    return true;
  };

  const addDocument = () => {
    documents.value.push({
      key: Date.now() + documents.value.length,
      file: undefined,
      documentType: 'REQUIREMENT',
      datasourceId:
        datasourceBindings.value.length === 1
          ? datasourceBindings.value[0].datasourceId
          : undefined,
      sourceLocation: '',
    });
  };

  const inferDocumentType = (filename: string): ProjectDocumentType => {
    const name = filename.toLowerCase();
    if (name.endsWith('.sql') || /sql|查询/.test(name)) return 'HISTORICAL_SQL';
    if (/指标|metric|口径/.test(name)) return 'METRIC_SPEC';
    if (/字典|dictionary|schema|字段/.test(name)) return 'DATA_DICTIONARY';
    if (/术语|glossary|词汇/.test(name)) return 'GLOSSARY';
    if (/枚举|enum|状态码/.test(name)) return 'ENUM_SPEC';
    if (/同步|时效|fresh|sync|sla/.test(name)) return 'SYNC_POLICY';
    if (/报表|report|dashboard|bi/.test(name)) return 'REPORT_SPEC';
    if (/架构|设计|system|service|系统/.test(name)) return 'SYSTEM_RESPONSIBILITY';
    return 'REQUIREMENT';
  };

  const selectDocumentFile = (event: Event, item: DocumentDraft) => {
    const input = event.target as HTMLInputElement;
    item.file = input.files?.[0];
    if (item.file) item.documentType = inferDocumentType(item.file.name);
  };

  const documentTypeLabel = (type: ProjectDocumentType) =>
    documentTypeOptions.find(item => item.value === type)?.label || type;

  const datasourceName = (datasourceId?: number) => {
    const datasource = availableDatasources.value.find(item => item.id === datasourceId);
    return datasource
      ? `${datasourceDisplayName(datasource)} · ${datasourceTypeLabel(datasource.type)}`
      : datasourceId
        ? `数据连接 ${datasourceId}`
        : '未选择';
  };

  const modelDisplayLabel = (model: ModelConfig) => {
    const provider = String(model.provider || '').toLowerCase();
    const providerLabel = provider.includes('openai')
      ? 'OpenAI 对话模型'
      : provider.includes('deepseek')
        ? 'DeepSeek 对话模型'
        : provider.includes('qwen') || provider.includes('local')
          ? '本地模型服务'
          : model.provider || '自定义模型服务';
    return `${providerLabel} · ${model.modelName}`;
  };

  const nextStep = () => {
    if (!platformReadiness.value?.ready) {
      ElMessage.warning('对话模型、向量模型与重排模型均准备好后才能开始创建项目');
      return;
    }
    if (activeStep.value === 0) {
      if (!form.name.trim()) {
        ElMessage.warning('请填写项目名称');
        return;
      }
      if (!form.initializationModelId) {
      ElMessage.warning('没有可用的对话模型，请先完成模型配置');
        return;
      }
      ensureGeneratedFields();
      if (!versionPattern.test(form.firstVersionNumber)) {
        ElMessage.warning('业务模型版本号必须使用 x.x.x 格式');
        return;
      }
      if (datasourceBindings.value.length === 0) {
        datasourceBindings.value = [
          {
            key: Date.now(),
            datasourceId: undefined,
            domainCode: form.businessDomain,
            domainName: form.name,
            responsibility: `为${form.name}查询提供业务数据`,
            priority: 100,
            exposedTables: [],
            tableOptions: [],
            loadingTables: false,
          },
        ];
      }
    }
    if (activeStep.value === 1 && !validateBindings()) return;
    if (activeStep.value === 2 && documents.value.some(item => !item.file)) {
      ElMessage.warning('请为每条资料选择文件，或删除空记录');
      return;
    }
    activeStep.value++;
  };

  const createProject = async () => {
    if (!platformReadiness.value?.ready) {
      ElMessage.warning('模型能力当前不可用，暂时不能创建并理解新项目');
      return;
    }
    if (!form.initializationModelId || !validateBindings()) return;
    if (documents.value.some(item => !item.file)) {
      ElMessage.warning('请为每条资料选择文件，或删除空记录');
      return;
    }
    submitting.value = true;
    let createdProjectId: number | undefined;
    try {
      const created = await semEvoSQLService.createProject({
        projectCode: form.projectCode.trim(),
        name: form.name.trim(),
        businessDomain: form.businessDomain.trim(),
        description: form.description.trim(),
        firstVersionNumber: form.firstVersionNumber,
        source: 'project-wizard',
        datasourceBindings: datasourceBindings.value.map(item => ({
          datasourceId: item.datasourceId as number,
          domainCode: item.domainCode.trim(),
          domainName: item.domainName.trim(),
          responsibility: item.responsibility.trim(),
          priority: item.priority,
          exposedTables: item.exposedTables,
        })),
      });
      if (!created.version) throw new Error('项目首个业务模型版本创建失败');
      createdProjectId = created.project.id;

      await semEvoSQLService.initializeProjectVersion(
        created.project.id,
        created.version.id,
        form.initializationModelId,
      );

      for (const binding of datasourceBindings.value) {
        await semEvoSQLService.scanProjectDatasource(
          created.project.id,
          created.version.id,
          binding.datasourceId as number,
          binding.exposedTables,
        );
      }

      for (const document of documents.value) {
        await semEvoSQLService.uploadProjectDocument(created.project.id, created.version.id, {
          documentType: document.documentType,
          datasourceId: document.datasourceId,
          sourceLocation: document.sourceLocation,
          sourceName: document.file?.name,
          file: document.file as File,
        });
      }

      await semEvoSQLService.startOnboarding(created.project.id, created.version.id);

      ElMessage.success('项目已创建，接下来只需确认系统无法安全推断的业务规则');
      await router.push({
        path: `/projects/${created.project.id}`,
        query: { section: 'grill', onboarding: '1' },
      });
    } catch (error) {
      if (createdProjectId) {
        ElMessage.error(
          `${getApiErrorMessage(error, '自动理解启动失败')}。项目草稿已保留，可在项目页继续处理。`,
        );
        await router.push({
          path: `/projects/${createdProjectId}`,
          query: { section: 'overview', onboarding: '1' },
        });
      } else {
        ElMessage.error(getApiErrorMessage(error, '项目创建失败'));
      }
    } finally {
      submitting.value = false;
    }
  };

  onMounted(async () => {
    try {
      const [readiness, models] = await Promise.all([
        platformContext.readiness(true),
        modelConfigService.list(),
      ]);
      platformReadiness.value = readiness;
      chatModels.value = models.filter(
        item =>
          item.modelType === 'CHAT' &&
          typeof item.id === 'number' &&
          item.validationStatus === 'PASSED',
      );
      const preferred = chatModels.value.find(item => item.isActive) || chatModels.value[0];
      form.initializationModelId = preferred?.id;
    } catch (error) {
      platformReadiness.value = undefined;
      ElMessage.error(getApiErrorMessage(error, '模型能力状态加载失败'));
    } finally {
      modelReadyChecked.value = true;
    }

    try {
      availableDatasources.value = (await datasourceService.getAllDatasource()).filter(
        item => typeof item.id === 'number',
      );
    } catch (error) {
      ElMessage.error(getApiErrorMessage(error, '数据连接列表加载失败'));
    }
  });
</script>

<style scoped>
  .wizard-page {
    max-width: 1120px;
    margin: 0 auto;
    padding: 28px;
  }
  .wizard-heading h1 {
    margin: 12px 0 6px;
    color: #0f172a;
    font-size: 30px;
  }
  .wizard-heading p,
  .section-title p,
  .intro-block p {
    margin: 0;
    color: #64748b;
  }
  .blocking-alert {
    margin-top: 18px;
  }
  .blocker-actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .wizard-card {
    margin-top: 22px;
    border-radius: 16px;
  }
  .step-content {
    min-height: 430px;
    padding: 42px 18px 24px;
  }
  .intro-block {
    margin-bottom: 24px;
  }
  .intro-block h2,
  .section-title h2 {
    margin: 0 0 7px;
    color: #0f172a;
  }
  .form-grid,
  .document-grid,
  .summary-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0 20px;
  }
  .advanced-collapse {
    margin-top: 18px;
  }
  .section-title,
  .document-heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
  }
  .document-card {
    margin-top: 14px;
  }
  .document-heading {
    align-items: center;
    margin-bottom: 12px;
  }
  .document-heading > div {
    display: grid;
    gap: 3px;
  }
  .document-heading span {
    color: #64748b;
    font-size: 12px;
  }
  .file-input {
    box-sizing: border-box;
    width: 100%;
    min-height: 32px;
    padding: 5px;
    border: 1px solid #dcdfe6;
    border-radius: 4px;
  }
  .ready-step {
    max-width: 920px;
    margin: 0 auto;
  }
  .summary-grid {
    gap: 14px;
  }
  .summary-grid :deep(.el-card__body) {
    display: grid;
    gap: 6px;
  }
  .summary-grid span,
  .summary-grid small {
    color: #64748b;
  }
  .summary-grid strong {
    color: #0f172a;
    font-size: 18px;
  }
  .flow-alert {
    margin-top: 22px;
  }
  .wizard-actions {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    border-top: 1px solid #e2e8f0;
    padding: 20px 18px 4px;
  }
  @media (max-width: 760px) {
    .wizard-page {
      padding: 16px 10px;
    }
    .form-grid,
    .document-grid,
    .summary-grid {
      grid-template-columns: 1fr;
    }
    .section-title,
    .blocker-actions {
      flex-direction: column;
    }
  }
</style>
