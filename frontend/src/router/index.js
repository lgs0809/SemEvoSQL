/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { createRouter, createWebHistory } from 'vue-router';
import routes from '@/router/routes';
import { localSession } from '@/services/localSession';

const routerBase = import.meta.env.VITE_SEMEVOSQL_BASE_PATH || '/semevosql/';

const router = createRouter({
  history: createWebHistory(routerBase),
  routes,
  scrollBehavior(to, from, savedPosition) {
    return savedPosition || { top: 0 };
  },
});

router.beforeEach(async to => {
  document.title = to.meta?.title ? `${to.meta.title} - SemEvoSQL` : 'SemEvoSQL';
  const session = await localSession.load();
  if (session.enabled && !session.username && to.name !== 'LocalLogin')
    return { name: 'LocalLogin', query: { returnTo: to.fullPath } };
  if ((!session.enabled || session.username) && to.name === 'LocalLogin') return '/projects';
  return true;
});

export default router;
