<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <BaseLayout>
    <div class="chat-shell">
      <aside class="conversation-sidebar">
        <div class="sidebar-title">
          <strong>项目问数</strong>
          <el-button
            circle
            :icon="Plus"
            :disabled="!canCreateConversation"
            @click="createConversation"
          />
        </div>
        <el-select
          v-model="selectedProjectId"
          filterable
          placeholder="选择项目"
          @change="loadProject()"
        >
          <el-option v-for="item in projects" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <div class="conversation-list" v-loading="conversationLoading">
          <button
            v-for="item in conversations"
            :key="item.conversationId"
            class="conversation"
            :class="{ active: item.conversationId === activeConversationId }"
            @click="selectConversation(item.conversationId)"
          >
            <strong>{{ item.title }}</strong>
            <span>{{ formatTime(item.updateTime) }}</span>
          </button>
          <el-empty v-if="!conversations.length" :image-size="72" description="暂无会话" />
        </div>
      </aside>

      <main class="chat-main">
        <div class="mobile-context-bar">
          <el-select
            v-model="selectedProjectId"
            filterable
            placeholder="选择项目"
            @change="loadProject()"
          >
            <el-option
              v-for="item in projects"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
          <el-select
            v-model="activeConversationId"
            placeholder="选择会话"
            :disabled="!conversations.length"
            @change="handleMobileConversationChange"
          >
            <el-option
              v-for="item in conversations"
              :key="item.conversationId"
              :label="item.title"
              :value="item.conversationId"
            />
          </el-select>
          <el-button
            circle
            :icon="Plus"
            :disabled="!canCreateConversation"
            @click="createConversation"
          />
        </div>

        <header class="chat-header">
          <div>
            <h1>{{ selectedProject?.project.name || '选择一个项目开始问数' }}</h1>
            <span v-if="activeConversation">
              此会话使用业务模型 v{{ conversationVersion?.versionNumber || '-' }}
            </span>
            <span v-else-if="activeVersion">当前业务模型 v{{ activeVersion.versionNumber }}</span>
            <span v-else>项目尚未发布可问数的业务模型</span>
          </div>
          <div class="run-actions">
            <el-tag
              v-if="selectedProjectHealth"
              :type="selectedProjectHealth.queryReady ? 'success' : 'warning'"
              effect="plain"
            >
              {{ selectedProjectHealth.queryReady ? '项目可问数' : '项目准备中' }}
            </el-tag>
            <el-button v-if="activeRun && canResume" @click="resumeRun">继续查询</el-button>
            <el-button v-if="activeRun" link type="primary" @click="openDiagnosis(activeRun.runId)">
              查询诊断
            </el-button>
            <el-button
              v-if="activeRun && canViewTechnicalDetails"
              link
              @click="openRunDetails(activeRun.runId)"
            >
              运行详情
            </el-button>
            <el-button v-if="activeRun && !terminalRun" type="danger" plain @click="cancelRun">
              取消查询
            </el-button>
          </div>
        </header>

        <el-alert
          v-if="initializationError"
          class="page-error-alert"
          type="error"
          show-icon
          :closable="false"
          title="问数页面初始化失败"
        >
          <template #default>
            <div class="inline-recovery">
              <span>{{ initializationError }}</span>
              <el-button size="small" type="primary" plain @click="initializePage">
                重新加载
              </el-button>
            </div>
          </template>
        </el-alert>

        <el-alert
          v-else-if="projectContextError"
          class="page-error-alert"
          type="warning"
          show-icon
          :closable="false"
          title="当前项目上下文加载不完整"
        >
          <template #default>
            <div class="inline-recovery">
              <span>{{ projectContextError }}</span>
              <el-button size="small" @click="loadProject()">重新加载项目</el-button>
            </div>
          </template>
        </el-alert>

        <el-alert
          v-else-if="selectedProject && selectedProjectHealthError"
          class="page-error-alert"
          type="info"
          show-icon
          :closable="false"
          title="项目健康状态暂时不可用"
        >
          <template #default>
            <div class="inline-recovery">
              <span>{{ selectedProjectHealthError }}。已有正式版本和历史会话仍可继续使用。</span>
              <el-button size="small" @click="reloadSelectedProjectHealth">重试状态</el-button>
            </div>
          </template>
        </el-alert>

        <el-alert
          v-if="!queryCapabilityReady && (platformReadiness || platformReadinessError)"
          class="page-error-alert"
          type="warning"
          show-icon
          :closable="false"
          title="新问数暂时不可用"
        >
          <template #default>
            <span>
              {{
                platformReadinessError
                  ? '模型能力状态暂时无法确认。历史会话和已有结果仍可查看。'
                  : 'Chat Model、Embedding Model 或 Rerank Model 当前不可用。历史会话和已有结果仍可查看。'
              }}
            </span>
          </template>
        </el-alert>

        <el-alert
          v-if="conversationUsesOlderVersion && !versionNoticeDismissed"
          class="version-notice"
          type="warning"
          show-icon
          :closable="false"
        >
          <template #title>
            这个会话使用业务模型 v{{ conversationVersion?.versionNumber }}。项目当前已经升级到 v{{
              activeVersion?.versionNumber
            }}。
          </template>
          <div class="version-actions">
            <el-button size="small" @click="versionNoticeDismissed = true">
              继续查看旧会话
            </el-button>
            <el-button size="small" type="primary" @click="createConversation">
              基于新版本开始新会话
            </el-button>
          </div>
        </el-alert>

        <QueryRunProgress
          v-if="activeRun"
          :run="activeRun"
          :events="runEvents"
          :needs-action="Boolean(clarification || humanReviewRequired)"
          :transport-notice="showTransportNotice ? transportNotice : ''"
        />

        <section ref="messageArea" class="message-area" v-loading="messageLoading">
          <el-alert
            v-if="conversationError"
            class="conversation-error-alert"
            type="warning"
            show-icon
            :closable="false"
            title="当前会话加载失败"
          >
            <template #default>
              <div class="inline-recovery">
                <span>{{ conversationError }}</span>
                <el-button
                  v-if="activeConversationId"
                  size="small"
                  @click="selectConversation(activeConversationId)"
                >
                  重新加载会话
                </el-button>
              </div>
            </template>
          </el-alert>

          <ChatWelcome
            v-if="!activeConversation"
            :has-project="Boolean(selectedProject)"
            :project-name="selectedProject?.project.name || '当前项目'"
            :project-status="selectedProject?.project.status"
            :version-number="activeVersion?.versionNumber"
            :query-ready="selectedProjectHealth?.queryReady"
            :has-conversation="conversations.length > 0"
            :suggested-questions="welcomeExamples"
            :next-action="selectedProjectHealth?.nextActions?.[0]"
            @create-project="router.push('/projects/create')"
            @create-conversation="createConversation"
            @manage-project="openProjectPreparation"
            @use-example="useWelcomeExample"
          />

          <template v-else>
            <el-card v-if="clarification" shadow="never" class="clarification-card">
              <template #header>
                <div class="event-heading">
                  <strong>请确认你的业务含义</strong>
                  <span>确认后会从当前查询继续</span>
                </div>
              </template>
              <h3>{{ clarification.question }}</h3>
              <p v-if="clarification.reason" class="clarification-reason">
                {{ clarification.reason }}
              </p>
              <el-radio-group v-model="selectedClarificationOption" class="clarification-options">
                <el-radio
                  v-for="option in clarification.options"
                  :key="option.code"
                  :value="option.code"
                  border
                >
                  <span class="clarification-option-content">
                    <strong>{{ option.label }}</strong>
                    <small v-if="option.reason">{{ option.reason }}</small>
                  </span>
                </el-radio>
              </el-radio-group>
              <el-input
                v-model="clarificationCustomAnswer"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 5 }"
                placeholder="候选都不符合时可补充你的实际含义"
              />
              <div v-if="clarificationAllowsDurableScope" class="clarification-scope">
                <span>这次选择：</span>
                <el-radio-group v-model="selectedClarificationScope" size="small">
                  <el-radio-button value="QUERY">仅本次</el-radio-button>
                  <el-radio-button value="USER">记住我的习惯</el-radio-button>
                  <el-radio-button v-if="canSubmitProjectRule" value="PROJECT">
                    作为项目统一定义
                  </el-radio-button>
                </el-radio-group>
              </div>
              <div class="clarification-actions">
                <el-button
                  type="primary"
                  :loading="answeringClarification"
                  :disabled="!selectedClarificationOption"
                  @click="answerClarification"
                >
                  提交澄清
                </el-button>
              </div>
            </el-card>

            <el-card v-else-if="humanReviewRequired" shadow="never" class="human-review-card">
              <template #header>
                <div class="event-heading">
                  <strong>需要确认查询理解</strong>
                  <span>确认后按当前理解串行执行查询任务</span>
                </div>
              </template>
              <p class="human-review-summary">{{ humanReviewSummary }}</p>
              <el-input
                v-model="humanReviewFeedback"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 6 }"
                placeholder="如果理解不对，直接说明需要调整的业务口径；批准时可留空"
              />
              <div class="human-review-actions">
                <el-button
                  type="danger"
                  plain
                  :loading="submittingHumanReview"
                  :disabled="!humanReviewFeedback.trim()"
                  @click="submitHumanReview(false)"
                >
                  修改并重新理解
                </el-button>
                <el-button
                  type="primary"
                  :loading="submittingHumanReview"
                  @click="submitHumanReview(true)"
                >
                  批准执行
                </el-button>
              </div>
            </el-card>

            <template v-for="item in messages" :key="item.messageId">
              <article v-if="item.role === 'USER'" class="message user">
                <div class="message-meta">
                  <strong>你</strong>
                  <span>{{ formatTime(item.createTime) }}</span>
                </div>
                <div class="message-content">{{ item.content }}</div>
              </article>

              <AnswerCard
                v-else-if="item.role === 'ASSISTANT'"
                :content="item.content"
                :create-time="item.createTime"
                :status="item.status"
                :run-id="item.runId"
                :explanation="messageExplanation(item)"
                :artifact-id="messageArtifactId(item)"
                :artifact="artifactViews[item.messageId]?.artifact"
                :artifact-columns="artifactViews[item.messageId]?.columns || []"
                :artifact-rows="artifactViews[item.messageId]?.rows || []"
                :artifact-loading="artifactViews[item.messageId]?.loading"
                :artifact-error="artifactViews[item.messageId]?.error"
                :show-feedback-actions="
                  Boolean(
                    item.runId &&
                      canSubmitPersonalFeedback &&
                      item.status === 'SUCCEEDED' &&
                      !feedbackSubmittedRunIds.has(item.runId) &&
                      (!correctionMode || correctionRun?.runId !== item.runId),
                  )
                "
                :feedback-loading="submittingAnswerFeedback"
                @trust="item.runId && submitAnswerFeedback(item.runId, true)"
                @correct="item.runId && startCorrection(item.runId)"
                @diagnosis="item.runId && openDiagnosis(item.runId)"
                @run-details="item.runId && openRunDetails(item.runId)"
              >
                <template #feedback>
                  <div
                    v-if="item.runId === correctionRun?.runId && correctionMode"
                    class="answer-correction-panel"
                  >
                    <div class="correction-heading">
                      <div>
                        <strong>修正这条答案的理解</strong>
                        <span>系统会先阻止错误轨迹继续学习，再按你确认的范围处理修正。</span>
                      </div>
                      <el-button link @click="cancelCorrection">取消</el-button>
                    </div>
                    <div class="correction-kind-grid">
                      <button
                        v-for="option in correctionQuickOptions"
                        :key="option.value"
                        type="button"
                        class="correction-kind"
                        :class="{ active: correctionCategory === option.value }"
                        @click="selectCorrectionCategory(option.value)"
                      >
                        <strong>{{ option.label }}</strong>
                        <small>{{ option.description }}</small>
                      </button>
                    </div>
                    <el-collapse v-if="canSubmitProjectRule" class="correction-advanced">
                      <el-collapse-item title="高级纠错与治理">
                        <el-select
                          v-model="correctionCategory"
                          placeholder="选择高级问题类型"
                          @change="loadCorrectionOptions"
                        >
                          <el-option label="指标映射" value="METRIC" />
                          <el-option label="维度映射" value="DIMENSION" />
                          <el-option label="枚举映射" value="ENUM_VALUE" />
                          <el-option label="时间语义" value="TIME" />
                          <el-option label="过滤条件" value="FILTER" />
                          <el-option label="关联关系" value="RELATIONSHIP" />
                          <el-option label="业务定义" value="DEFINITION" />
                          <el-option label="规划策略" value="PLANNING" />
                          <el-option label="数据质量" value="DATA_QUALITY" />
                          <el-option label="其他" value="OTHER" />
                        </el-select>
                      </el-collapse-item>
                    </el-collapse>
                    <template v-if="bindingCorrectionCategory">
                      <el-input
                        v-model="correctionRawExpression"
                        placeholder="原问题里的说法，例如：流水、客户、支付状态"
                      />
                      <el-select
                        v-model="correctionAssetKey"
                        filterable
                        :loading="correctionOptionsLoading"
                        placeholder="你实际想表达的是"
                      >
                        <el-option
                          v-for="option in correctionOptions"
                          :key="option.assetKey"
                          :label="option.businessLabel"
                          :value="option.assetKey"
                        />
                      </el-select>
                      <el-radio-group
                        v-model="correctionScope"
                        size="small"
                        class="correction-scope"
                      >
                        <el-radio-button value="QUERY">仅修正这次</el-radio-button>
                        <el-radio-button value="USER">以后按我的习惯理解</el-radio-button>
                        <el-radio-button v-if="canSubmitProjectRule" value="PROJECT">
                          作为项目统一定义
                        </el-radio-button>
                      </el-radio-group>
                    </template>
                    <el-input
                      v-model="answerFeedbackComment"
                      type="textarea"
                      :autosize="{ minRows: 2, maxRows: 5 }"
                      :placeholder="
                        bindingCorrectionCategory
                          ? '可选：补充为什么原理解不对'
                          : correctionCategory === 'PLANNING'
                            ? '说明这次规划哪里不合理，以及什么情况下应该怎样规划'
                            : '请直接说明哪里不对、你期望怎样理解或结果应该是什么'
                      "
                    />
                    <div class="answer-feedback-actions">
                      <el-button
                        type="primary"
                        :loading="submittingAnswerFeedback"
                        :disabled="
                          !correctionCategory ||
                          (bindingCorrectionCategory &&
                            (!correctionRawExpression.trim() || !correctionAssetKey)) ||
                          (!bindingCorrectionCategory && !answerFeedbackComment.trim())
                        "
                        @click="submitCorrection"
                      >
                        {{ bindingCorrectionCategory ? '修正并重新查询' : '提交纠正' }}
                      </el-button>
                    </div>
                  </div>
                  <el-alert
                    v-else-if="item.runId && feedbackSubmittedRunIds.has(item.runId)"
                    class="answer-feedback-saved"
                    type="success"
                    show-icon
                    :closable="false"
                    title="这条答案的反馈已保存。明确确认与纠错会作为不同质量信号记录。"
                  />
                </template>

                <template #learning>
                  <div
                    v-for="prompt in messageUpgradePrompts(item)"
                    :key="prompt.preferenceId"
                    class="preference-upgrade"
                  >
                    <span>
                      你已经多次使用“{{ prompt.phrase || prompt.displayPhrase }}”表示“{{
                        prompt.businessLabel
                      }}”。{{
                        canSubmitProjectRule
                          ? '可以升级为项目统一定义；通过语义验证、回归与发布后才会对项目共享。'
                          : '系统会继续把它作为你的个人选择使用。'
                      }}
                    </span>
                    <div>
                      <el-button
                        v-if="canSubmitProjectRule"
                        size="small"
                        type="primary"
                        :loading="preferenceActionId === prompt.preferenceId"
                        @click="handlePreferenceUpgrade(prompt.preferenceId, 'PROMOTE')"
                      >
                        升级为项目统一定义
                      </el-button>
                      <el-button
                        size="small"
                        :loading="preferenceActionId === prompt.preferenceId"
                        @click="handlePreferenceUpgrade(prompt.preferenceId, 'CONTINUE')"
                      >
                        继续作为我的习惯
                      </el-button>
                      <el-button
                        size="small"
                        text
                        :loading="preferenceActionId === prompt.preferenceId"
                        @click="handlePreferenceUpgrade(prompt.preferenceId, 'DISMISS')"
                      >
                        不再提醒
                      </el-button>
                    </div>
                  </div>
                </template>
              </AnswerCard>

              <article v-else class="message system">
                <div class="message-meta">
                  <strong>系统</strong>
                  <span>{{ formatTime(item.createTime) }}</span>
                </div>
                <div class="message-content">{{ item.content }}</div>
              </article>
            </template>
          </template>
        </section>

        <footer class="composer">
          <el-input
            v-model="message"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 6 }"
            placeholder="例如：对比本月订单金额和财务确认收入"
            :disabled="
              !queryCapabilityReady ||
              !conversationVersion ||
              !activeConversation ||
              sending ||
              runBusy
            "
            @keydown.ctrl.enter.prevent="send"
            @keydown.meta.enter.prevent="send"
          />
          <div class="composer-footer">
            <div class="composer-options">
              <span>
                {{
                  conversationVersion
                    ? `使用业务模型 v${conversationVersion.versionNumber}；Ctrl/⌘ + Enter 发送`
                    : '这个会话没有可用的业务模型'
                }}
              </span>
              <el-radio-group
                v-model="approvalMode"
                size="small"
                :disabled="sending || runBusy"
                aria-label="查询执行方式"
              >
                <el-radio-button value="REQUIRE_APPROVAL">请求批准</el-radio-button>
                <el-radio-button value="AUTO_EXECUTE">自动执行</el-radio-button>
              </el-radio-group>
              <small>业务语义不明确时，两种模式都会先请求澄清。</small>
            </div>
            <el-button
              type="primary"
              :loading="sending"
              :disabled="
                !queryCapabilityReady ||
                !conversationVersion ||
                !activeConversation ||
                !message.trim() ||
                runBusy
              "
              :icon="Promotion"
              @click="send"
            >
              发送
            </el-button>
          </div>
        </footer>
      </main>
    </div>

    <RunDetailsDrawer
      v-model="runDetailsVisible"
      :loading="runDetailsLoading"
      :run="detailsRun"
      :events="detailsEvents"
      :transport-hint="detailsTransportHint"
    />
    <QueryDiagnosisWorkbench
      v-model="diagnosisVisible"
      :run-id="diagnosisRunId"
      @rerun="handleDiagnosisRerun"
      @open-evolution="openDiagnosisEvolution"
    />
  </BaseLayout>
