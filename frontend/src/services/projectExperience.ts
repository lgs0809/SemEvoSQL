/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import type { ProjectHealth } from './semevosql';

type ProjectLifecycleState = 'done' | 'current' | 'pending';
type ProjectLifecycleStageId =
  | 'data'
  | 'understanding'
  | 'rules'
  | 'validation'
  | 'release'
  | 'chat';

interface ProjectLifecycleStage {
  id: ProjectLifecycleStageId;
  label: string;
  description: string;
  state: ProjectLifecycleState;
}

export type ProjectHealthAction = ProjectHealth['nextActions'][number];

const VERSION_VALIDATED = new Set(['VALIDATED', 'READY', 'PUBLISHED']);

export const projectLifecycleStages = (health?: ProjectHealth): ProjectLifecycleStage[] => {
  const workingStatus = health?.workingVersion?.status || '';
  const completion = [
    (health?.understanding.datasourceCount || 0) > 0,
    Boolean(health?.understanding.catalogReady),
    Boolean(health?.understanding.catalogReady) &&
      (health?.understanding.openGapCount || 0) === 0 &&
      (health?.understanding.unresolvedConflictCount || 0) === 0,
    Boolean(health?.activeVersion) || VERSION_VALIDATED.has(workingStatus),
    Boolean(health?.queryReady && health?.activeVersion),
    (health?.quality.totalQueries || 0) > 0,
  ];

  const definitions: Array<Omit<ProjectLifecycleStage, 'state'>> = [
    {
      id: 'data',
      label: '连接数据',
      description: '选择供查询使用的数据连接与业务表',
    },
    {
      id: 'understanding',
      label: '理解业务',
      description: '从数据库结构和业务资料构建业务模型',
    },
    {
      id: 'rules',
      label: '确认规则',
      description: '只确认无法安全推断的关键业务含义',
    },
    {
      id: 'validation',
      label: '验证模型',
      description: '检查结构、场景与回归事实',
    },
    {
      id: 'release',
      label: '发布激活',
      description: '正式版本通过门禁后供新会话使用',
    },
    {
      id: 'chat',
      label: '打开查询工作台',
      description: '使用发布版本进行可追溯业务查询',
    },
  ];

  const currentIndex = completion.findIndex(done => !done);
  return definitions.map((item, index) => ({
    ...item,
    state: completion[index] ? 'done' : currentIndex === index ? 'current' : 'pending',
  }));
};

export const projectPrimaryAction = (health?: ProjectHealth): ProjectHealthAction | undefined =>
  health?.nextActions?.[0];

export const projectDetailSectionForTarget = (
  target?: ProjectHealthAction['target'],
): 'overview' | 'prepare' | 'improve' | 'governance' | 'chat' => {
  if (target === 'data' || target === 'business') return 'prepare';
  if (target === 'improve') return 'improve';
  if (target === 'test' || target === 'release') return 'governance';
  if (target === 'chat') return 'chat';
  return 'overview';
};

export const projectDetailSubsectionForTarget = (
  target?: ProjectHealthAction['target'],
): string | undefined => {
  if (target === 'data') return 'datasources';
  if (target === 'business') return 'semantic';
  if (target === 'test') return 'test';
  if (target === 'release') return 'release';
  if (target === 'improve') return 'inbox';
  return undefined;
};
