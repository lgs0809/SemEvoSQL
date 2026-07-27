import test from "node:test";
import assert from "node:assert/strict";
import {
  projectLocation,
  projectSectionRoute,
} from "../src/utils/project-navigation.ts";

test("preparation and improvement semantic tabs have distinct bookmark identities", () => {
  assert.deepEqual(projectLocation("semantic", true), {
    section: "prepare",
    prepareTab: "semantic",
  });
  assert.equal(projectSectionRoute("improve", "semantic"), "evolution");
  assert.deepEqual(projectLocation("evolution", true), {
    section: "improve",
    improveTab: "semantic",
  });
});

test("every child location survives refresh and independently addressed navigation", () => {
  for (const [section, children, key] of [
    [
      "prepare",
      ["datasources", "documents", "semantic", "grill"],
      "prepareTab",
    ],
    [
      "improve",
      ["inbox", "semantic", "examples", "trajectory", "optimization"],
      "improveTab",
    ],
    ["governance", ["test", "release"], "governanceTab"],
  ])
    for (const child of children) {
      const location = projectLocation(
        projectSectionRoute(section, child),
        true,
      );
      assert.equal(location.section, section);
      assert.equal(location[key], child);
    }
});

test("member bookmarks fall back to their visible overview rather than a hidden blank tab", () => {
  for (const target of [
    "semantic",
    "evolution",
    "release",
    "mcp",
    "prepare",
    "optimization",
  ])
    assert.deepEqual(projectLocation(target, false), { section: "overview" });
  assert.deepEqual(projectLocation(["release", "overview"], true), {
    section: "overview",
  });
  assert.deepEqual(projectLocation("missing", true), { section: "overview" });
});

test("legacy external and release links remain readable without selecting an unrelated child", () => {
  assert.equal(projectLocation("integration", true).section, "external");
  assert.deepEqual(projectLocation("versions", true), {
    section: "governance",
    governanceTab: "release",
  });
  assert.equal(projectSectionRoute("prepare", "release"), "prepare");
});
