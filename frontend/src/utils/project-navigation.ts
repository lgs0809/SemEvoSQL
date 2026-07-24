/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

export type ProjectSection =
  "overview" | "external" | "prepare" | "improve" | "governance";
type PrepareTab = "datasources" | "documents" | "semantic" | "grill";
type ImproveTab =
  "inbox" | "semantic" | "examples" | "trajectory" | "optimization";
type GovernanceTab = "test" | "release";

export interface ProjectLocation {
  section: ProjectSection;
  prepareTab?: PrepareTab;
  improveTab?: ImproveTab;
  governanceTab?: GovernanceTab;
}

const locations: Record<string, ProjectLocation> = {
  overview: { section: "overview" },
  external: { section: "external" },
  mcp: { section: "external" },
  integration: { section: "external" },
  prepare: { section: "prepare" },
  data: { section: "prepare", prepareTab: "datasources" },
  business: { section: "prepare", prepareTab: "semantic" },
  datasources: { section: "prepare", prepareTab: "datasources" },
  documents: { section: "prepare", prepareTab: "documents" },
  semantic: { section: "prepare", prepareTab: "semantic" },
  grill: { section: "prepare", prepareTab: "grill" },
  improve: { section: "improve" },
  inbox: { section: "improve", improveTab: "inbox" },
  evolution: { section: "improve", improveTab: "semantic" },
  examples: { section: "improve", improveTab: "examples" },
  trajectory: { section: "improve", improveTab: "trajectory" },
  optimization: { section: "improve", improveTab: "optimization" },
  governance: { section: "governance" },
  evaluations: { section: "governance", governanceTab: "test" },
  test: { section: "governance", governanceTab: "test" },
  release: { section: "governance", governanceTab: "release" },
  versions: { section: "governance", governanceTab: "release" },
  releases: { section: "governance", governanceTab: "release" },
};

/** Display routing follows the existing administrator-only tabs; APIs remain authoritative. */
export function projectLocation(
  value: unknown,
  canManage: boolean,
): ProjectLocation {
  const key = typeof value === "string" ? value : "overview";
  const location = locations[key] || locations.overview!;
  return { ...(canManage ? location : locations.overview!) };
}

export function projectSectionRoute(
  section: ProjectSection,
  subsection?: string,
): string {
  if (section === "improve" && subsection === "semantic") return "evolution";
  const location = subsection ? locations[subsection] : undefined;
  return location?.section === section ? subsection! : section;
}

export const projectSectionCopy: Record<
  ProjectSection,
  { label: string; description: string; icon: string }
> = {
  overview: {
    label: "项目概览",
    description: "查看正式模型、查询质量和当前需要处理的事项。",
    icon: "bi bi-grid-1x2",
  },
  prepare: {
    label: "模型准备",
    description: "连接数据 → 补充资料 → 核对业务模型 → 确认待解决的口径。",
    icon: "bi bi-layers",
  },
  governance: {
    label: "验证与发布",
    description: "先查看测试与回归，再审核变更、发布并激活正式版本。",
    icon: "bi bi-shield-check",
  },
  improve: {
    label: "持续改进",
    description: "从真实查询中审查建议和案例；变更仍需经过验证与发布。",
    icon: "bi bi-arrow-up-right-circle",
  },
  external: {
    label: "外部接入",
    description: "把已发布的项目查询能力接入现有工具，并查看接入状态。",
    icon: "bi bi-plug",
  },
};
