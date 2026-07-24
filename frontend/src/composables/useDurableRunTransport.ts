/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
import { computed, ref } from 'vue';
import { reconnectDelay } from '@/services/runRecoveryState.mjs';
import type { RunEvent } from '@/services/semevosql';

type RunTransportStatus =
  | 'IDLE'
  | 'CONNECTING'
  | 'CONNECTED'
  | 'RECONNECTING'
  | 'POLLING'
  | 'OFFLINE';

interface DurableRunTransportOptions {
  isTerminal: () => boolean;
  lastSequence: () => number;
  catchUp: (runId: string) => Promise<void>;
  onEvent: (event: RunEvent) => void;
  subscribe: (
    runId: string,
    afterSequence: number,
    onEvent: (event: RunEvent) => void,
    onError: () => void,
    onOpen: () => void,
  ) => EventSource;
}

const RECONNECT_DELAYS = [1000, 2000, 5000, 10000];

export function useDurableRunTransport(options: DurableRunTransportOptions) {
  const status = ref<RunTransportStatus>('IDLE');
  const followedRunId = ref<string>();

  let eventSource: EventSource | undefined;
  let reconnectTimer: number | undefined;
  let pollingTimer: number | undefined;
  let reconnectAttempt = 0;
  let generation = 0;

  const notice = computed(() => {
    if (status.value === 'OFFLINE') return '连接中断，查询仍在后台继续';
    if (status.value === 'RECONNECTING') return '正在恢复查询结果';
    if (status.value === 'POLLING') return '正在同步查询结果';
    if (status.value === 'CONNECTING') return '正在连接查询进度';
    return '';
  });

  const clearReconnectTimer = () => {
    if (reconnectTimer != null) window.clearTimeout(reconnectTimer);
    reconnectTimer = undefined;
  };

  const stopPolling = () => {
    if (pollingTimer != null) window.clearInterval(pollingTimer);
    pollingTimer = undefined;
  };

  const closeEventSource = () => {
    eventSource?.close();
    eventSource = undefined;
  };

  const stop = (clearFollowedRun = false) => {
    closeEventSource();
    clearReconnectTimer();
    stopPolling();
    reconnectAttempt = 0;
    generation += 1;
    status.value = 'IDLE';
    if (clearFollowedRun) followedRunId.value = undefined;
  };

  const isFollowing = (runId: string) => followedRunId.value === runId;

  const begin = (runId: string) => {
    const changed = followedRunId.value !== runId;
    stop(false);
    followedRunId.value = runId;
    return changed;
  };

  const startPollingFallback = (runId: string, expectedGeneration: number) => {
    if (pollingTimer != null || options.isTerminal()) return;
    status.value = 'POLLING';
    pollingTimer = window.setInterval(() => {
      if (
        expectedGeneration !== generation ||
        followedRunId.value !== runId ||
        options.isTerminal()
      ) {
        stopPolling();
        return;
      }
      void options.catchUp(runId);
    }, 5000);
  };

  const scheduleReconnect = (runId: string, expectedGeneration: number) => {
    if (reconnectTimer != null || options.isTerminal() || followedRunId.value !== runId) return;
    const delay = reconnectDelay(reconnectAttempt, RECONNECT_DELAYS);
    reconnectTimer = window.setTimeout(async () => {
      reconnectTimer = undefined;
      if (
        expectedGeneration !== generation ||
        followedRunId.value !== runId ||
        options.isTerminal()
      )
        return;
      if (!navigator.onLine) {
        status.value = 'OFFLINE';
        return;
      }
      try {
        await options.catchUp(runId);
        if (!options.isTerminal()) connect(runId, expectedGeneration);
      } catch {
        reconnectAttempt += 1;
        if (reconnectAttempt >= 4) startPollingFallback(runId, expectedGeneration);
        scheduleReconnect(runId, expectedGeneration);
      }
    }, delay);
  };

  const connect = (runId: string, expectedGeneration = generation) => {
    if (expectedGeneration !== generation || followedRunId.value !== runId || options.isTerminal())
      return;
    closeEventSource();
    status.value = reconnectAttempt > 0 ? 'RECONNECTING' : 'CONNECTING';
    eventSource = options.subscribe(
      runId,
      options.lastSequence(),
      event => {
        if (expectedGeneration !== generation || followedRunId.value !== runId) return;
        options.onEvent(event);
      },
      () => {
        if (
          expectedGeneration !== generation ||
          followedRunId.value !== runId ||
          options.isTerminal()
        )
          return;
        closeEventSource();
        reconnectAttempt += 1;
        status.value = navigator.onLine ? 'RECONNECTING' : 'OFFLINE';
        if (reconnectAttempt >= 4) startPollingFallback(runId, expectedGeneration);
        scheduleReconnect(runId, expectedGeneration);
      },
      () => {
        if (expectedGeneration !== generation || followedRunId.value !== runId) return;
        reconnectAttempt = 0;
        clearReconnectTimer();
        stopPolling();
        status.value = 'CONNECTED';
      },
    );
  };

  const handleOnline = () => {
    const runId = followedRunId.value;
    if (!runId || options.isTerminal()) return;
    reconnectAttempt = 0;
    clearReconnectTimer();
    status.value = 'RECONNECTING';
    const expectedGeneration = generation;
    void options
      .catchUp(runId)
      .then(() => {
        if (!options.isTerminal()) connect(runId, expectedGeneration);
      })
      .catch(() => {
        reconnectAttempt = 1;
        scheduleReconnect(runId, expectedGeneration);
      });
  };

  const handleOffline = () => {
    closeEventSource();
    clearReconnectTimer();
    stopPolling();
    if (followedRunId.value && !options.isTerminal()) status.value = 'OFFLINE';
  };

  return {
    status,
    notice,
    followedRunId,
    begin,
    connect,
    stop,
    isFollowing,
    handleOnline,
    handleOffline,
  };
}
