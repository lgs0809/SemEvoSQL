/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

/** Self-hosted single-user capability helpers. Governance workflow state is server-owned. */
export function defaultHomeForRole() {
  return '/projects';
}

export function projectSectionVisible(section) {
  void section;
  return true;
}

export function projectListAction(summary) {
  if (!summary?.available) return 'VIEW';
  return summary.queryReady ? 'CHAT' : 'PREPARE';
}
