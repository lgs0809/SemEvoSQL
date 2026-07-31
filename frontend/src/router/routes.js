/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */

const routes = [
  {
    path: '/',
    name: 'Home',
    component: () => import('@/views/HomeRedirect.vue'),
    meta: { title: 'SemEvoSQL', module: 'home' },
  },
  {
    path: '/semevosql',
    redirect: '/',
  },
  {
    path: '/chat',
    name: 'ProjectChat',
    component: () => import('@/views/ProjectChat.vue'),
    meta: { title: '查询工作台', module: 'chat' },
  },
  {
    path: '/projects',
    name: 'ProjectList',
    component: () => import('@/views/ProjectList.vue'),
    meta: { title: '项目', module: 'project' },
  },
  {
    path: '/projects/create',
    name: 'ProjectCreate',
    component: () => import('@/views/ProjectCreate.vue'),
    meta: { title: '创建项目', module: 'project' },
  },
  {
    path: '/projects/:id',
    name: 'ProjectDetail',
    component: () => import('@/views/ProjectDetail.vue'),
    meta: { title: '项目详情', module: 'project' },
  },
  {
    path: '/connections',
    name: 'DataConnections',
    component: () => import('@/views/DataConnections.vue'),
    meta: { title: '数据连接', module: 'connections' },
  },
  {
    path: '/admin',
    redirect: '/admin/models',
  },
  {
    path: '/admin/models',
    name: 'AdminModels',
    component: () => import('@/views/ModelConfig.vue'),
    meta: { title: '模型', module: 'admin' },
  },
  {
    path: '/admin/settings',
    name: 'AdminSettings',
    component: () => import('@/views/SystemSettings.vue'),
    meta: { title: '系统服务', module: 'admin' },
  },
  {
    path: '/model-config',
    redirect: '/admin/models',
  },
  {
    path: '/settings',
    redirect: '/admin/settings',
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFound.vue'),
    meta: { title: '页面未找到', module: 'error' },
  },
];

export default routes;
