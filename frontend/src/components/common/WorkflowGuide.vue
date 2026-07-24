<!-- Copyright 2024-2026 the original author or authors. Licensed under the Apache License, Version 2.0. -->
<template>
  <ol class="workflow-guide" :aria-label="label">
    <li v-for="(step, index) in steps" :key="step.title">
      <span class="workflow-number" aria-hidden="true">{{ index + 1 }}</span>
      <div>
        <strong>{{ step.title }}</strong>
        <p>{{ step.description }}</p>
      </div>
    </li>
  </ol>
</template>
<script setup lang="ts">
defineProps<{
  label: string;
  steps: Array<{ title: string; description: string }>;
}>();
</script>
<style scoped>
.workflow-guide {
  display: flex;
  list-style: none;
  gap: 16px;
  margin: 16px 0;
  padding: 16px;
  border: 1px solid #dfeae7;
  border-radius: 12px;
  background: #f6faf8;
}
li {
  display: flex;
  flex: 1;
  min-width: 0;
  gap: 10px;
}
.workflow-number {
  display: grid;
  flex: 0 0 24px;
  height: 24px;
  place-items: center;
  border-radius: 8px;
  background: #e4f2ed;
  color: #177d73;
  font-size: 11px;
  font-weight: 700;
}
strong {
  color: #2b484d;
  font-size: 12px;
}
p {
  margin: 4px 0 0;
  color: #627980;
  font-size: 12px;
  line-height: 1.65;
}
@media (max-width: 700px) {
  .workflow-guide {
    flex-direction: column;
    gap: 14px;
  }
}
</style>
