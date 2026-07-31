<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <el-drawer
    :model-value="modelValue"
    title="运行详情"
    size="460px"
    @close="emit('update:modelValue', false)"
  >
    <div v-loading="loading" class="drawer-body">
      <el-descriptions v-if="run" :column="1" border>
        <el-descriptions-item label="运行编号">{{ run.runId }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusLabel(run.status) }}</el-descriptions-item>
        <el-descriptions-item label="当前阶段">{{ stageLabel(run.currentNode, run.status) }}</el-descriptions-item>
        <el-descriptions-item label="业务模型版本">
          {{ run.projectVersionId || '-' }}
        </el-descriptions-item>
        <el-descriptions-item v-if="run.errorMessage" label="处理提示">
          {{ run.errorMessage }}
          <span v-if="run.retryable === true">（可以重试）</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="transportHint"
        class="transport-alert"
        type="info"
        :closable="false"
        show-icon
        :title="transportHint"
      />

      <el-card v-if="run" shadow="never" class="persistence-card">
        <strong>执行记录已持久化</strong>
        <p>
          系统已保存 {{ events.length }} 条执行进度。关闭页面或网络中断不会取消后台任务，重新打开后会继续同步。
        </p>
      </el-card>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
  import type { QueryRun, RunEvent } from '@/services/semevosql';

  defineProps<{
    modelValue: boolean;
    loading?: boolean;
    run?: QueryRun;
    events: RunEvent[];
    transportHint?: string;
  }>();
  const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();

  const statusLabel = (status?: string) => {
    const labels: Record<string, string> = {
      QUEUED: '排队中',
      RUNNING: '执行中',
      WAITING_HUMAN: '等待确认',
      SUCCEEDED: '已完成',
      FAILED: '未完成',
      CANCELLED: '已取消',
      EXPIRED: '已过期',
    };
    return status ? labels[status] || '处理中' : '-';
  };

  const stageLabel = (node?: string, status?: string) => {
    if (status === 'WAITING_HUMAN') return '等待业务确认';
    if (status === 'SUCCEEDED') return '结果已生成';
    if (status === 'FAILED' || status === 'CANCELLED' || status === 'EXPIRED') return '已结束';
    const normalized = (node || '').toLowerCase();
    if (normalized.includes('retriev') || normalized.includes('schema')) return '理解业务语义';
    if (normalized.includes('semantic') || normalized.includes('plan')) return '规划查询';
    if (normalized.includes('sql') || normalized.includes('execut')) return '执行查询';
    if (normalized.includes('review') || normalized.includes('result')) return '校验结果';
    if (normalized.includes('report')) return '整理结果';
    return status === 'QUEUED' ? '等待执行' : '处理中';
  };
</script>

<style scoped>
  .drawer-body {
    min-height: 220px;
  }
  .transport-alert,
  .persistence-card {
    margin-top: 16px;
  }
  .persistence-card p {
    margin: 8px 0 0;
    color: #64748b;
    line-height: 1.65;
  }
</style>
