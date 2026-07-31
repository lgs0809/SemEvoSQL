<!--
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
-->
<template>
  <BaseLayout>
    <div class="model-config-page">
      <!-- 主内容区域 -->
      <main class="main-content">
        <!-- 内容头部 -->
        <div class="content-header">
          <div class="header-info">
            <h1 class="content-title">模型服务</h1>
            <p class="content-subtitle">管理对话、向量和重排模型，并在接入前验证实时可用性。</p>
          </div>
        </div>

        <!-- 操作区域 -->
        <div class="action-section">
          <el-card>
            <div class="action-content">
              <div class="action-buttons">
                <el-button type="primary" :icon="Plus" @click="showAddDialog" size="large">
                  新增配置
                </el-button>
                <el-button :icon="Refresh" @click="loadConfigs" size="large">刷新</el-button>
              </div>
              <div class="filter-options">
                <el-select
                  v-model="activeFilter"
                  placeholder="筛选模型类型"
                  size="large"
                  clearable
                  style="width: 300px"
                >
                  <el-option label="全部" value="" />
                  <el-option label="对话模型" value="CHAT" />
                  <el-option label="向量模型" value="EMBEDDING" />
                  <el-option label="重排模型" value="RERANK" />
                </el-select>
              </div>
            </div>
          </el-card>
        </div>

        <!-- 配置表格 -->
        <div class="config-table" v-if="!loading">
          <el-card>
            <el-table :data="filteredConfigs" style="width: 100%" stripe>
              <el-table-column type="expand" width="48">
                <template #default="scope">
                  <div class="advanced-config">
                    <h4>高级连接配置</h4>
                    <el-descriptions :column="2" border>
                      <el-descriptions-item label="API 地址">
                        {{ scope.row.baseUrl }}
                      </el-descriptions-item>
                      <el-descriptions-item label="接口路径">
                        {{
                          scope.row.modelType === 'CHAT'
                            ? scope.row.completionsPath || '默认对话路径'
                            : scope.row.modelType === 'EMBEDDING'
                              ? scope.row.embeddingsPath || '默认嵌入路径'
                              : scope.row.rerankPath || '/v1/rerank'
                        }}
                      </el-descriptions-item>
                      <el-descriptions-item v-if="scope.row.modelType === 'CHAT'" label="温度">
                        {{ scope.row.temperature ?? 0 }}
                      </el-descriptions-item>
                      <el-descriptions-item v-if="scope.row.modelType === 'CHAT'" label="最大输出长度（Token）">
                        {{ scope.row.maxTokens || 2000 }}
                      </el-descriptions-item>
                      <el-descriptions-item label="密钥状态">
                        {{
                          scope.row.apiKeyConfigured ? scope.row.apiKeyHint || '已配置' : '未配置'
                        }}
                      </el-descriptions-item>
                      <el-descriptions-item label="代理">
                        {{
                          scope.row.proxyEnabled
                            ? `${scope.row.proxyHost || '-'}:${scope.row.proxyPort || '-'}`
                            : '未启用'
                        }}
                      </el-descriptions-item>
                    </el-descriptions>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="模型" min-width="240">
                <template #default="scope">
                  <strong>{{ scope.row.modelName }}</strong>
                  <div class="model-meta">
                    {{ providerLabel(scope.row.provider, scope.row.modelType) }}
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="用途" width="130">
                <template #default="scope">
                  <el-tag
                    :type="scope.row.modelType === 'CHAT' ? 'primary' : 'success'"
                    effect="plain"
                  >
                    {{
                      scope.row.modelType === 'CHAT'
                        ? '业务理解 / 查询'
                        : scope.row.modelType === 'EMBEDDING'
                          ? '语义向量'
                          : '语义重排'
                    }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="配置状态" width="130">
                <template #default="scope">
                  <el-tag :type="scope.row.isActive ? 'success' : 'info'" effect="plain">
                    {{ scope.row.isActive ? '当前启用' : '备用配置' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="可用性验证" min-width="190">
                <template #default="scope">
                  <el-tag :type="validationTagType(scope.row.validationStatus)" effect="plain">
                    {{ validationLabel(scope.row.validationStatus) }}
                  </el-tag>
                  <div class="model-meta">{{ validationTime(scope.row.lastValidationTime) }}</div>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="300" fixed="right">
                <template #default="scope">
                  <div class="action-buttons-cell">
                    <el-button
                      type="primary"
                      plain
                      size="small"
                      @click="handleTestConnection(scope.row)"
                      :loading="testingId === scope.row.id"
                    >
                      验证可用性
                    </el-button>
                    <el-button
                      v-if="!scope.row.isActive"
                      type="success"
                      size="small"
                      @click="handleActivate(scope.row.id, scope.row.modelType)"
                      :loading="activatingId === scope.row.id"
                    >
                      设为当前模型
                    </el-button>
                    <el-button type="primary" link @click="handleEdit(scope.row)">编辑</el-button>
                    <el-button type="danger" link @click="handleDelete(scope.row)">删除</el-button>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </div>

        <!-- 加载状态 -->
        <div v-if="loading" class="loading-state">
          <el-skeleton :rows="6" animated />
        </div>

        <!-- 空状态 -->
        <div v-if="!loading && filteredConfigs.length === 0" class="empty-state">
          <el-empty description="暂无模型配置">
            <template #image>
              <el-icon size="60"><Cpu /></el-icon>
            </template>
            <el-button type="primary" :icon="Plus" @click="showAddDialog">新增配置</el-button>
          </el-empty>
        </div>
      </main>

      <!-- 新增/编辑对话框 -->
      <el-dialog
        v-model="dialogVisible"
        :title="dialogTitle"
        width="min(600px, calc(100vw - 32px))"
        :close-on-click-modal="false"
      >
        <el-form
          ref="formRef"
          :model="formData"
          :rules="formRules"
          label-width="120px"
          label-position="left"
        >
          <el-form-item label="提供商" prop="provider">
            <el-select
              v-model="formData.provider"
              filterable
              allow-create
              default-first-option
              placeholder="选择或输入提供商标识"
              style="width: 100%"
              @change="updateBaseUrlByProvider"
            >
              <el-option label="DeepSeek" value="deepseek" />
              <el-option label="Qwen" value="qwen" />
              <el-option label="OpenAI" value="openai" />
              <el-option label="Siliconflow" value="siliconflow" />
              <el-option label="自定义" value="custom" />
            </el-select>
          </el-form-item>

          <el-form-item label="模型类型" prop="modelType">
            <el-radio-group v-model="formData.modelType">
              <el-radio label="CHAT">对话模型</el-radio>
              <el-radio label="EMBEDDING">嵌入模型</el-radio>
              <el-radio label="RERANK">重排模型</el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="模型名称" prop="modelName">
            <el-input
              v-model="formData.modelName"
              placeholder="例如: gpt-4, deepseek-chat,qwen-plus,text-embedding-v4"
            />
          </el-form-item>

          <el-form-item label="API 密钥" prop="apiKey">
            <el-input
              v-model="formData.apiKey"
              type="password"
              show-password
              :placeholder="formData.apiKeyConfigured ? '已配置；留空表示保持不变' : '可选；服务需要鉴权时填写'"
            />
          </el-form-item>

          <el-form-item label="服务地址（Base URL）" prop="baseUrl">
            <el-input
              v-model="formData.baseUrl"
              placeholder="填写模型服务地址，例如 https://api.example.com"
            />
          </el-form-item>

          <el-form-item
            v-if="formData.modelType === 'CHAT'"
            label="对话接口路径"
            prop="completionsPath"
          >
            <el-input
              v-model="formData.completionsPath"
              placeholder="附加到base-url的路径。留空则使用默认值/v1/chat/completions"
            />
          </el-form-item>

          <el-form-item
            v-if="formData.modelType === 'EMBEDDING'"
            label="向量模型路径"
            prop="embeddingsPath"
          >
            <el-input
              v-model="formData.embeddingsPath"
              placeholder="附加到服务地址的路径；留空使用 /v1/embeddings"
            />
          </el-form-item>

          <el-form-item v-if="formData.modelType === 'RERANK'" label="重排模型路径" prop="rerankPath">
            <el-input
              v-model="formData.rerankPath"
              placeholder="附加到服务地址的路径；留空使用 /v1/rerank"
            />
          </el-form-item>

          <el-form-item label="请求超时" prop="requestTimeoutSeconds">
            <el-input-number
              v-model="formData.requestTimeoutSeconds"
              :min="1"
              :max="600"
              :step="5"
              style="width: 100%"
            />
            <div class="form-tip">单次模型请求超时，单位秒；适用于对话、向量和重排模型。</div>
          </el-form-item>

          <el-form-item v-if="formData.modelType === 'CHAT'" label="温度" prop="temperature">
            <el-slider
              v-model="formData.temperature"
              :min="0"
              :max="2"
              :step="0.1"
              show-input
              show-input-controls
            />
            <div class="form-tip">建议默认0。控制生成文本的随机性，值越高越随机</div>
          </el-form-item>

          <el-form-item v-if="formData.modelType === 'CHAT'" label="最大输出长度（Token）" prop="maxTokens">
            <el-input-number
              v-model="formData.maxTokens"
              :min="100"
              :max="10000"
              :step="100"
              style="width: 100%"
            />
            <div class="form-tip">控制生成文本的最大长度</div>
          </el-form-item>
        </el-form>

        <el-divider content-position="left">网络代理配置</el-divider>

        <el-form-item label="启用代理">
          <el-switch v-model="formData.proxyEnabled" />
          <span class="form-tip" style="margin-left: 10px">
            如果您的服务器处于受限内网，请开启代理以连接模型服务
          </span>
        </el-form-item>

        <transition name="el-fade-in">
          <div v-if="formData.proxyEnabled">
            <el-form-item label="代理主机" prop="proxyHost" :required="formData.proxyEnabled">
              <el-input
                v-model="formData.proxyHost"
                placeholder="例如 proxy.example.com"
              />
            </el-form-item>

            <el-form-item label="代理端口" prop="proxyPort" :required="formData.proxyEnabled">
              <el-input-number
                v-model="formData.proxyPort"
                :min="1"
                :max="65535"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>

            <el-form-item label="代理用户名" prop="proxyUsername">
              <el-input
                v-model="formData.proxyUsername"
                placeholder="可选，代理服务器需要认证时填写"
              />
            </el-form-item>

            <el-form-item label="代理密码" prop="proxyPassword">
              <el-input
                v-model="formData.proxyPassword"
                type="password"
                show-password
                placeholder="可选"
              />
            </el-form-item>
          </div>
        </transition>

        <template #footer>
          <span class="dialog-footer">
            <el-button @click="dialogVisible = false">取消</el-button>
            <el-button type="primary" @click="handleSubmit" :loading="submitting">
              {{ isEditMode ? '更新' : '创建' }}
            </el-button>
          </span>
        </template>
      </el-dialog>
    </div>
  </BaseLayout>
</template>

<script lang="ts">
  import { defineComponent, ref, computed, onMounted } from 'vue';
  import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
  import { Plus, Refresh, Cpu } from '@element-plus/icons-vue';
  import BaseLayout from '@/layouts/BaseLayout.vue';
  import modelConfigService, { type ModelConfig } from '@/services/modelConfig';
  import { getApiErrorMessage } from '@/services/common';

  export default defineComponent({
    name: 'ModelConfig',
    components: {
      BaseLayout,
      Cpu,
    },
    setup() {
      const loading = ref(true);
      const dialogVisible = ref(false);
      const isEditMode = ref(false);
      const submitting = ref(false);
      const activatingId = ref<number | null>(null);
      const testingId = ref<number | null>(null);
      const activeFilter = ref('');
      const configs = ref<ModelConfig[]>([]);
      const formRef = ref<FormInstance>();

      // 表单数据
      const formData = ref<ModelConfig>({
        provider: '',
        apiKey: '',
        baseUrl: '',
        modelName: '',
        modelType: 'CHAT',
        temperature: 0.0,
        maxTokens: 2000,
        completionsPath: '',
        embeddingsPath: '',
        rerankPath: '',
        requestTimeoutSeconds: 60,
        isActive: false,
        proxyEnabled: false,
        proxyHost: '',
        proxyPort: undefined,
        proxyUsername: '',
        proxyPassword: '',
      });

      // 提供商与API地址的映射
      const providerBaseUrlMap: Record<string, string> = {
        deepseek: 'https://api.deepseek.com',
        qwen: 'https://dashscope.aliyuncs.com/compatible-mode',
        openai: 'https://api.openai.com',
        siliconflow: 'https://api.siliconflow.cn',
        custom: '', // 自定义提供商不设置默认API地址
      };

      // 监听提供商变化，自动更新API地址
      const updateBaseUrlByProvider = (provider: string) => {
        if (provider && provider !== 'custom') {
          formData.value.baseUrl = providerBaseUrlMap[provider] || '';
        }
      };

      // 表单验证规则
      const formRules: FormRules = {
        provider: [{ required: true, message: '请选择提供商', trigger: 'change' }],
        modelType: [{ required: true, message: '请选择模型类型', trigger: 'change' }],
        modelName: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
        baseUrl: [{ required: true, message: '请输入 API 地址', trigger: 'blur' }],
        requestTimeoutSeconds: [
          { type: 'number', min: 1, max: 600, message: '请求超时必须在 1-600 秒之间', trigger: 'blur' },
        ],
        temperature: [
          { type: 'number', min: 0, max: 2, message: '温度值必须在0-2之间', trigger: 'blur' },
        ],
        maxTokens: [
          {
            type: 'number',
            min: 100,
            max: 10000,
            message: '最大Token必须在100-10000之间',
            trigger: 'blur',
          },
        ],
        proxyHost: [
          {
            validator: (_rule, value, callback) => {
              if (formData.value.proxyEnabled && (!value || value.trim() === '')) {
                callback(new Error('启用代理时，必须填写代理主机地址'));
              } else {
                callback();
              }
            },
            trigger: 'blur',
          },
        ],
        proxyPort: [
          {
            validator: (_rule, value, callback) => {
              if (formData.value.proxyEnabled && !value) {
                callback(new Error('启用代理时，必须填写代理端口'));
              } else {
                callback();
              }
            },
            trigger: 'blur',
          },
        ],
      };

      // 计算属性
      const dialogTitle = computed(() => {
        return isEditMode.value ? '编辑模型配置' : '新增模型配置';
      });

      const filteredConfigs = computed(() => {
        let filtered = configs.value;

        // 按模型类型过滤
        if (activeFilter.value) {
          filtered = filtered.filter(config => config.modelType === activeFilter.value);
        }

        return filtered;
      });

      // 方法
      const loadConfigs = async () => {
        loading.value = true;
        try {
          const response = await modelConfigService.list();
          configs.value = response || [];
        } catch (error) {
          ElMessage.error(getApiErrorMessage(error, '获取模型配置列表失败，请检查网络！'));
          configs.value = [];
        } finally {
          loading.value = false;
        }
      };

      const showAddDialog = () => {
        isEditMode.value = false;
        formData.value = {
          provider: '',
          apiKey: '',
          baseUrl: '',
          modelName: '',
          modelType: 'CHAT',
          temperature: 0.0,
          maxTokens: 2000,
          completionsPath: '',
          embeddingsPath: '',
          rerankPath: '',
          requestTimeoutSeconds: 60,
          isActive: false,
          proxyEnabled: false,
          proxyHost: '',
          proxyPort: undefined,
          proxyUsername: '',
          proxyPassword: '',
        };
        dialogVisible.value = true;
      };

      const handleEdit = (config: ModelConfig) => {
        isEditMode.value = true;
        formData.value = { ...config };
        dialogVisible.value = true;
      };

      const handleSubmit = async () => {
        if (!formRef.value) return;
        const valid = await formRef.value.validate().catch(() => false);
        if (!valid) return;

        submitting.value = true;
        try {
          if (isEditMode.value) {
            const result = await modelConfigService.update(formData.value);
            ElMessage.success(result.message || '配置更新成功');
          } else {
            const result = await modelConfigService.add(formData.value);
            ElMessage.success(result.message || '配置添加成功');
          }
          dialogVisible.value = false;
          void loadConfigs();
        } catch (error) {
          ElMessage.error(
            getApiErrorMessage(error, isEditMode.value ? '配置更新失败' : '配置添加失败'),
          );
        } finally {
          submitting.value = false;
        }
      };

      const handleDelete = async (config: ModelConfig) => {
        try {
          await ElMessageBox.confirm(
            `确定要删除配置 "${config.provider} - ${config.modelName}" 吗？此操作不可恢复。`,
            '删除确认',
            {
              confirmButtonText: '确定删除',
              cancelButtonText: '取消',
              type: 'warning',
            },
          );

          if (config.id) {
            const result = await modelConfigService.delete(config.id);
            ElMessage.success(result.message || '配置删除成功');
            void loadConfigs();
          }
        } catch (error) {
          if (error === 'cancel' || error === 'close') {
            return;
          }
          ElMessage.error(getApiErrorMessage(error, '配置删除失败'));
        }
      };

      const handleActivate = async (id?: number, modelType?: string) => {
        if (!id) return;

        try {
          // 如果是嵌入模型，显示确认提示
          if (modelType === 'EMBEDDING') {
            try {
              await ElMessageBox.confirm(
                '您正在更换嵌入模型，此操作风险较高！由于不同模型的向量空间不一致，切换后可能导致所有历史向量数据（含数据源、智能体知识、业务知识）将全部失效且无法检索。确定要执行吗？',
                '切换嵌入模型确认',
                {
                  confirmButtonText: '确定继续',
                  cancelButtonText: '取消',
                  type: 'warning',
                },
              );
            } catch {
              // 用户取消了操作
              console.log('用户取消了嵌入模型切换');
              return;
            }
          }

          activatingId.value = id;
          const result = await modelConfigService.activate(id);
          ElMessage.success(result.message || '模型启用成功');
          void loadConfigs();
        } catch (error) {
          ElMessage.error(getApiErrorMessage(error, '模型启用失败'));
        } finally {
          activatingId.value = null;
        }
      };

      const handleTestConnection = async (config: ModelConfig) => {
        if (!config.id) return;

        try {
          testingId.value = config.id;
          const result = await modelConfigService.testConnection(config);
          ElMessage.success(result.message || '当前配置已验证可用');
        } catch (error) {
          ElMessage.error(getApiErrorMessage(error, '可用性验证失败'));
        } finally {
          testingId.value = null;
          await loadConfigs();
        }
      };

      const validationTagType = (status?: ModelConfig['validationStatus']) => {
        if (status === 'PASSED') return 'success';
        if (status === 'FAILED') return 'danger';
        return 'warning';
      };
      const validationLabel = (status?: ModelConfig['validationStatus']) => {
        if (status === 'PASSED') return '已验证可用';
        if (status === 'FAILED') return '最近验证失败';
        return '待验证';
      };
      const validationTime = (value?: string) =>
        value
          ? `最近验证：${new Date(value.replace(' ', 'T')).toLocaleString('zh-CN')}`
          : '尚无真实验证记录';

      const providerLabel = (provider?: string, modelType?: ModelConfig['modelType']) => {
        const normalized = String(provider || '').trim().toLowerCase();
        if (normalized.includes('openai') && modelType === 'CHAT') return 'OpenAI · 对话模型';
        if (normalized.includes('embedding')) return '本地 · 向量模型';
        if (normalized.includes('rerank')) return '本地 · 重排模型';
        if (normalized === 'openai') return 'OpenAI';
        if (normalized === 'deepseek') return 'DeepSeek';
        if (normalized === 'qwen') return 'Qwen';
        if (normalized === 'siliconflow') return 'SiliconFlow';
        if (!provider) {
          return modelType === 'CHAT' ? '对话模型' : modelType === 'EMBEDDING' ? '向量模型' : '重排模型';
        }
        return /[\u3400-\u9fff]/.test(provider) ? provider : '自定义模型服务';
      };

      // 生命周期
      onMounted(() => {
        loadConfigs();
      });

      return {
        loading,
        dialogVisible,
        isEditMode,
        submitting,
        activatingId,
        testingId,
        activeFilter,
        configs,
        formData,
        formRef,
        formRules,
        filteredConfigs,
        dialogTitle,
        loadConfigs,
        showAddDialog,
        handleEdit,
        handleSubmit,
        handleDelete,
        handleActivate,
        handleTestConnection,
        validationTagType,
        validationLabel,
        validationTime,
        providerLabel,
        updateBaseUrlByProvider,
        Plus,
        Refresh,
      };
    },
  });
</script>

<style scoped>
  .model-config-page {
    min-height: 100vh;
    background: #f8fafc;
    font-family:
      -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  }

  /* 主内容区域 */
  .main-content {
    width: 100%;
    max-width: 1600px;
    margin: 0 auto;
    padding: 2rem;
  }

  /* 内容头部 */
  .content-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 2rem;
  }

  .header-info h1 {
    font-size: 2rem;
    font-weight: 600;
    color: #1f2937;
    margin: 0 0 0.5rem 0;
  }

  .header-info p {
    color: #6b7280;
    margin: 0;
    font-size: 1.1rem;
  }

  /* 操作区域 */
  .action-section {
    margin-bottom: 2rem;
  }

  .action-content {
    padding: 20px;
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .action-buttons {
    display: flex;
    gap: 1rem;
  }

  .filter-options {
    display: flex;
    gap: 1rem;
  }

  /* 配置表格 */
  .config-table {
    margin-bottom: 2rem;
  }

  .action-buttons-cell {
    display: flex;
    gap: 0.5rem;
  }

  /* 加载状态 */
  .loading-state {
    padding: 4rem 2rem;
  }

  /* 空状态 */
  .empty-state {
    padding: 4rem 2rem;
  }

  /* 表单提示 */
  .form-tip {
    font-size: 0.75rem;
    color: #6b7280;
    margin-top: 0.25rem;
  }

  /* 文本样式 */
  .text-muted {
    color: #9ca3af;
    font-style: italic;
  }

  /* 响应式设计 */
  @media (max-width: 768px) {
    .main-content {
      padding: 1rem;
    }

    .content-header {
      flex-direction: column;
      align-items: flex-start;
      gap: 1rem;
    }

    .header-stats {
      gap: 1rem;
    }

    .action-content {
      flex-direction: column;
      align-items: stretch;
      gap: 1rem;
    }

    .action-buttons {
      width: 100%;
    }

    .action-buttons .el-button {
      flex: 1;
    }

    .filter-options {
      width: 100%;
    }

    .filter-options .el-select {
      width: 100%;
    }
  }
</style>
