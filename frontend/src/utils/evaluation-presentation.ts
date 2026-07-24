export function storedObject(raw: unknown): Record<string, unknown> | undefined {
  try {
    let value = typeof raw === 'string' ? JSON.parse(raw) : raw;
    // JdbcTemplate rows expose PostgreSQL's PGobject until the API has a typed DTO.
    if (value && typeof value === 'object' && ['json', 'jsonb'].includes(value.type)
        && typeof value.value === 'string') value = JSON.parse(value.value);
    if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
    if ('schemaVersion' in value) {
      return value.schemaVersion === 1 && value.payload && typeof value.payload === 'object' && !Array.isArray(value.payload)
        ? value.payload : undefined;
    }
    return value;
  } catch { return undefined; }
}

export function replayVerdict(job: { status?: string; result_json?: unknown }) {
  if (job.status !== 'SUCCEEDED') return { label: '等待执行结果', type: 'info' as const, summary: '' };
  const result = storedObject(job.result_json);
  const total = result?.total, passed = result?.passed, failed = result?.failed;
  if (![total, passed, failed].every(value => Number.isInteger(value) && Number(value) >= 0)
      || Number(passed) + Number(failed) !== total) {
    return { label: '结果待核对', type: 'warning' as const, summary: '任务已结束，但缺少完整的验证统计。' };
  }
  if (total === 0) return { label: '未执行用例', type: 'warning' as const, summary: '没有启用的验证用例，不能认定回归通过。' };
  const summary = `共 ${total} 项，${passed} 项通过，${failed} 项失败`;
  if (Number(failed) > 0 || result?.safetyPassed === false) {
    return { label: '检查未通过', type: 'danger' as const,
      summary: summary + (result?.safetyPassed === false ? '；安全就绪检查未通过' : '') };
  }
  if (result?.safetyPassed !== true) return { label: '安全检查待核对', type: 'warning' as const, summary };
  return { label: '检查通过', type: 'success' as const, summary };
}

export const replayModeLabel = (value?: string) =>
  ({ LIVE: '实时数据回放', FIXTURE: '固定数据集核验' }[value || 'LIVE'] ?? '未识别的回放方式');

const records = (value: unknown): Record<string, unknown>[] =>
  Array.isArray(value) ? value.filter(item => item && typeof item === 'object' && !Array.isArray(item)) : [];
const savedCount = (value: unknown) =>
  typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : undefined;

/** Display persisted evidence only. Missing older fields do not become successful checks. */
export function replayCaseEvidence(raw: unknown) {
  return records(storedObject(raw)?.proofs).map((proof, index) => {
    const errors = Array.isArray(proof.errors) ? proof.errors.filter(item => typeof item === 'string') : [];
    const verified = proof.status === 'PASSED' && errors.length === 0;
    const failed = proof.status === 'FAILED' || errors.length > 0;
    const results = records(proof.executionProof).filter(item => item.artifactType === 'SOURCE_RESULT');
    return {
      code: typeof proof.caseCode === 'string' ? proof.caseCode : `未记录编号的检查 ${index + 1}`,
      label: verified ? '通过' : failed ? '未通过' : '未核验',
      type: verified ? 'success' as const : failed ? 'danger' as const : 'warning' as const,
      errors,
      modelCalls: savedCount(proof.modelCallCount),
      sources: savedCount(proof.sourceCount),
      latencyMs: savedCount(proof.latencyMs),
      rows: savedCount(proof.resultRowCount),
      queries: records(proof.sourceQueries).filter(item => typeof item.sql === 'string'),
      results: results.map(item => ({
        columns: Array.isArray(item.columns) ? item.columns.filter(column => typeof column === 'string') : [],
        rows: records(item.rows),
      })),
    };
  });
}
