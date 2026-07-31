<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <div class="base-layout" :class="{ 'focus-layout': focus }">
    <aside class="app-sidebar">
      <div class="sidebar-brand">
        <button class="brand-logo" aria-label="返回项目工作台" @click="router.push('/projects')">
          <span class="brand-mark"><i class="bi bi-diagram-3"></i></span>
          <span class="brand-copy">
            <strong>SemEvoSQL</strong>
            <small>语义数据工作台</small>
          </span>
        </button>
      </div>

      <div class="workspace-card">
        <span class="workspace-kicker">本地工作区</span>
        <strong>本地单机环境</strong>
        <span><i class="bi bi-shield-check"></i> 自托管 · 单用户</span>
      </div>

      <nav class="side-nav" aria-label="主导航">
        <span class="nav-section-label">工作区</span>
        <button
          v-for="item in visibleNavigation.slice(0, 2)"
          :key="item.path"
          class="nav-item"
          :class="{ active: isActive(item.modules) }"
          @click="router.push(item.path)"
        >
          <span class="nav-icon"><i :class="item.icon"></i></span>
          <span class="nav-copy">
            <strong>{{ item.label }}</strong>
            <small>{{ item.description }}</small>
          </span>
          <i v-if="isActive(item.modules)" class="bi bi-arrow-right-short nav-arrow"></i>
        </button>

        <span class="nav-section-label platform-label">平台</span>
        <button
          v-for="item in visibleNavigation.slice(2)"
          :key="item.path"
          class="nav-item"
          :class="{ active: isActive(item.modules) }"
          @click="router.push(item.path)"
        >
          <span class="nav-icon"><i :class="item.icon"></i></span>
          <span class="nav-copy">
            <strong>{{ item.label }}</strong>
            <small>{{ item.description }}</small>
          </span>
          <i v-if="isActive(item.modules)" class="bi bi-arrow-right-short nav-arrow"></i>
        </button>
      </nav>

      <div class="sidebar-footer">
        <div class="service-status" :class="`is-${readinessTone}`">
          <span class="status-dot"></span>
          <span>
            <strong>{{ readinessLabel }}</strong>
            <small>{{ readinessHint }}</small>
          </span>
        </div>
        <button class="operator-chip" @click="router.push('/admin/models')">
          <span class="operator-avatar">本地</span>
          <span><strong>本地操作员</strong><small>打开模型设置</small></span>
          <i class="bi bi-chevron-right"></i>
        </button>
      </div>
    </aside>

    <div class="app-main">
      <header v-if="!focus" class="topbar">
        <div class="topbar-title">
          <span class="topbar-eyebrow">语义数据工作台</span>
          <strong>{{ currentTitle }}</strong>
        </div>
        <div class="topbar-actions">
          <span class="topbar-context"><i class="bi bi-hdd-network"></i> 本地数据不会离开当前部署</span>
          <span class="topbar-status" :class="`is-${readinessTone}`">
            <span class="status-dot"></span>{{ readinessLabel }}
          </span>
        </div>
      </header>
      <div v-if="degradedMessage" class="degraded-banner" role="status">
        <div>
          <strong>模型服务需要处理</strong>
          <span>{{ degradedMessage }}</span>
        </div>
        <el-button size="small" @click="router.push('/admin/models')">查看模型设置</el-button>
      </div>
      <main class="page-content"><slot /></main>
    </div>
  </div>
</template>

