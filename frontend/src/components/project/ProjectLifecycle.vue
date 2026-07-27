<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section
    v-if="health"
    class="project-lifecycle"
    :class="{ compact: compact && health.queryReady }"
    aria-label="项目准备进度"
    aria-live="polite"
  >
    <div class="lifecycle-heading">
      <div>
        <span class="eyebrow">项目进度</span>
        <strong>{{ lifecycleTitle }}</strong>
        <p>{{ lifecycleDescription }}</p>
      </div>
      <el-button
        v-if="showAction && actionTarget"
        :type="health.queryReady ? 'primary' : 'default'"
        @click="emit('action', actionTarget)"
      >
        {{ health.queryReady ? '打开查询工作台' : primaryAction?.label }}
      </el-button>
    </div>

    <ol v-if="!compact || !health.queryReady" class="lifecycle-stages">
      <li v-for="(stage, index) in stages" :key="stage.id" :class="stage.state">
        <div class="stage-marker" aria-hidden="true">
          <i v-if="stage.state === 'done'" class="bi bi-check-lg"></i>
          <span v-else>{{ index + 1 }}</span>
        </div>
        <div class="stage-copy">
          <strong>{{ stage.label }}</strong>
          <small>{{ stage.description }}</small>
        </div>
      </li>
    </ol>
  </section>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import type { ProjectHealth } from '@/services/semevosql';
  import {
    projectLifecycleStages,
    projectPrimaryAction,
    type ProjectHealthAction,
  } from '@/services/projectExperience';

  const props = withDefaults(
    defineProps<{
      health?: ProjectHealth;
      showAction?: boolean;
      compact?: boolean;
    }>(),
    { health: undefined, showAction: true, compact: false },
  );

  const emit = defineEmits<{
    action: [target: ProjectHealthAction['target']];
  }>();

  const stages = computed(() => projectLifecycleStages(props.health));
  const primaryAction = computed(() => projectPrimaryAction(props.health));
  const actionTarget = computed<ProjectHealthAction['target'] | undefined>(() =>
    props.health?.queryReady ? 'chat' : primaryAction.value?.target,
  );
  const lifecycleTitle = computed(() => {
    if (props.health?.queryReady) return '查询入口已就绪';
    const current = stages.value.find(stage => stage.state === 'current');
    return current ? `当前：${current.label}` : '正在读取项目准备状态';
  });
  const lifecycleDescription = computed(() => {
    if (props.health?.queryReady) {
      const version = props.health.activeVersion?.versionNumber;
      return version
        ? `正式业务模型 v${version} 已激活，新会话将固定使用该版本。`
        : '正式业务模型已激活。';
    }
    return (
      primaryAction.value?.description ||
      'SemEvoSQL 会根据当前项目事实确定下一步，不会跳过业务确认、验证或发布门禁。'
    );
  });
</script>

<style scoped>
  .project-lifecycle {
    margin-bottom: 20px;
    padding: 18px 20px;
    border: 1px solid #dce7e7;
    border-radius: 14px;
    background: #fff;
    box-shadow: 0 8px 30px rgb(15 23 42 / 4%);
  }
  .project-lifecycle.compact {
    margin-bottom: 14px;
    padding: 12px 16px;
    border-color: #d5e9e4;
    background: #f4fbf9;
    box-shadow: none;
  }
  .project-lifecycle.compact .lifecycle-heading {
    align-items: center;
    margin-bottom: 0;
  }
  .project-lifecycle.compact .lifecycle-heading strong {
    font-size: 14px;
  }
  .project-lifecycle.compact .lifecycle-heading p {
    margin-top: 3px;
  }
  .lifecycle-heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 20px;
    margin-bottom: 18px;
  }
  .eyebrow {
    display: block;
    margin-bottom: 5px;
    color: #6f858b;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }
  .lifecycle-heading strong {
    display: block;
    color: #17353b;
    font-size: 16px;
  }
  .lifecycle-heading p {
    margin: 6px 0 0;
    color: #71858b;
    font-size: 12px;
    line-height: 1.55;
  }
  .lifecycle-stages {
    display: grid;
    grid-template-columns: repeat(6, minmax(0, 1fr));
    gap: 8px;
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .lifecycle-stages li {
    position: relative;
    display: flex;
    min-width: 0;
    gap: 9px;
    padding: 10px;
    border: 1px solid transparent;
    border-radius: 11px;
    background: #f6f9f9;
  }
  .lifecycle-stages li.current {
    border-color: #b9e1d9;
    background: #f1fbf8;
  }
  .lifecycle-stages li.done {
    background: #f8fafc;
  }
  .stage-marker {
    display: grid;
    width: 24px;
    height: 24px;
    flex: 0 0 auto;
    place-items: center;
    border-radius: 50%;
    background: #e2e8f0;
    color: #64748b;
    font-size: 11px;
    font-weight: 700;
  }
  .done .stage-marker {
    background: #e5f6ee;
    color: #2b896d;
  }
  .current .stage-marker {
    background: #177d73;
    color: #fff;
  }
  .stage-copy {
    display: grid;
    min-width: 0;
    gap: 3px;
  }
  .stage-copy strong {
    overflow: hidden;
    color: #3d5962;
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .stage-copy small {
    display: -webkit-box;
    overflow: hidden;
    color: #8a9ba1;
    font-size: 10px;
    line-height: 1.35;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }
  .current .stage-copy strong {
    color: #177d73;
  }
  @media (max-width: 1100px) {
    .lifecycle-stages {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
  }
  @media (max-width: 680px) {
    .lifecycle-heading {
      flex-direction: column;
    }
    .lifecycle-stages {
      grid-template-columns: 1fr;
    }
    .stage-copy small {
      -webkit-line-clamp: 1;
    }
  }
</style>
