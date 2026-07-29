<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <BaseLayout>
    <section class="settings-page" v-loading="loading">
      <div class="heading">
        <div>
          <h1>系统设置</h1>
          <p>只展示 SemEvoSQL 能从后端确认的运行事实；“已配置”与“已验证可用”严格区分。</p>
        </div>
        <el-button @click="load">刷新</el-button>
      </div>

      <el-alert
        v-if="error"
        type="warning"
        show-icon
        :closable="false"
        title="系统依赖状态加载失败"
        :description="error"
      />

      <div class="service-grid">
        <el-card shadow="never">
          <div class="service-header">
            <div>
              <h2>业务理解与问数模型</h2>
              <p>用于项目初始化、业务理解和查询执行。</p>
            </div>
            <el-tag :type="statusType(readiness.chatModelStatus)" effect="plain">
              {{ statusLabel(readiness.chatModelStatus) }}
            </el-tag>
          </div>
          <div class="fact-row">
            <span>配置</span>
            <strong>{{ readiness.chatModelConfigured ? '已有当前配置' : '未配置' }}</strong>
          </div>
          <div class="fact-row">
            <span>真实可用性验证</span>
            <strong>{{ verificationLabel(readiness.chatModelStatus) }}</strong>
          </div>
          <div class="fact-row">
            <span>最近验证</span>
            <strong>{{ formatTime(readiness.chatModelLastValidationTime) }}</strong>
          </div>
        </el-card>

        <el-card shadow="never">
          <div class="service-header">
            <div>
              <h2>语义检索模型</h2>
              <p>用于 Semantic Retrieval 与历史案例的向量召回。</p>
            </div>
            <el-tag :type="statusType(readiness.embeddingModelStatus)" effect="plain">
              {{ statusLabel(readiness.embeddingModelStatus) }}
            </el-tag>
          </div>
          <div class="fact-row">
            <span>配置</span>
            <strong>{{ readiness.embeddingModelConfigured ? '已有当前配置' : '未配置' }}</strong>
          </div>
          <div class="fact-row">
            <span>真实可用性验证</span>
            <strong>{{ verificationLabel(readiness.embeddingModelStatus) }}</strong>
          </div>
          <div class="fact-row">
            <span>最近验证</span>
            <strong>{{ formatTime(readiness.embeddingModelLastValidationTime) }}</strong>
          </div>
        </el-card>

        <el-card shadow="never">
          <div class="service-header">
            <div>
              <h2>语义重排模型</h2>
              <p>对 RRF 融合后的候选进行深度相关性重排，是标准语义检索链路的一部分。</p>
            </div>
            <el-tag :type="statusType(readiness.rerankModelStatus)" effect="plain">
              {{ statusLabel(readiness.rerankModelStatus) }}
            </el-tag>
          </div>
          <div class="fact-row">
            <span>配置</span>
            <strong>{{ readiness.rerankModelConfigured ? '已有当前配置' : '未配置' }}</strong>
          </div>
          <div class="fact-row">
            <span>真实可用性验证</span>
            <strong>{{ verificationLabel(readiness.rerankModelStatus) }}</strong>
          </div>
          <div class="fact-row">
            <span>最近验证</span>
            <strong>{{ formatTime(readiness.rerankModelLastValidationTime) }}</strong>
          </div>
        </el-card>
      </div>

      <div class="actions">
        <el-button type="primary" @click="router.push('/admin/models')">管理与验证模型</el-button>
      </div>

      <details class="advanced">
        <summary>高级状态口径</summary>
        <p>
          “已有当前配置”只表示数据库中存在已启用配置；“已验证可用”必须由模型管理页发起一次真实模型调用并成功。
          任何模型配置修改都会使旧验证结果失效，重新变为“待验证”。
        </p>
      </details>
    </section>
  </BaseLayout>
</template>

<script setup lang="ts">
  import { onMounted, reactive, ref } from 'vue';
  import { useRouter } from 'vue-router';
  import BaseLayout from '@/layouts/BaseLayout.vue';
  import modelConfigService, { type ModelReadinessStatus } from '@/services/modelConfig';

  const router = useRouter();
  const loading = ref(false);
  const error = ref('');
  const readiness = reactive({
    chatModelConfigured: false,
    chatModelReady: false,
    chatModelStatus: 'NOT_CONFIGURED' as ModelReadinessStatus,
    chatModelLastValidationTime: undefined as string | undefined,
    embeddingModelConfigured: false,
    embeddingModelReady: false,
    embeddingModelStatus: 'NOT_CONFIGURED' as ModelReadinessStatus,
    embeddingModelLastValidationTime: undefined as string | undefined,
    rerankModelConfigured: false,
    rerankModelReady: false,
    rerankModelStatus: 'NOT_CONFIGURED' as ModelReadinessStatus,
    rerankModelLastValidationTime: undefined as string | undefined,
    ready: false,
  });

  const load = async () => {
    loading.value = true;
    error.value = '';
    try {
      Object.assign(readiness, await modelConfigService.checkReady());
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '系统依赖状态加载失败';
    } finally {
      loading.value = false;
    }
  };

  const statusLabel = (status: ModelReadinessStatus) =>
    ({
      NOT_CONFIGURED: '未配置',
      CONFIGURED: '待验证',
      VERIFIED: '当前可用',
      STALE: '验证已过期',
      UNAVAILABLE: '当前不可用',
    })[status];

  const verificationLabel = (status: ModelReadinessStatus) =>
    ({
      NOT_CONFIGURED: '尚未配置',
      CONFIGURED: '尚未完成真实调用验证',
      VERIFIED: '最近验证仍在有效期内',
      STALE: '历史测试通过，但需要重新验证',
      UNAVAILABLE: '最近一次真实调用失败',
    })[status];

  const statusType = (status: ModelReadinessStatus) => {
    if (status === 'VERIFIED') return 'success';
    if (status === 'UNAVAILABLE') return 'danger';
    return 'warning';
  };
  const formatTime = (value?: string) =>
    value ? new Date(value.replace(' ', 'T')).toLocaleString('zh-CN') : '尚无验证记录';

  onMounted(load);
</script>

<style scoped>
  .settings-page {
    max-width: 1180px;
    margin: 0 auto;
    padding: 30px;
  }
  .heading,
  .service-header,
  .fact-row {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 18px;
  }
  .heading h1 {
    margin: 0 0 6px;
    color: #0f172a;
    font-size: 30px;
  }
  .heading p,
  .service-header p,
  .advanced p {
    margin: 0;
    color: #64748b;
    line-height: 1.65;
  }
  .service-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 18px;
    margin: 24px 0;
  }
  .service-header {
    margin-bottom: 18px;
  }
  .service-header h2 {
    margin: 0 0 6px;
    color: #0f172a;
    font-size: 18px;
  }
  .fact-row {
    align-items: center;
    padding: 10px 0;
    border-top: 1px solid #eef2f7;
  }
  .fact-row span {
    color: #64748b;
  }
  .fact-row strong {
    color: #0f172a;
    text-align: right;
  }
  .actions {
    display: flex;
    justify-content: flex-end;
  }
  .advanced {
    margin-top: 22px;
    padding: 16px 18px;
    border: 1px solid #e2e8f0;
    border-radius: 12px;
    background: #f8fafc;
  }
  .advanced summary {
    cursor: pointer;
    color: #334155;
    font-weight: 650;
  }
  .advanced p {
    margin-top: 12px;
  }
  @media (max-width: 760px) {
    .settings-page {
      padding: 18px 10px;
    }
    .heading {
      flex-direction: column;
    }
    .service-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