</template>

<script setup lang="ts">
  import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { ElMessage } from 'element-plus';
  import { Plus, Promotion } from '@element-plus/icons-vue';
  import BaseLayout from '@/layouts/BaseLayout.vue';
  import { platformContext, type PlatformReadiness } from '@/services/platformContext';
  import AnswerCard from '@/components/chat/AnswerCard.vue';
  import ChatWelcome from '@/components/chat/ChatWelcome.vue';
  import QueryRunProgress from '@/components/chat/QueryRunProgress.vue';
  import RunDetailsDrawer from '@/components/chat/RunDetailsDrawer.vue';
  import { useDurableRunTransport } from '@/composables/useDurableRunTransport';
  import {
    canRestoreCursor,
    mergeSequencedEvents,
    nextReplayCursor,
    parseRunCursor,
    serializeRunCursor,
  } from '@/services/runRecoveryState.mjs';
  import {
    semEvoSQLService,
    type ProjectConversation,
    type ProjectHealth,
    type ProjectInitializationView,
    type ProjectMessage,
    type QueryApprovalMode,
    type QueryCorrectionOption,
    type QueryExecutionExplanation,
    type QueryRun,
    type ResultArtifact,
    type RunEvent,
    type RuntimeClarification,
    type SemanticBindingScope,
    type SemanticPreferenceUpgradePrompt,
    type SemanticProject,
    type SemanticProjectVersion,
  } from '@/services/semevosql';

  const QueryDiagnosisWorkbench = defineAsyncComponent(
    () => import('@/components/chat/QueryDiagnosisWorkbench.vue'),
  );

  interface PersistedRunCursor {
    projectId: number;
    conversationId: string;
    runId: string;
    lastSequence: number;
  }

  interface InlineArtifactView {
    artifact?: ResultArtifact;
    columns: string[];
    rows: Array<Record<string, unknown>>;
    loading: boolean;
    error?: string;
  }

  const RUN_CURSOR_STORAGE_KEY = 'semevosql:project-chat:active-run';
  const TERMINAL_STATUSES = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'];

  const route = useRoute();
  const router = useRouter();
  const projects = ref<SemanticProject[]>([]);
  const versions = ref<SemanticProjectVersion[]>([]);
  const selectedProject = ref<ProjectInitializationView>();
  const selectedProjectId = ref<number>();
  const selectedProjectHealth = ref<ProjectHealth>();
  const initializationError = ref('');
  const platformReadiness = ref<PlatformReadiness>();
  const platformReadinessError = ref('');
  const projectContextError = ref('');
  const selectedProjectHealthError = ref('');
  const conversationError = ref('');
  const welcomeExamples = ref<string[]>([]);
  const conversations = ref<ProjectConversation[]>([]);
  const activeConversationId = ref<string>();
  const activeConversation = computed(() =>
    conversations.value.find(item => item.conversationId === activeConversationId.value),
  );
  const messages = ref<ProjectMessage[]>([]);
  const message = ref('');
  const approvalMode = ref<QueryApprovalMode>('REQUIRE_APPROVAL');
  const sending = ref(false);
  const conversationLoading = ref(false);
  const messageLoading = ref(false);
  const activeRun = ref<QueryRun>();
  const clarification = ref<RuntimeClarification>();
  const clarificationLoading = ref(false);
  const selectedClarificationOption = ref('');
  const selectedClarificationScope = ref<SemanticBindingScope>('QUERY');
  const clarificationCustomAnswer = ref('');
  const answeringClarification = ref(false);
  const currentOperatorId = ref('local-operator');
  const preferenceActionId = ref<number>();
  const hiddenUpgradePromptIds = ref<Set<number>>(new Set());
  const humanReviewFeedback = ref('');
  const submittingHumanReview = ref(false);
  const answerFeedbackComment = ref('');
  const submittingAnswerFeedback = ref(false);
  const feedbackSubmittedRunIds = ref<Set<string>>(new Set());
  const correctionRun = ref<QueryRun>();
  const correctionMode = ref(false);
  const correctionCategory = ref('');
  const correctionRawExpression = ref('');
  const correctionAssetKey = ref('');
  const correctionOptions = ref<QueryCorrectionOption[]>([]);
  const correctionOptionsLoading = ref(false);
  const correctionScope = ref<SemanticBindingScope>('QUERY');
  const correctionQuickOptions = [
    {
      value: 'METRIC',
      label: '业务指标理解错了',
      description: '例如金额、收入、订单数的业务口径不是你想表达的',
    },
    {
      value: 'DIMENSION',
      label: '分组对象理解错了',
      description: '例如客户、渠道、区域、商品等分组维度不对',
    },
    {
      value: 'ENUM_VALUE',
      label: '状态或类型理解错了',
      description: '例如“成功”“退款”“新客”等业务取值映射不对',
    },
    {
      value: 'TIME',
      label: '时间口径理解错了',
      description: '例如应该按支付时间而不是下单时间统计',
    },
    {
      value: 'DATA_QUALITY',
      label: '底层数据有问题',
      description: '查询理解没错，但源数据缺失、延迟或异常',
    },
    {
      value: 'OTHER',
      label: '筛选或其他问题',
      description: '直接描述哪里不对，系统会记录为错误信号',
    },
  ];
  const artifactViews = ref<Record<string, InlineArtifactView>>({});
  const runDetailsVisible = ref(false);
  const runDetailsLoading = ref(false);
  const detailsRun = ref<QueryRun>();
  const detailsEvents = ref<RunEvent[]>([]);
  const diagnosisVisible = ref(false);
  const diagnosisRunId = ref<string>();
  const versionNoticeDismissed = ref(false);
  const runEvents = ref<RunEvent[]>([]);
  const lastSequence = ref(0);
  const messageArea = ref<HTMLElement>();

  const activeVersion = computed(() =>
    versions.value.find(
      item => item.id === selectedProject.value?.project.activePublishedVersionId,
    ),
  );
  const queryCapabilityReady = computed(
    () => platformReadiness.value?.ready === true && !platformReadinessError.value,
  );
  const canCreateConversation = computed(
    () =>
      queryCapabilityReady.value &&
      Boolean(activeVersion.value) &&
      selectedProjectHealth.value?.queryReady !== false,
  );
  const conversationVersion = computed(() =>
    versions.value.find(item => item.id === activeConversation.value?.projectVersionId),
  );
  const conversationUsesOlderVersion = computed(
    () =>
      Boolean(activeConversation.value && activeVersion.value && conversationVersion.value) &&
      activeVersion.value?.id !== conversationVersion.value?.id,
  );
  const terminalRun = computed(() => TERMINAL_STATUSES.includes(activeRun.value?.status || ''));
  const runBusy = computed(() => Boolean(activeRun.value && !terminalRun.value));
  const bindingCorrectionCategory = computed(() =>
    ['METRIC', 'DIMENSION', 'ENUM_VALUE', 'TIME'].includes(correctionCategory.value),
  );
  const canResume = computed(() => ['QUEUED', 'FAILED'].includes(activeRun.value?.status || ''));
  const latestHumanReviewRequiredEvent = computed(() =>
    [...runEvents.value]
      .reverse()
      .find(event =>
        ['QUERY_UNDERSTANDING_READY', 'HUMAN_FEEDBACK_REQUIRED'].includes(event.eventType),
      ),
  );
  const latestHumanReviewAnsweredEvent = computed(() =>
    [...runEvents.value]
      .reverse()
      .find(event =>
        [
          'REQUEST_APPROVED',
          'REQUEST_APPROVAL_REJECTED',
          'HUMAN_FEEDBACK_ANSWERED',
          'HUMAN_FEEDBACK_APPLIED',
        ].includes(event.eventType),
      ),
  );
  const clarificationAllowsDurableScope = computed(() =>
    ['METRIC', 'DIMENSION', 'ENUM_VALUE'].includes(clarification.value?.assetType || ''),
  );
  const canSubmitPersonalFeedback = computed(() => true);
  const canSubmitProjectRule = computed(() => true);
  const canViewTechnicalDetails = computed(() => true);
  const humanReviewRequired = computed(() => {
    if (
      activeRun.value?.status !== 'WAITING_HUMAN' ||
      clarification.value ||
      clarificationLoading.value
    )
      return false;
    const required = latestHumanReviewRequiredEvent.value;
    if (!required) return false;
    return (
      !latestHumanReviewAnsweredEvent.value ||
      latestHumanReviewAnsweredEvent.value.sequence < required.sequence
    );
  });
  const humanReviewSummary = computed(() => {
    const event = latestHumanReviewRequiredEvent.value;
    if (event?.eventType === 'QUERY_UNDERSTANDING_READY' && event.payload) return event.payload;
    return (
      event?.payloadSummary ||
      '查询理解已生成。请确认是否执行；如果口径不对，直接用自然语言说明需要修改的内容。'
    );
  });
  const runTransport = useDurableRunTransport({
    isTerminal: () => terminalRun.value,
    lastSequence: () => lastSequence.value,
    catchUp: runId => catchUpRun(runId),
    onEvent: event => {
      mergeRunEvents([event]);
      void refreshRun(event.runId, true);
    },
    subscribe: (runId, afterSequence, onEvent, onError, onOpen) =>
      semEvoSQLService.subscribeRun(runId, afterSequence, onEvent, onError, onOpen),
  });
  const transportNotice = runTransport.notice;
  const showTransportNotice = computed(() =>
    Boolean(activeRun.value && !terminalRun.value && transportNotice.value),
  );
  const detailsTransportHint = computed(() => {
    if (!detailsRun.value || detailsRun.value.runId !== activeRun.value?.runId) return '';
    return transportNotice.value;
  });

  const readPersistedRunCursor = (): PersistedRunCursor | undefined => {
    const raw = localStorage.getItem(RUN_CURSOR_STORAGE_KEY);
    const cursor = parseRunCursor(raw) as PersistedRunCursor | undefined;
    if (raw && !cursor) localStorage.removeItem(RUN_CURSOR_STORAGE_KEY);
    return cursor;
  };

  const persistRunCursor = () => {
    if (
      !selectedProjectId.value ||
      !activeConversationId.value ||
      !runTransport.followedRunId.value
    )
      return;
    const cursor: PersistedRunCursor = {
      projectId: selectedProjectId.value,
      conversationId: activeConversationId.value,
      runId: runTransport.followedRunId.value,
      lastSequence: lastSequence.value,
    };
    const serialized = serializeRunCursor(cursor);
    if (serialized) localStorage.setItem(RUN_CURSOR_STORAGE_KEY, serialized);
  };

  const clearPersistedRunCursor = (runId?: string) => {
    const cursor = readPersistedRunCursor();
    if (!runId || cursor?.runId === runId) localStorage.removeItem(RUN_CURSOR_STORAGE_KEY);
  };

  const clearClarification = () => {
    clarification.value = undefined;
    selectedClarificationOption.value = '';
    selectedClarificationScope.value = 'QUERY';
    clarificationCustomAnswer.value = '';
  };

  const loadClarification = async (runId: string) => {
    if (activeRun.value?.status !== 'WAITING_HUMAN') {
      clearClarification();
      return;
    }
    clarificationLoading.value = true;
    try {
      clarification.value = await semEvoSQLService.clarification(runId);
      selectedClarificationOption.value = clarification.value.recommendedOption || '';
      selectedClarificationScope.value = clarification.value.selectedScope || 'QUERY';
      clarificationCustomAnswer.value = '';
    } catch {
      clearClarification();
    } finally {
      clarificationLoading.value = false;
    }
  };

  const mergeRunEvents = (events: RunEvent[]) => {
    if (!events.length) return;
    const merged = mergeSequencedEvents(runEvents.value, events) as {
      events: RunEvent[];
      lastSequence: number;
    };
    runEvents.value = merged.events;
    lastSequence.value = merged.lastSequence || lastSequence.value;
    persistRunCursor();
  };

  const replayMissingEvents = async (runId: string, afterSequence: number) => {
    let cursor = Math.max(0, afterSequence);
    for (let page = 0; page < 20; page += 1) {
      const batch = await semEvoSQLService.runEvents(runId, cursor, 500);
      mergeRunEvents(batch);
      if (batch.length < 500) return;
      const nextCursor = nextReplayCursor(batch, cursor);
      if (nextCursor <= cursor) return;
      cursor = nextCursor;
    }
  };

  const syncMessage = async (runId: string, quiet = false) => {
    if (!selectedProjectId.value || !activeConversationId.value) return;
    try {
      await semEvoSQLService.syncProjectMessage(
        selectedProjectId.value,
        activeConversationId.value,
        runId,
      );
      const view = await semEvoSQLService.projectConversation(
        selectedProjectId.value,
        activeConversationId.value,
      );
      messages.value = view.messages;
      void loadMessageArtifacts(view.messages);
      await scrollToBottom();
    } catch (error) {
      if (!quiet) ElMessage.error(error instanceof Error ? error.message : '消息状态同步失败');
    }
  };

  const refreshRun = async (runId: string, quiet = false) => {
    if (!runTransport.isFollowing(runId)) return;
    try {
      activeRun.value = await semEvoSQLService.run(runId);
      await loadClarification(runId);
      if (terminalRun.value) {
        runTransport.stop(false);
        clearPersistedRunCursor(runId);
        await syncMessage(runId, true);
      }
    } catch (error) {
      if (!quiet) throw error;
    }
  };

  const catchUpRun = async (runId: string) => {
    if (!runTransport.isFollowing(runId)) return;
    await replayMissingEvents(runId, lastSequence.value);
    await refreshRun(runId, true);
  };

  const loadWelcomeExamples = async () => {
    welcomeExamples.value = [];
    if (!selectedProjectId.value || !activeVersion.value) return;
    try {
      const catalog = await semEvoSQLService.semanticCatalog(
        selectedProjectId.value,
        activeVersion.value.id,
      );
      const metrics = catalog.metrics
        .filter(item => item.status !== 'DISABLED')
        .map(item => String(item.businessName || item.metricCode || '').trim())
        .filter(Boolean);
      const dimensions = catalog.dimensions
        .filter(item => item.status !== 'DISABLED')
        .map(item => String(item.businessName || item.dimensionCode || '').trim())
        .filter(Boolean);
      const metric = metrics[0];
      if (!metric) return;
      const suggestions = [`上个月${metric}是多少？`];
      if (dimensions[0]) suggestions.push(`按${dimensions[0]}看，上个月${metric}分别是多少？`);
      suggestions.push(`本周${metric}和上周相比有什么变化？`);
      if (metrics[1]) suggestions.push(`上个月${metrics[1]}表现如何？`);
      welcomeExamples.value = suggestions.slice(0, 3);
    } catch {
      welcomeExamples.value = [];
    }
  };

  const reloadSelectedProjectHealth = async () => {
    selectedProjectHealthError.value = '';
    if (!selectedProjectId.value) {
      selectedProjectHealth.value = undefined;
      return;
    }
    try {
      selectedProjectHealth.value = await semEvoSQLService.projectHealth(selectedProjectId.value);
    } catch (error) {
      selectedProjectHealth.value = undefined;
      selectedProjectHealthError.value =
        error instanceof Error ? error.message : '项目健康状态读取失败';
    }
  };

  const loadProject = async (recovery?: PersistedRunCursor) => {
    runTransport.stop(true);
    activeConversationId.value = undefined;
    messages.value = [];
    artifactViews.value = {};
    runEvents.value = [];
    lastSequence.value = 0;
    activeRun.value = undefined;
    selectedProjectHealth.value = undefined;
    projectContextError.value = '';
    selectedProjectHealthError.value = '';
    conversationError.value = '';
    clearClarification();
    humanReviewFeedback.value = '';
    if (!selectedProjectId.value) return;
    conversationLoading.value = true;
    try {
      const healthPromise = reloadSelectedProjectHealth();
      [selectedProject.value, versions.value, conversations.value] = await Promise.all([
        semEvoSQLService.project(selectedProjectId.value),
        semEvoSQLService.projectVersions(selectedProjectId.value),
        semEvoSQLService.projectConversations(selectedProjectId.value),
      ]);
      await healthPromise;
      await loadWelcomeExamples();
      const restoredConversation = canRestoreCursor(
        recovery,
        selectedProjectId.value,
        conversations.value.map(item => item.conversationId),
      )
        ? recovery?.conversationId
        : undefined;
      const conversationId = restoredConversation || conversations.value[0]?.conversationId;
      if (conversationId) {
        await selectConversation(
          conversationId,
          restoredConversation ? recovery?.runId : undefined,
          restoredConversation ? recovery?.lastSequence : 0,
        );
      }
    } catch (error) {
      projectContextError.value = error instanceof Error ? error.message : '项目问数上下文加载失败';
    } finally {
      conversationLoading.value = false;
    }
  };

  const createConversation = async () => {
    if (!queryCapabilityReady.value) {
      ElMessage.warning('模型能力当前不可用，暂时不能创建新的问数会话');
      return;
    }
    if (!selectedProjectId.value || !canCreateConversation.value) return;
    try {
      const created = await semEvoSQLService.createProjectConversation(
        selectedProjectId.value,
        '新对话',
      );
      conversations.value = [created, ...conversations.value];
      await selectConversation(created.conversationId);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '会话创建失败');
    }
  };

  const selectConversation = async (
    conversationId: string,
    preferredRunId?: string,
    restoredSequence = 0,
  ) => {
    if (!selectedProjectId.value) return;
    runTransport.stop(true);
    clearClarification();
    humanReviewFeedback.value = '';
    versionNoticeDismissed.value = false;
    conversationError.value = '';
    activeConversationId.value = conversationId;
    messages.value = [];
    artifactViews.value = {};
    runEvents.value = [];
    activeRun.value = undefined;
    messageLoading.value = true;
    try {
      const view = await semEvoSQLService.projectConversation(
        selectedProjectId.value,
        conversationId,
      );
      const index = conversations.value.findIndex(item => item.conversationId === conversationId);
      if (index >= 0) conversations.value[index] = view.conversation;
      messages.value = view.messages;
      void loadMessageArtifacts(view.messages);
      const messageRunIds = new Set(
        view.messages.flatMap(item => (item.runId ? [item.runId] : [])),
      );
      const latestRunId = [...view.messages].reverse().find(item => item.runId)?.runId;
      const runId =
        preferredRunId && messageRunIds.has(preferredRunId) ? preferredRunId : latestRunId;
      if (runId) await followRun(runId, restoredSequence);
      else clearPersistedRunCursor();
      await scrollToBottom();
    } catch (error) {
      conversationError.value = error instanceof Error ? error.message : '会话加载失败';
    } finally {
      messageLoading.value = false;
    }
  };

  const send = async () => {
    if (!queryCapabilityReady.value) {
      ElMessage.warning('模型能力当前不可用，历史会话仍可查看，但暂时不能发起新查询');
      return;
    }
    if (!selectedProjectId.value || !activeConversationId.value || !message.value.trim()) return;
    const content = message.value.trim();
    sending.value = true;
    try {
      await semEvoSQLService.sendProjectMessage(
        selectedProjectId.value,
        activeConversationId.value,
        content,
        approvalMode.value,
      );
      message.value = '';
      await selectConversation(activeConversationId.value);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '消息发送失败');
    } finally {
      sending.value = false;
    }
  };

  const followRun = async (runId: string, restoredSequence = 0) => {
    const runChanged = runTransport.begin(runId);
    if (runChanged) resetCorrection();
    runEvents.value = [];
    lastSequence.value = Math.max(0, restoredSequence);
    try {
      activeRun.value = await semEvoSQLService.run(runId);
      await replayMissingEvents(runId, 0);
      await loadClarification(runId);
      persistRunCursor();
      if (terminalRun.value) {
        clearPersistedRunCursor(runId);
        await syncMessage(runId, true);
      } else if (navigator.onLine) {
        runTransport.connect(runId);
      } else {
        runTransport.handleOffline();
      }
      await scrollToBottom();
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '查询状态加载失败');
    }
  };

  const messageMetadata = (item: ProjectMessage): Record<string, unknown> => {
    if (!item.metadataJson) return {};
    try {
      return JSON.parse(item.metadataJson) as Record<string, unknown>;
    } catch {
      return {};
    }
  };

  const messageExplanation = (item: ProjectMessage): QueryExecutionExplanation | undefined =>
    messageMetadata(item).executionExplanation as QueryExecutionExplanation | undefined;

  const messageUpgradePrompts = (item: ProjectMessage): SemanticPreferenceUpgradePrompt[] => {
    const prompts = (messageMetadata(item).semanticPreferenceUpgradePrompts ||
      []) as SemanticPreferenceUpgradePrompt[];
    return prompts.filter(prompt => !hiddenUpgradePromptIds.value.has(prompt.preferenceId));
  };

  const handlePreferenceUpgrade = async (
    preferenceId: number,
    action: 'PROMOTE' | 'CONTINUE' | 'DISMISS',
  ) => {
    preferenceActionId.value = preferenceId;
    try {
      if (action === 'PROMOTE') await semEvoSQLService.promoteSemanticPreference(preferenceId);
      else if (action === 'CONTINUE')
        await semEvoSQLService.continueSemanticPreference(preferenceId);
      else await semEvoSQLService.dismissSemanticPreferenceUpgrade(preferenceId);
      hiddenUpgradePromptIds.value = new Set([...hiddenUpgradePromptIds.value, preferenceId]);
      ElMessage.success(
        action === 'PROMOTE'
          ? '已申请升级为项目统一定义；当前个人习惯继续有效，通过语义验证、回归与发布后才会项目共享'
          : action === 'CONTINUE'
            ? '已继续作为你的个人习惯使用，后续稳定使用一段时间后再提醒'
            : '已关闭这条建议的提醒',
      );
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '语义习惯处理失败');
    } finally {
      preferenceActionId.value = undefined;
    }
  };

  const messageArtifactId = (item: ProjectMessage) => {
    if (!item.metadataJson) return undefined;
    try {
      const metadata = JSON.parse(item.metadataJson) as { artifactId?: string };
      return metadata.artifactId;
    } catch {
      return undefined;
    }
  };

  const loadMessageArtifact = async (item: ProjectMessage) => {
    const artifactId = messageArtifactId(item);
    if (!artifactId || !item.runId) return;
    const existing = artifactViews.value[item.messageId];
    if (existing?.loading || existing?.artifact) return;
    artifactViews.value[item.messageId] = { columns: [], rows: [], loading: true };
    try {
      const result = await semEvoSQLService.resultArtifact(item.runId, artifactId);
      artifactViews.value[item.messageId] = {
        artifact: result,
        columns: JSON.parse(result.schemaJson) as string[],
        rows: JSON.parse(result.dataJson) as Array<Record<string, unknown>>,
        loading: false,
      };
    } catch (error) {
      artifactViews.value[item.messageId] = {
        columns: [],
        rows: [],
        loading: false,
        error: error instanceof Error ? error.message : '结果数据加载失败',
      };
    }
  };

  const loadMessageArtifacts = async (items: ProjectMessage[]) => {
    await Promise.all(items.filter(item => item.role === 'ASSISTANT').map(loadMessageArtifact));
  };

  const openRunDetails = async (runId: string) => {
    runDetailsVisible.value = true;
    runDetailsLoading.value = true;
    detailsRun.value = undefined;
    detailsEvents.value = [];
    try {
      const [run, events] = await Promise.all([
        semEvoSQLService.run(runId),
        semEvoSQLService.runEvents(runId, 0, 500),
      ]);
      detailsRun.value = run;
      detailsEvents.value = events;
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '运行详情加载失败');
    } finally {
      runDetailsLoading.value = false;
    }
  };

  const openDiagnosis = (runId: string) => {
    diagnosisRunId.value = runId;
    diagnosisVisible.value = true;
  };

  const handleDiagnosisRerun = async (runId: string) => {
    diagnosisVisible.value = false;
    if (activeConversationId.value) {
      await selectConversation(activeConversationId.value, runId);
      return;
    }
    await followRun(runId);
  };

  const openDiagnosisEvolution = (candidateId: string) => {
    diagnosisVisible.value = false;
    if (!selectedProjectId.value) return;
    void router.push({
      path: `/projects/${selectedProjectId.value}`,
      query: { section: 'evolution', candidateId },
    });
  };

  const openProjectPreparation = () => {
    if (!selectedProjectId.value) return;
    const section = selectedProjectHealth.value?.nextActions?.[0]?.target || 'overview';
    void router.push({ path: `/projects/${selectedProjectId.value}`, query: { section } });
  };

  const handleMobileConversationChange = (conversationId?: string) => {
    if (conversationId) void selectConversation(conversationId);
  };

  const useWelcomeExample = async (example: string) => {
    if (!activeConversation.value) await createConversation();
    if (!activeConversation.value) return;
    message.value = example;
  };

  const answerClarification = async () => {
    if (!activeRun.value || !clarification.value || !selectedClarificationOption.value) return;
    const runId = activeRun.value.runId;
    const selectedOption = selectedClarificationOption.value;
    const selectedScope = clarificationAllowsDurableScope.value
      ? selectedClarificationScope.value
      : 'QUERY';
    answeringClarification.value = true;
    try {
      await semEvoSQLService.answerClarification(
        runId,
        clarification.value,
        selectedOption,
        clarificationCustomAnswer.value,
        selectedScope,
        crypto.randomUUID(),
      );
      clearClarification();
      activeRun.value =
        selectedOption === 'CANCEL'
          ? await semEvoSQLService.run(runId)
          : await semEvoSQLService.resumeRun(runId);
      await followRun(runId);
      await syncMessage(runId);
      const scopeText =
        selectedScope === 'USER'
          ? '已记住你的选择，查询将继续。'
          : selectedScope === 'PROJECT'
            ? '已设为项目默认含义，查询将继续。'
            : '已按本次选择继续查询。';
      ElMessage.success(scopeText);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '业务含义确认失败');
    } finally {
      answeringClarification.value = false;
    }
  };

  const submitHumanReview = async (approved: boolean) => {
    if (!selectedProjectId.value || !activeConversationId.value || !activeRun.value) return;
    const feedback = humanReviewFeedback.value.trim();
    if (!approved && !feedback) {
      ElMessage.warning('驳回执行计划时必须填写调整意见');
      return;
    }
    const runId = activeRun.value.runId;
    submittingHumanReview.value = true;
    try {
      activeRun.value = await semEvoSQLService.submitProjectHumanReview(
        selectedProjectId.value,
        activeConversationId.value,
        runId,
        approved,
        feedback,
        crypto.randomUUID(),
      );
      humanReviewFeedback.value = '';
      await followRun(runId);
      await syncMessage(runId);
      ElMessage.success(
        approved ? '查询计划已确认，正在继续查询数据' : '已提交调整意见，正在重新规划查询',
      );
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '查询计划确认失败');
    } finally {
      submittingHumanReview.value = false;
    }
  };

  const markFeedbackSubmitted = (runId: string) => {
    feedbackSubmittedRunIds.value = new Set([...feedbackSubmittedRunIds.value, runId]);
  };

  const resetCorrection = () => {
    correctionMode.value = false;
    correctionRun.value = undefined;
    correctionCategory.value = '';
    correctionRawExpression.value = '';
    correctionAssetKey.value = '';
    correctionOptions.value = [];
    correctionScope.value = 'QUERY';
    answerFeedbackComment.value = '';
  };

  const startCorrection = async (runId: string) => {
    try {
      const run =
        activeRun.value?.runId === runId ? activeRun.value : await semEvoSQLService.run(runId);
      if (run.status !== 'SUCCEEDED' || !run.episodeId) {
        ElMessage.warning('这条答案当前不能提交纠错');
        return;
      }
      resetCorrection();
      correctionRun.value = run;
      correctionMode.value = true;
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '答案详情加载失败');
    }
  };

  const cancelCorrection = () => resetCorrection();

  const correctionExplanation = () => {
    const runId = correctionRun.value?.runId;
    if (!runId) return undefined;
    const message = [...messages.value]
      .reverse()
      .find(item => item.role === 'ASSISTANT' && item.runId === runId);
    return message ? messageExplanation(message) : undefined;
  };

  const selectCorrectionCategory = async (value: string) => {
    correctionCategory.value = value;
    await loadCorrectionOptions();
  };

  const loadCorrectionOptions = async () => {
    correctionAssetKey.value = '';
    correctionOptions.value = [];
    if (!correctionRun.value || !bindingCorrectionCategory.value) return;
    const assetType =
      correctionCategory.value === 'TIME'
        ? 'TIME_COLUMN'
        : (correctionCategory.value as 'METRIC' | 'DIMENSION' | 'ENUM_VALUE');
    const explanation = correctionExplanation();
    const currentBinding = explanation?.semanticBindings.find(
      binding => String(binding.assetType || '') === assetType,
    );
    if (currentBinding) {
      correctionRawExpression.value = String(
        currentBinding.displayPhrase || currentBinding.normalizedPhrase || '',
      );
    } else if (assetType === 'METRIC') {
      const metric = explanation?.businessDefinitions.find(
        definition => String(definition.type || '') === 'METRIC',
      );
      correctionRawExpression.value = String(metric?.name || '');
    }
    correctionOptionsLoading.value = true;
    try {
      const result = await semEvoSQLService.correctionOptions(
        correctionRun.value.runId,
        assetType,
      );
      correctionOptions.value = result.options;
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '纠错候选加载失败');
    } finally {
      correctionOptionsLoading.value = false;
    }
  };

  const submitCorrection = async () => {
    if (!selectedProjectId.value || !activeConversationId.value || !correctionRun.value) return;
    const runId = correctionRun.value.runId;
    const episodeId = correctionRun.value.episodeId;
    if (!episodeId || !correctionCategory.value) return;
    submittingAnswerFeedback.value = true;
    try {
      const detail = answerFeedbackComment.value.trim();
      await semEvoSQLService.submitEpisodeFeedback(
        episodeId,
        currentOperatorId.value,
        1,
        false,
        `CORRECTION[${correctionCategory.value}] ${detail || '用户确认当前查询理解有误'}`,
      );
      markFeedbackSubmitted(runId);
      if (bindingCorrectionCategory.value) {
        const option = correctionOptions.value.find(
          item => item.assetKey === correctionAssetKey.value,
        );
        if (!option || !correctionRawExpression.value.trim()) {
          throw new Error('请选择正确的业务含义并填写原问题中的业务说法');
        }
        const rawExpression = correctionRawExpression.value.trim();
        const selectedScope = correctionScope.value;
        const result = await semEvoSQLService.correctBinding(
          selectedProjectId.value,
          activeConversationId.value,
          runId,
          {
            rawExpression,
            assetType: option.assetType,
            assetKey: option.assetKey,
            businessLabel: option.businessLabel,
            scope: selectedScope,
            idempotencyKey: crypto.randomUUID(),
          },
        );
        await selectConversation(activeConversationId.value, result.rerunId);
        ElMessage.success(
          selectedScope === 'USER'
            ? `已记住你把“${rawExpression}”理解为“${option.businessLabel}”。后续你的查询会优先使用这个含义。`
            : selectedScope === 'PROJECT'
              ? `已按“${option.businessLabel}”重新查询，并申请作为项目统一定义；通过语义验证、回归与发布前不会影响其他用户。`
              : `已按“${option.businessLabel}”重新查询。这次修正只对本次查询生效。`,
        );
      } else {
        if (
          ['DEFINITION', 'TIME', 'FILTER', 'RELATIONSHIP', 'PLANNING'].includes(
            correctionCategory.value,
          )
        ) {
          await semEvoSQLService.proposeDefinitionCorrection(
            selectedProjectId.value,
            activeConversationId.value,
            runId,
            correctionCategory.value as
              | 'DEFINITION'
              | 'TIME'
              | 'FILTER'
              | 'RELATIONSHIP'
              | 'PLANNING',
            detail,
          );
        }
        ElMessage.success(
          correctionCategory.value === 'DATA_QUALITY'
            ? '已记录为数据质量问题，这条错误结果不会继续参与学习。'
            : correctionCategory.value === 'PLANNING'
              ? '已提交规划策略改进建议；必须经过回归验证、审核与发布后才会影响后续查询规划。'
              : ['DEFINITION', 'TIME', 'FILTER', 'RELATIONSHIP'].includes(correctionCategory.value)
                ? '已提交业务模型改进建议；验证与回归测试通过前不会修改正式口径。'
                : '已记录这次纠正，这条错误结果不会继续参与学习。',
        );
        resetCorrection();
      }
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '纠错提交失败');
    } finally {
      submittingAnswerFeedback.value = false;
    }
  };

  const submitAnswerFeedback = async (runId: string, adopted: boolean) => {
    const comment = answerFeedbackComment.value.trim();
    if (!adopted && !comment) {
      ElMessage.warning('标记结果不正确时必须说明具体问题');
      return;
    }
    submittingAnswerFeedback.value = true;
    try {
      const run =
        activeRun.value?.runId === runId ? activeRun.value : await semEvoSQLService.run(runId);
      if (run.status !== 'SUCCEEDED' || !run.episodeId) {
        throw new Error('这条答案当前不能提交反馈');
      }
      await semEvoSQLService.submitEpisodeFeedback(
        run.episodeId,
        currentOperatorId.value,
        adopted ? 5 : 1,
        adopted,
        comment,
      );
      markFeedbackSubmitted(runId);
      answerFeedbackComment.value = '';
      ElMessage.success(
        adopted ? '已明确确认这条结果正确，并记录为可信答案信号。' : '问题反馈已保存',
      );
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '结果反馈提交失败');
    } finally {
      submittingAnswerFeedback.value = false;
    }
  };

  const resumeRun = async () => {
    if (!activeRun.value) return;
    const runId = activeRun.value.runId;
    try {
      activeRun.value = await semEvoSQLService.resumeRun(runId);
      await followRun(runId);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '查询恢复失败');
    }
  };

  const cancelRun = async () => {
    if (!activeRun.value) return;
    const runId = activeRun.value.runId;
    try {
      activeRun.value = await semEvoSQLService.cancelRun(runId);
      clearClarification();
      humanReviewFeedback.value = '';
      await followRun(runId);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '取消查询失败');
    }
  };

  const handleOnline = () => runTransport.handleOnline();

  const handleOffline = () => runTransport.handleOffline();

  const scrollToBottom = async () => {
    await nextTick();
    if (messageArea.value) messageArea.value.scrollTop = messageArea.value.scrollHeight;
  };
  const formatTime = (value?: string) => (value ? new Date(value).toLocaleString('zh-CN') : '-');

  const initializePage = async () => {
    initializationError.value = '';
    platformReadinessError.value = '';
    const readinessPromise = platformContext
      .readiness(true)
      .then(value => {
        platformReadiness.value = value;
      })
      .catch(error => {
        platformReadiness.value = undefined;
        platformReadinessError.value =
          error instanceof Error ? error.message : '平台模型能力状态读取失败';
      });
    try {
      projects.value = await semEvoSQLService.listProjects();
      const persisted = readPersistedRunCursor();
      const queryProjectId = Number(route.query.projectId);
      selectedProjectId.value =
        Number.isFinite(queryProjectId) && queryProjectId > 0
          ? queryProjectId
          : persisted?.projectId || projects.value[0]?.id;
      await loadProject(persisted?.projectId === selectedProjectId.value ? persisted : undefined);
      await readinessPromise;
    } catch (error) {
      initializationError.value = error instanceof Error ? error.message : '问数页面初始化失败';
      await readinessPromise;
    }
  };

  onMounted(() => {
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    void initializePage();
  });
  onBeforeUnmount(() => {
    window.removeEventListener('online', handleOnline);
    window.removeEventListener('offline', handleOffline);
    runTransport.stop(true);
  });