<script setup>
  import { computed, onMounted, ref } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { platformContext } from '@/services/platformContext';

  defineProps({
    focus: { type: Boolean, default: false },
  });

  const router = useRouter();
  const route = useRoute();
  const readiness = ref();
  const readinessError = ref('');
  const navigation = [
    {
      label: '项目工作台',
      description: '项目、模型与治理',
      path: '/projects',
      icon: 'bi bi-grid-1x2',
      modules: ['project'],
    },
    {
      label: '查询工作台',
      description: '用业务语言查数据',
      path: '/chat',
      icon: 'bi bi-chat-square-quote',
      modules: ['chat'],
    },
    {
      label: '数据连接',
      description: '数据库与接入状态',
      path: '/connections',
      icon: 'bi bi-database',
      modules: ['connections'],
    },
    {
      label: '模型服务',
      description: '模型接口与可用性',
      path: '/admin/models',
      icon: 'bi bi-cpu',
      modules: ['admin'],
    },
  ];

  const visibleNavigation = computed(() => navigation);
  const isActive = modules => modules.includes(route.meta.module);
  const currentTitle = computed(() => String(route.meta.title || '工作区'));
  const readinessTone = computed(() => {
    if (readinessError.value) return 'unknown';
    if (!readiness.value) return 'checking';
    return readiness.value.ready ? 'ready' : 'attention';
  });
  const readinessLabel = computed(() => {
    if (readinessError.value) return '状态未知';
    if (!readiness.value) return '检查服务中';
    return readiness.value.ready ? '模型服务正常' : '需要处理';
  });
  const readinessHint = computed(() => {
    if (readinessError.value) return '点击打开模型设置';
    if (!readiness.value) return '正在读取实时状态';
    return readiness.value.ready ? '对话、向量、重排模型均可用' : '有模型暂不可用';
  });
  const degradedMessage = computed(() => {
    if (readinessError.value)
      return '模型能力状态暂时无法读取；项目、历史结果和管理页面仍可继续访问。';
    if (!readiness.value || readiness.value.ready) return '';
    const missing = [];
    if (!readiness.value.chatModelReady) missing.push('对话模型');
    if (!readiness.value.embeddingModelReady) missing.push('向量模型');
    if (!readiness.value.rerankModelReady) missing.push('重排模型');
    return `${missing.join('、')} 暂不可用；历史数据与控制面保持可访问，新查询和语义构建会被暂停。`;
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
    background: #f4f6f8;
  }
  .focus-layout .app-sidebar {
    display: none;
  }
  .focus-layout .app-main {
    margin-left: 0;
  }
  .focus-layout .page-content {
    min-height: 100vh;
  }
  .app-sidebar {
    position: fixed;
    inset: 0 auto 0 0;
    z-index: 100;
    display: flex;
    width: 248px;
    flex-direction: column;
    padding: 24px 16px 18px;
    background: #102131;
    color: #e7f1f3;
    box-shadow: 12px 0 28px rgb(13 28 42 / 8%);
  }
  .sidebar-brand {
    padding: 0 10px 24px;
    border-bottom: 1px solid rgb(255 255 255 / 10%);
  }
  .brand-logo {
    display: flex;
    align-items: center;
    gap: 11px;
    padding: 0;
    border: 0;
    background: transparent;
    color: #f5fbfb;
    cursor: pointer;
    text-align: left;
  }
  .brand-mark {
    display: grid;
    width: 38px;
    height: 38px;
    place-items: center;
    border: 1px solid rgb(133 233 213 / 30%);
    border-radius: 12px;
    background: linear-gradient(145deg, #65d7bf, #2a9d9a);
    color: #07242c;
    box-shadow: 0 8px 18px rgb(34 184 159 / 20%);
  }
  .brand-mark i {
    font-size: 19px;
  }
  .brand-copy {
    display: grid;
    gap: 2px;
  }
  .brand-copy strong {
    font-size: 16px;
    letter-spacing: -0.02em;
  }
  .brand-copy small {
    color: #94b4bb;
    font-size: 11px;
    letter-spacing: 0.08em;
  }
  .workspace-card {
    display: grid;
    gap: 5px;
    margin: 18px 4px 24px;
    padding: 13px 14px;
    border: 1px solid rgb(165 225 218 / 14%);
    border-radius: 12px;
    background: rgb(255 255 255 / 5%);
  }
  .workspace-kicker,
  .nav-section-label {
    color: #6f9ca6;
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 0.13em;
  }
  .workspace-card strong {
    color: #eaf7f6;
    font-size: 13px;
  }
  .workspace-card > span:last-child {
    color: #8cb3b7;
    font-size: 11px;
  }
  .workspace-card i {
    margin-right: 4px;
    color: #6cdec3;
  }
  .side-nav {
    display: flex;
    flex: 1;
    flex-direction: column;
    gap: 5px;
  }
  .platform-label {
    margin-top: 24px;
  }
  .nav-item {
    display: flex;
    width: 100%;
    align-items: center;
    gap: 10px;
    padding: 10px 10px;
    border: 1px solid transparent;
    border-radius: 10px;
    background: transparent;
    color: #a1bdc1;
    cursor: pointer;
    text-align: left;
    transition: 0.18s ease;
  }
  .nav-item:hover {
    border-color: rgb(125 219 202 / 14%);
    background: rgb(255 255 255 / 7%);
    color: #e4f3f1;
  }
  .nav-item.active {
    border-color: rgb(111 223 202 / 23%);
    background: linear-gradient(90deg, rgb(72 196 169 / 21%), rgb(72 196 169 / 7%));
    color: #edfffc;
  }
  .nav-icon {
    display: grid;
    width: 30px;
    height: 30px;
    flex: 0 0 auto;
    place-items: center;
    border-radius: 8px;
    background: rgb(255 255 255 / 7%);
    color: #77bfc0;
    font-size: 15px;
  }
  .nav-item.active .nav-icon {
    background: #62ceb5;
    color: #09242c;
  }
  .nav-copy {
    display: grid;
    min-width: 0;
    gap: 2px;
  }
  .nav-copy strong {
    font-size: 12px;
    font-weight: 650;
  }
  .nav-copy small {
    overflow: hidden;
    color: #7199a0;
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .nav-item.active .nav-copy small {
    color: #9ed6d0;
  }
  .nav-arrow {
    margin-left: auto;
    color: #79d9c1;
    font-size: 17px;
  }
  .sidebar-footer {
    display: grid;
    gap: 12px;
    padding: 16px 4px 0;
    border-top: 1px solid rgb(255 255 255 / 10%);
  }
  .service-status,
  .operator-chip {
    display: flex;
    align-items: center;
    gap: 9px;
  }
  .service-status > span:last-child,
  .operator-chip > span:nth-child(2) {
    display: grid;
    gap: 2px;
    min-width: 0;
  }
  .service-status strong,
  .operator-chip strong {
    color: #cde5e5;
    font-size: 11px;
    font-weight: 650;
  }
  .service-status small,
  .operator-chip small {
    overflow: hidden;
    color: #6f9ca6;
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .status-dot {
    display: block;
    width: 7px;
    height: 7px;
    flex: 0 0 auto;
    border-radius: 50%;
    background: #f3b75f;
    box-shadow: 0 0 0 4px rgb(243 183 95 / 10%);
  }
  .is-ready .status-dot {
    background: #66d8b8;
    box-shadow: 0 0 0 4px rgb(102 216 184 / 10%);
  }
  .is-attention .status-dot {
    background: #f3b75f;
  }
  .is-unknown .status-dot {
    background: #bdcbd0;
  }
  .operator-chip {
    width: 100%;
    padding: 8px 6px;
    border: 0;
    border-radius: 9px;
    background: rgb(255 255 255 / 5%);
    color: inherit;
    cursor: pointer;
    text-align: left;
  }
  .operator-chip:hover {
    background: rgb(255 255 255 / 9%);
  }
  .operator-avatar {
    display: grid;
    width: 29px;
    height: 29px;
    flex: 0 0 auto;
    place-items: center;
    border-radius: 9px;
    background: #d8ede7;
    color: #174f4d;
    font-size: 10px;
    font-weight: 750;
  }
  .operator-chip > i {
    margin-left: auto;
    color: #6f9ca6;
    font-size: 13px;
  }
  .app-main {
    min-height: 100vh;
    margin-left: 248px;
  }
  .topbar {
    position: sticky;
    top: 0;
    z-index: 100;
    display: flex;
    min-height: 70px;
    align-items: center;
    justify-content: space-between;
    gap: 20px;
    padding: 13px 34px;
    border-bottom: 1px solid #e3e8ec;
    background: rgb(250 252 252 / 93%);
    backdrop-filter: blur(12px);
  }
  .topbar-title {
    display: grid;
    gap: 2px;
  }
  .topbar-eyebrow {
    color: #8a9aa0;
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 0.13em;
  }
  .topbar-title > strong {
    color: #172a36;
    font-size: 17px;
    letter-spacing: -0.01em;
  }
  .topbar-actions {
    display: flex;
    align-items: center;
    gap: 14px;
  }
  .topbar-context,
  .topbar-status {
    display: flex;
    align-items: center;
    gap: 7px;
    color: #75878e;
    font-size: 11px;
  }
  .topbar-context i {
    color: #2ca791;
    font-size: 13px;
  }
  .topbar-status {
    padding: 7px 10px;
    border: 1px solid #dbeee8;
    border-radius: 99px;
    background: #f3fbf8;
    color: #155b53;
    font-weight: 650;
  }
  .degraded-banner {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 10px 34px;
    border-bottom: 1px solid #f2d99e;
    background: #fff8e8;
    color: #87591d;
    font-size: 12px;
  }
  .degraded-banner > div {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .page-content {
    min-height: calc(100vh - 70px);
  }
  @media (max-width: 900px) {
    .app-sidebar {
      position: sticky;
      inset: auto;
      width: 100%;
      min-height: 0;
      padding: 12px 14px 10px;
      box-shadow: 0 8px 20px rgb(13 28 42 / 8%);
    }
    .sidebar-brand {
      padding: 0 2px 10px;
      border-bottom: 0;
    }
    .workspace-card,
    .sidebar-footer,
    .nav-section-label {
      display: none;
    }
    .side-nav {
      display: flex;
      flex-direction: row;
      gap: 6px;
      overflow-x: auto;
      padding: 0 0 2px;
    }
    .nav-item {
      width: auto;
      flex: 0 0 auto;
      padding: 7px 10px;
    }
    .nav-copy small,
    .nav-arrow {
      display: none;
    }
    .nav-copy strong {
      font-size: 11px;
    }
    .nav-icon {
      width: 24px;
      height: 24px;
      font-size: 13px;
    }
    .app-main {
      margin-left: 0;
    }
    .topbar {
      min-height: 58px;
      padding: 10px 16px;
    }
    .topbar-context {
      display: none;
    }
    .topbar-title > strong {
      font-size: 15px;
    }
    .degraded-banner {
      align-items: flex-start;
      padding: 9px 16px;
      flex-direction: column;
    }
  }
</style>
