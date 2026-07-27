export type ProjectSection =
  "overview" | "external" | "prepare" | "improve" | "governance";
export type ProjectListAction = "VIEW" | "CHAT" | "PREPARE";

export interface ProjectListSummaryCapabilityInput {
  available: boolean;
  queryReady: boolean;
}

export function defaultHomeForRole(): "/projects";
export function projectListAction(
  summary: ProjectListSummaryCapabilityInput | undefined,
): ProjectListAction;
