/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
import { onMounted, onUnmounted, readonly, shallowRef } from 'vue';
import { platformContext, type PlatformReadiness } from '@/services/platformContext';

const readiness = shallowRef<PlatformReadiness>();
const error = shallowRef('');
let consumers = 0;
let timer: ReturnType<typeof setInterval> | undefined;
let refreshing: Promise<void> | undefined;

function refresh(): Promise<void> {
  if (!refreshing) {
    refreshing = platformContext.readiness(true)
      .then(value => {
        readiness.value = value;
        error.value = '';
      })
      .catch(cause => {
        error.value = cause instanceof Error ? cause.message : '平台模型能力状态读取失败';
      })
      .finally(() => { refreshing = undefined; });
  }
  return refreshing;
}

function refreshVisible() {
  if (document.visibilityState === 'visible') void refresh();
}

/** Mounted views share one status poll; the endpoint reads stored evidence only. */
export function usePlatformReadiness() {
  onMounted(() => {
    if (consumers++ === 0) {
      timer = setInterval(refreshVisible, 15_000);
      window.addEventListener('online', refreshVisible);
      window.addEventListener('focus', refreshVisible);
      document.addEventListener('visibilitychange', refreshVisible);
    }
    refreshVisible();
  });
  onUnmounted(() => {
    if (--consumers === 0) {
      clearInterval(timer);
      timer = undefined;
      window.removeEventListener('online', refreshVisible);
      window.removeEventListener('focus', refreshVisible);
      document.removeEventListener('visibilitychange', refreshVisible);
    }
  });
  return { readiness: readonly(readiness), error: readonly(error) };
}
