type ResumeRun = { status: string; retryable?: boolean; errorCode?: string | null; resumeDeadlineEpochMillis?: number | null };

export function resumeExpired(run: ResumeRun | undefined, now: number): boolean {
  return Boolean(run && ['FAILED', 'QUEUED'].includes(run.status) &&
    (run.retryable !== false || run.errorCode === 'QUERY_EXPIRED') &&
    run.resumeDeadlineEpochMillis != null && now >= run.resumeDeadlineEpochMillis);
}

export function canResumeRun(run: ResumeRun | undefined, now: number): boolean {
  return Boolean(run && !resumeExpired(run, now) &&
    (run.status === 'QUEUED' || (run.status === 'FAILED' && run.retryable !== false)));
}
