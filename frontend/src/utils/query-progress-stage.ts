type ProgressRun = { status: string; currentNode?: string };
type ProgressEvent = { eventType: string };

/** Worker and budget events are transport facts, not proof that SQL has started. */
export function queryProgressStage(run: ProgressRun, events: readonly ProgressEvent[] = [], needsAction = false): number {
  if (run.status === 'SUCCEEDED') return 4;
  if (needsAction || run.status === 'WAITING_HUMAN') return 1;
  const node = (run.currentNode || '').toUpperCase().replaceAll('-', '_');
  let stage = /MERGE|FINAL|REPORT|RESULT_ARTIFACT|AGGREGAT/.test(node) ? 3
    : /SQL|SOURCE_SUBRUN|MULTI_SOURCE_EXECUTION/.test(node) ? 2
    : /SEMANTIC|BLUEPRINT|CLARIFICATION|HUMAN|QUERY_UNDERSTANDING/.test(node) ? 1 : 0;
  for (const event of events) {
    const type = event.eventType.toUpperCase();
    if (/^(RESULT_ARTIFACT|FINAL_RESULT|QUERY_ANSWER|RESULT_MERGE)/.test(type)) stage = Math.max(stage, 3);
    else if (type === 'PLANNING_TRACE' || /^SQL_|^SOURCE_SUBRUN_/.test(type)) stage = Math.max(stage, 2);
    else if (['REQUEST_ANALYSIS_COMPLETED', 'QUERY_UNDERSTANDING_READY', 'REQUEST_APPROVED', 'SEMANTIC_PLANNING_INTERRUPTED', 'CLARIFICATION_CREATED', 'CLARIFICATION_ANSWERED'].includes(type)) stage = Math.max(stage, 1);
  }
  return stage;
}
