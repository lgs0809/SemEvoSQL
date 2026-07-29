<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <div class="base-layout">
    <header class="page-header">
      <div class="header-content">
        <button class="brand-logo" @click="router.push('/')">
          <i class="bi bi-diagram-3"></i>
          <span>SemEvoSQL</span>
        </button>
        <div class="header-actions">
          <nav class="header-nav" aria-label="主导航">
            <button
              v-for="item in visibleNavigation"
              :key="item.path"
              class="nav-item"
              :class="{ active: isActive(item.modules) }"
              @click="router.push(item.path)"
            >
              <i :class="item.icon"></i>
              <span>{{ item.label }}</span>
            </button>
          </nav>
        </div>
      </div>
    </header>
    <div v-if="degradedMessage" class="degraded-banner" role="status">
      <div>
        <strong>平台当前处于降级模式</strong>
        <span>{{ degradedMessage }}</span>
      </div>
      <el-button size="small" @click="router.push('/admin/models')">模型设置</el-button>
    </div>
    <main class="page-content"><slot /></main>
  </div>
</template>

<script setup>
  import { computed, onMounted, ref } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { platformContext } from '@/services/platformContext';

  const router = useRouter();
  const route = useRoute();
  const readiness = ref();
  const readinessError = ref('');
  const navigation = [
    { label: '项目', path: '/projects', icon: 'bi bi-folder2-open', modules: ['project'] },
    { label: '问数中心', path: '/chat', icon: 'bi bi-chat-square-text', modules: ['chat'] },
    { label: '数据连接', path: '/connections', icon: 'bi bi-database', modules: ['connections'] },
    { label: '管理', path: '/admin/models', icon: 'bi bi-gear', modules: ['admin'] },
  ];

  const visibleNavigation = computed(() => navigation);
  const isActive = modules => modules.includes(route.meta.module);
  const degradedMessage = computed(() => {
    if (readinessError.value)
      return '模型能力状态暂时无法读取；项目、历史结果和管理页面仍可继续访问。';
    if (!readiness.value || readiness.value.ready) return '';
    const missing = [];
    if (!readiness.value.chatModelReady) missing.push('Chat Model');
    if (!readiness.value.embeddingModelReady) missing.push('Embedding Model');
    if (!readiness.value.rerankModelReady) missing.push('Rerank Model');
    return `${missing.join('、')} 暂不可用；历史数据与控制面保持可访问，新问数和语义构建会被暂停。`;
  });

  onMounted(async () => {
    try {
      readiness.value = await platformContext.readiness(true);
    } catch (cause) {
      readinessError.value = cause instanceof Error ? cause.message : '平台能力状态不可用';
    }
  });
</script>

<style scoped>
  .base-layout {
    min-height: 100vh;
    background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%);
  }
  .page-header {
    position: sticky;
    top: 0;
    z-index: 100;
    border-bottom: 1px solid #e2e8f0;
    background: rgba(255, 255, 255, 0.96);
    box-shadow: 0 1px 3px rgb(15 23 42 / 8%);
    backdrop-filter: blur(12px);
  }
  .header-content {
    display: flex;
    align-items: center;
    justify-content: space-between;
    max-width: 1600px;
    height: 64px;
    margin: 0 auto;
    padding: 0 24px;
  }
  .brand-logo {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 0;
    border: 0;
    background: transparent;
    color: #0f172a;
    cursor: pointer;
    font-size: 20px;
    font-weight: 700;
  }
  .brand-logo i {
    color: #2563eb;
    font-size: 24px;
  }
  .header-actions {
    display: flex;
    align-items: center;
    gap: 14px;
  }
  .header-nav {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .nav-item {
    display: flex;
    align-items: center;
    gap: 7px;
    padding: 9px 14px;
    border: 0;
    border-radius: 9px;
    background: transparent;
    color: #64748b;
    cursor: pointer;
    font: inherit;
    font-weight: 550;
    transition: 0.18s ease;
  }
  .nav-item:hover {
    background: #f1f5f9;
    color: #334155;
  }
  .nav-item.active {
    background: #eff6ff;
    color: #1d4ed8;
  }
  .account-button {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 5px 8px;
    border: 1px solid #e2e8f0;
    border-radius: 10px;
    background: #fff;
    color: #334155;
    cursor: pointer;
  }
  .account-avatar {
    display: grid;
    width: 28px;
    height: 28px;
    place-items: center;
    border-radius: 50%;
    background: #e0e7ff;
    color: #3730a3;
    font-size: 12px;
    font-weight: 700;
  }
  .account-name {
    max-width: 140px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 12px;
  }
  .operator-error {
    max-width: 260px;
    color: #dc2626;
    font-size: 12px;
  }
  .degraded-banner {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 9px 24px;
    border-bottom: 1px solid #fde68a;
    background: #fffbeb;
    color: #92400e;
    font-size: 12px;
  }
  .degraded-banner > div {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .page-content {
    min-height: calc(100vh - 64px);
  }
  @media (max-width: 760px) {
    .header-content {
      height: auto;
      padding: 12px;
      align-items: flex-start;
      flex-direction: column;
      gap: 10px;
    }
    .header-actions {
      width: 100%;
      align-items: stretch;
      flex-direction: column;
    }
    .header-nav {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      width: 100%;
    }
    .nav-item {
      justify-content: center;
      padding: 8px 4px;
      font-size: 12px;
    }
  }
</style>
