/* Copyright 2026 the original author or authors. Licensed under the Apache License, Version 2.0. */
import axios from 'axios';
import { shallowRef } from 'vue';

declare module 'axios' {
  interface InternalAxiosRequestConfig { csrfRecovered?: boolean }
}

interface LocalSession {
  enabled: boolean;
  username?: string;
  administrator?: boolean;
  csrfHeader?: string;
  csrfToken?: string;
}

export const currentSession = shallowRef<LocalSession>();
export const sessionNotice = shallowRef('');
export const sessionExpired = shallowRef(false);
let loading: Promise<LocalSession> | undefined;
let lastAuthenticatedUser: string | undefined;

axios.interceptors.request.use(config => {
  const session = currentSession.value;
  const method = (config.method || 'get').toLowerCase();
  if (config.url?.startsWith('/api/') && !['get', 'head', 'options'].includes(method)
      && session?.csrfHeader && session.csrfToken) {
    config.headers.set(session.csrfHeader, session.csrfToken);
  }
  return config;
});
axios.interceptors.response.use(response => response, async error => {
  if (error?.response?.status === 401 && error.config?.url?.startsWith('/api/')
      && !error.config.url.endsWith('/auth/login')) {
    currentSession.value = undefined;
    sessionExpired.value = true;
    error.message = '登录已失效，请重新登录；已有查询仍会在后台继续执行。';
  }
  const request = error.config;
  const intendedUser = currentSession.value?.username ?? lastAuthenticatedUser;
  // Spring Security rejects this request before the controller runs. Refresh once, then replay
  // the same request identity only if the logged-in principal is still the intended operator.
  if (error?.response?.status === 403 && error.response.data === 'Invalid CSRF Token'
      && request?.url?.startsWith('/api/') && !request.csrfRecovered
      && !/\/auth\/(session|login|logout)$/.test(request.url) && intendedUser) {
    request.csrfRecovered = true;
    const fresh = await localSession.load(true);
    if (fresh.enabled && fresh.username === intendedUser) return axios.request(request);
    sessionNotice.value = '登录账号已变化，旧操作未提交。请确认当前账号后再继续。';
    error.message = sessionNotice.value;
  } else if (error?.response?.status === 403) {
    error.message = error.response.data === 'Invalid CSRF Token'
      ? '登录验证已失效，请刷新页面后再提交。'
      : '当前账号无权执行此操作，请确认账号与项目权限。';
  }
  return Promise.reject(error);
});

export const localSession = {
  async load(force = false): Promise<LocalSession> {
    if (!force && currentSession.value) return currentSession.value;
    if (!loading) {
      loading = axios.get<LocalSession>('/api/semevosql/auth/session')
        .then(response => {
          currentSession.value = response.data;
          lastAuthenticatedUser = response.data.enabled ? response.data.username : undefined;
          if (!response.data.enabled || response.data.username) sessionExpired.value = false;
          return response.data;
        })
        .finally(() => { loading = undefined; });
    }
    return loading;
  },
  async login(username: string, password: string): Promise<void> {
    await this.load(true);
    await axios.post('/api/semevosql/auth/login', new URLSearchParams({ username, password }));
    await this.load(true);
  },
  async logout(): Promise<void> {
    await axios.post('/api/semevosql/auth/logout');
    currentSession.value = undefined;
    lastAuthenticatedUser = undefined;
    sessionExpired.value = false;
    await this.load(true);
  },
};
