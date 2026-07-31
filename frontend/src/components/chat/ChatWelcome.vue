<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section class="welcome-card">
    <template v-if="!hasProject">
      <div class="empty-welcome-icon"><i class="bi bi-database-add"></i></div>
      <span class="welcome-kicker">首次使用</span>
      <h2>先连接一个数据项目</h2>
      <p>连接业务数据库后，系统会从真实表结构开始建立业务模型，只在无法安全判断时请你确认口径。</p>
      <el-button type="primary" size="large" @click="emit('create-project')">
        创建第一个项目
      </el-button>
    </template>

    <template v-else>
      <div class="welcome-main">
        <div class="welcome-icon"><i class="bi bi-stars"></i></div>
        <span class="welcome-kicker">自然语言查询</span>
        <h2>你想知道什么？</h2>
        <p>
          直接描述指标、时间范围或对比关系。系统会先说明业务口径，再生成查询并保留完整依据。
        </p>
        <div class="welcome-context">
          <span>当前项目</span>
          <strong>{{ projectName }}</strong>
          <span v-if="versionNumber">业务模型 v{{ versionNumber }}</span>
          <span v-else class="context-warning">业务模型尚未发布</span>
        </div>
        <div class="welcome-actions">
          <el-button v-if="canStart" type="primary" size="large" @click="emit('create-conversation')">
            {{ hasConversation ? '新建查询会话' : '开始第一次查询' }}
          </el-button>
          <el-button v-else type="primary" plain size="large" @click="emit('manage-project')">
            去完成模型准备
          </el-button>
          <span class="welcome-safety"><i class="bi bi-check2"></i> 查询前可确认口径 · 结果可追溯</span>
        </div>
        <div v-if="!canStart" class="welcome-next-step">
          <strong>{{ nextAction?.label || '继续建设业务模型' }}</strong>
          <span>{{ nextAction?.description || '完成业务理解、验证和发布后即可创建查询。' }}</span>
        </div>
      </div>

      <div v-if="canStart && examples.length" class="examples">
        <div class="examples-heading">
          <span>从一个具体问题开始</span>
          <small>点击建议即可填入输入框</small>
        </div>
        <button
          v-for="example in examples"
          :key="example"
          type="button"
          @click="emit('use-example', example)"
        >
          <i class="bi bi-arrow-up-right"></i>
          <span>{{ example }}</span>
        </button>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
  import { computed } from 'vue';

  const props = defineProps<{
    hasProject: boolean;
    projectName: string;
    versionNumber?: string;
    projectStatus?: string;
    queryReady?: boolean;
    hasConversation: boolean;
    suggestedQuestions?: string[];
    nextAction?: {
      label: string;
      description: string;
      target: string;
    };
  }>();
  const emit = defineEmits<{
    'create-project': [];
    'create-conversation': [];
    'manage-project': [];
    'use-example': [value: string];
  }>();

  const canStart = computed(() => Boolean(props.versionNumber) && props.queryReady !== false);
  const examples = computed(() => props.suggestedQuestions || []);
</script>

<style scoped>
  .welcome-card {
    max-width: 820px;
    margin: 12vh auto 0;
    padding: 0 14px 30px;
    text-align: center;
  }
  .welcome-icon {
    display: grid;
    width: 46px;
    height: 46px;
    margin: 0 auto 16px;
    place-items: center;
    border: 1px solid #b9e1d9;
    border-radius: 14px;
    background: #e4f5f1;
    color: #177d73;
    font-size: 21px;
  }
  .empty-welcome-icon {
    display: grid;
    width: 56px;
    height: 56px;
    margin: 0 auto 18px;
    place-items: center;
    border-radius: 16px;
    background: #e4f5f1;
    color: #177d73;
    font-size: 25px;
  }
  .welcome-kicker {
    display: block;
    margin-bottom: 8px;
    color: #2a9d8f;
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 0.15em;
  }
  h2 {
    margin: 0 0 10px;
    color: #17353b;
    font-size: clamp(28px, 4vw, 42px);
    font-weight: 720;
    letter-spacing: -0.045em;
  }
  p {
    margin: 0;
    color: #6b7f86;
    line-height: 1.75;
  }
  .welcome-main {
    min-width: 0;
  }
  .welcome-main > p {
    max-width: 620px;
    margin-right: auto;
    margin-left: auto;
  }
  .welcome-context {
    display: inline-flex;
    align-items: center;
    flex-wrap: wrap;
    justify-content: center;
    gap: 7px;
    margin-top: 16px;
    color: #71858b;
    font-size: 12px;
  }
  .welcome-context strong {
    color: #31565d;
  }
  .context-warning {
    color: #9a6a2b;
  }
  .welcome-actions {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 12px;
    justify-content: center;
    margin-top: 24px;
  }
  .welcome-safety {
    color: #6e8d87;
    font-size: 11px;
  }
  .welcome-safety i {
    color: #2b9274;
  }
  .welcome-next-step {
    display: grid;
    gap: 4px;
    max-width: 560px;
    margin: 18px auto 0;
    padding: 12px 16px;
    border: 1px solid #ead7ae;
    border-radius: 10px;
    background: #fffaf0;
    text-align: left;
  }
  .welcome-next-step strong {
    color: #536d72;
    font-size: 12px;
  }
  .welcome-next-step span {
    color: #83979b;
    font-size: 11px;
    line-height: 1.5;
  }
  .examples {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px;
    margin-top: 22px;
    text-align: left;
  }
  .examples-heading {
    display: flex;
    grid-column: 1 / -1;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
  }
  .examples-heading span {
    color: #46636a;
    font-size: 12px;
    font-weight: 700;
  }
  .examples-heading small {
    color: #8a9ba1;
    font-size: 10px;
  }
  .examples button {
    display: flex;
    min-height: 72px;
    align-items: flex-start;
    gap: 9px;
    padding: 13px 14px;
    border: 1px solid #dce9e8;
    border-radius: 11px;
    background: #fff;
    color: #42616a;
    text-align: left;
    cursor: pointer;
    transition: 0.18s ease;
  }
  .examples button:hover {
    border-color: #9bcfc5;
    background: #f2fbf8;
    color: #177d73;
  }
  .examples button i {
    padding-top: 2px;
    color: #2a9d8f;
    font-size: 12px;
  }
  .examples button span {
    line-height: 1.5;
  }
  @media (max-width: 760px) {
    .welcome-card {
      margin-top: 5vh;
    }
    .examples {
      grid-template-columns: 1fr;
    }
    .examples-heading {
      align-items: flex-start;
      flex-direction: column;
      gap: 4px;
    }
    .welcome-actions {
      align-items: stretch;
      flex-direction: column;
    }
    .welcome-actions .el-button {
      width: 100%;
    }
  }
</style>
