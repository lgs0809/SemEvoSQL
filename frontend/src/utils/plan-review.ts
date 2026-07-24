import type { RunEvent } from '../services/semevosql';

const record = (value: unknown): Record<string, unknown> | undefined =>
  value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;

const labels = (value: unknown): string[] =>
  Array.isArray(value)
    ? [...new Set(value.map(record).map((item) => item?.businessName)
      .filter((name): name is string => typeof name === 'string' && Boolean(name.trim())))]
    : [];

/** Describe only the immutable plan attached to the current approval round. */
export function currentPlanReviewSummary(
  events: RunEvent[], runId: string, requiredSequence: number, answeredSequence = 0,
): string | undefined {
  const snapshot = events.filter((event) => event.runId === runId
    && event.eventType === 'APPROVAL_PLAN_SNAPSHOT'
    && event.sequence > answeredSequence && event.sequence < requiredSequence)
    .sort((left, right) => right.sequence - left.sequence)[0];
  if (!snapshot?.payload) return undefined;
  try {
    const plan = record(JSON.parse(snapshot.payload));
    if (!plan) return undefined;
    const result = record(plan.resultContract);
    const measures = labels(result?.personalMeasures);
    const metrics = measures.length ? measures : labels(plan.metrics);
    const dimensions = labels(plan.dimensions);
    const sources = labels(plan.models);
    const time = record(plan.timeRange);
    const start = time?.startInclusive;
    const end = time?.endExclusive;
    const parts = [metrics.length ? `查询指标：${metrics.join('、')}` : '',
      dimensions.length ? `分组：${dimensions.join('、')}` : '',
      typeof start === 'string' && typeof end === 'string'
        ? `时间范围：${start}（含）至 ${end}（不含）` : '',
      sources.length ? `数据来源：${sources.join('、')}` : ''].filter(Boolean);
    return parts.length ? `${parts.join('；')}。请确认当前计划是否执行。` : undefined;
  } catch {
    return undefined;
  }
}
