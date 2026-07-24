/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
import { computed, ref, watch, type Ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  projectLocation,
  projectSectionCopy,
  projectSectionRoute,
  type ProjectSection,
} from "../utils/project-navigation.ts";

export function useProjectNavigation(canManage: Readonly<Ref<boolean>>) {
  const route = useRoute();
  const router = useRouter();
  const activeSection = ref<ProjectSection>("overview");
  const prepareTab = ref("datasources");
  const improveTab = ref("inbox");
  const governanceTab = ref("test");
  watch(
    [() => route.query.section || route.query.tab, canManage],
    ([value, allowed]) => {
      const location = projectLocation(value, allowed);
      activeSection.value = location.section;
      if (location.prepareTab) prepareTab.value = location.prepareTab;
      if (location.improveTab) improveTab.value = location.improveTab;
      if (location.governanceTab) governanceTab.value = location.governanceTab;
    },
    { immediate: true },
  );
  const syncSectionRoute = (name: string | number) => {
    const section = projectLocation(String(name), canManage.value).section;
    const child =
      section === "prepare"
        ? prepareTab.value
        : section === "improve"
          ? improveTab.value
          : section === "governance"
            ? governanceTab.value
            : undefined;
    const target = Object.hasOwn(projectSectionCopy, String(name))
      ? projectSectionRoute(section, child)
      : String(name);
    void router.replace({
      query: { ...route.query, section: target, tab: undefined },
    });
  };
  const syncSubsectionRoute = (
    section: ProjectSection,
    name: string | number,
  ) => {
    activeSection.value = section;
    void router.replace({
      query: {
        ...route.query,
        section: projectSectionRoute(section, String(name)),
        tab: undefined,
      },
    });
  };
  const syncPrepareTabRoute = (name: string | number) =>
    syncSubsectionRoute("prepare", name);
  const syncImproveTabRoute = (name: string | number) =>
    syncSubsectionRoute("improve", name);
  const syncGovernanceTabRoute = (name: string | number) =>
    syncSubsectionRoute("governance", name);
  const sectionContext = computed(
    () => projectSectionCopy[activeSection.value],
  );
  return {
    activeSection,
    prepareTab,
    improveTab,
    governanceTab,
    sectionContext,
    syncSectionRoute,
    syncPrepareTabRoute,
    syncImproveTabRoute,
    syncGovernanceTabRoute,
  };
}
