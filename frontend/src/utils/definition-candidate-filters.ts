export interface CandidateFilter {
  search: string;
  lifecycle: string;
  threshold: 'ALL' | 'REACHED' | 'PENDING';
  alignment: string;
}

export function filterDefinitionCandidates<T extends {
  business_name: string;
  definition_text: string;
  lifecycle: string;
  threshold_reached: boolean;
  assessment_json?: { alignment: { relation: string } };
}>(candidates: T[], filter: CandidateFilter): T[] {
  const search = filter.search.trim().toLocaleLowerCase();
  return candidates.filter(candidate =>
    (!search || `${candidate.business_name} ${candidate.definition_text}`.toLocaleLowerCase().includes(search)) &&
    (filter.lifecycle === 'ALL' || candidate.lifecycle === filter.lifecycle) &&
    (filter.threshold === 'ALL' || candidate.threshold_reached === (filter.threshold === 'REACHED')) &&
    (filter.alignment === 'ALL' || (candidate.assessment_json?.alignment.relation || 'PENDING') === filter.alignment),
  );
}