</script>

<style scoped>
  .chat-shell {
    display: grid;
    grid-template-columns: clamp(280px, 20vw, 320px) minmax(0, 1fr);
    width: 100%;
    max-width: 1600px;
    height: calc(100vh - 64px);
    margin: 0 auto;
    background: #f8fafc;
  }
  .conversation-sidebar {
    overflow: hidden;
    padding: 20px;
    border-right: 1px solid #e2e8f0;
    background: #fff;
  }
  .sidebar-title {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 18px;
    font-size: 20px;
  }
  .conversation-list {
    overflow-y: auto;
    max-height: calc(100vh - 170px);
    margin-top: 18px;
  }
  .mobile-context-bar {
    display: none;
  }
  .conversation {
    display: flex;
    flex-direction: column;
    gap: 5px;
    width: 100%;
    margin-bottom: 6px;
    padding: 12px;
    border: 0;
    border-radius: 10px;
    background: transparent;
    color: #334155;
    text-align: left;
    cursor: pointer;
  }
  .conversation span {
    color: #94a3b8;
    font-size: 11px;
  }
  .conversation.active {
    background: #eff6ff;
    color: #1d4ed8;
  }
  .chat-main {
    display: flex;
    overflow: hidden;
    flex-direction: column;
    min-width: 0;
  }
  .chat-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 20px;
    padding: 18px 28px;
    border-bottom: 1px solid #e2e8f0;
    background: #fff;
  }
  .chat-header h1 {
    margin: 0 0 6px;
    color: #0f172a;
    font-size: 18px;
  }
  .chat-header span {
    color: #64748b;
    font-size: 13px;
  }
  .run-actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .message-area {
    flex: 1;
    overflow-y: auto;
    width: 100%;
    padding: 32px max(32px, calc((100% - 900px) / 2));
  }
  .page-error-alert,
  .version-notice {
    margin: 12px 28px 0;
  }
  .conversation-error-alert {
    max-width: 760px;
    margin: 0 auto 18px;
  }
  .inline-recovery {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .version-actions {
    display: flex;
    gap: 8px;
    margin-top: 10px;
  }
  .clarification-card,
  .human-review-card {
    max-width: 760px;
    margin: 0 auto 20px;
  }
  .clarification-card {
    border-color: #facc15;
  }
  .human-review-card {
    border-color: #fb923c;
  }
  .clarification-card h3 {
    margin: 0 0 8px;
    color: #0f172a;
  }
  .clarification-reason,
  .human-review-summary {
    margin: 0 0 14px;
    color: #64748b;
    line-height: 1.7;
  }
  .clarification-options {
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
    margin-bottom: 14px;
  }
  .clarification-options :deep(.el-radio) {
    height: auto;
    min-height: 40px;
    margin-right: 0;
    padding: 8px 12px;
  }
  .clarification-option-content {
    display: grid;
    gap: 2px;
    white-space: normal;
  }
  .clarification-option-content small {
    color: #64748b;
    line-height: 1.4;
  }
  .clarification-scope,
  .correction-scope {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 10px;
    margin-top: 12px;
    color: #475569;
    font-size: 13px;
  }
  .clarification-actions,
  .human-review-actions,
  .answer-feedback-actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 12px;
  }
  .answer-correction-panel {
    display: grid;
    gap: 10px;
    padding: 16px 18px;
    border-top: 1px solid #fecaca;
    background: #fff7f7;
  }
  .answer-correction-panel :deep(.el-select),
  .answer-correction-panel :deep(.el-input) {
    width: 100%;
  }
  .correction-heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
  }
  .correction-heading > div {
    display: grid;
    gap: 4px;
  }
  .correction-heading span {
    color: #64748b;
    font-size: 12px;
  }
  .correction-kind-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }
  .correction-kind {
    display: grid;
    gap: 4px;
    padding: 10px 12px;
    border: 1px solid #e2e8f0;
    border-radius: 10px;
    background: #fff;
    color: #334155;
    cursor: pointer;
    text-align: left;
  }
  .correction-kind:hover,
  .correction-kind.active {
    border-color: #93c5fd;
    background: #eff6ff;
  }
  .correction-kind small {
    color: #64748b;
    line-height: 1.4;
  }
  .correction-advanced {
    border-top: 1px dashed #cbd5e1;
  }
  .answer-feedback-saved {
    margin: 0 18px 14px;
  }
  .preference-upgrade {
    margin-top: 12px;
    padding: 12px;
    border: 1px solid #bfdbfe;
    border-radius: 10px;
    background: #eff6ff;
    color: #334155;
  }
  .preference-upgrade > div {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 8px;
  }
  .message {
    max-width: 760px;
    margin: 0 auto 18px;
    padding: 16px 18px;
    border: 1px solid #e2e8f0;
    border-radius: 14px;
    background: #fff;
  }
  .message.user {
    border-color: #bfdbfe;
    background: #eff6ff;
  }
  .message-meta,
  .event-heading {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    color: #64748b;
    font-size: 12px;
  }
  .message-content {
    margin-top: 10px;
    color: #1e293b;
    white-space: pre-wrap;
    line-height: 1.7;
  }
  .composer {
    padding: 18px max(28px, calc((100% - 900px) / 2));
    border-top: 1px solid #e2e8f0;
    background: #fff;
  }
  .composer-footer {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 16px;
    margin-top: 10px;
    color: #94a3b8;
    font-size: 12px;
  }
  .composer-options {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px 12px;
    min-width: 0;
  }
  .composer-options small {
    color: #64748b;
  }
  @media (max-width: 850px) {
    .chat-shell {
      grid-template-columns: 1fr;
    }
    .conversation-sidebar {
      display: none;
    }
    .mobile-context-bar {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
      gap: 8px;
      padding: 10px 14px;
      border-bottom: 1px solid #e2e8f0;
      background: #fff;
    }
    .chat-header {
      align-items: flex-start;
      flex-direction: column;
    }
    .run-actions {
      flex-wrap: wrap;
    }
    .message-area,
    .composer {
      padding-right: 16px;
      padding-left: 16px;
    }
    .page-error-alert,
    .version-notice {
      margin-right: 14px;
      margin-left: 14px;
    }
    .inline-recovery {
      align-items: stretch;
      flex-direction: column;
    }
  }
</style>
