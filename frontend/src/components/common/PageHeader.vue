<!-- Copyright 2024-2026 the original author or authors. Licensed under the Apache License, Version 2.0. -->
<template>
  <header class="workspace-page-header">
    <div class="page-header-copy">
      <el-button v-if="backTo" link @click="router.push(backTo)"
        ><i class="bi bi-arrow-left" aria-hidden="true"></i
        >{{ backLabel }}</el-button
      >
      <h1>{{ title }}</h1>
      <p v-if="description">{{ description }}</p>
    </div>
    <div v-if="$slots.default" class="page-header-actions"><slot /></div>
  </header>
</template>
<script setup lang="ts">
import { useRouter } from "vue-router";
withDefaults(
  defineProps<{
    title: string;
    description?: string;
    backTo?: string;
    backLabel?: string;
  }>(),
  { description: "", backTo: "", backLabel: "返回项目" },
);
const router = useRouter();
</script>
<style scoped>
.workspace-page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20px;
  margin-bottom: 26px;
}
.page-header-copy {
  min-width: 0;
}
h1 {
  margin: 0 0 8px;
  color: #172a36;
  font-size: clamp(26px, 2.5vw, 32px);
  font-weight: 720;
  letter-spacing: -0.03em;
  overflow-wrap: anywhere;
  line-height: 1.35;
}
p {
  max-width: 740px;
  margin: 0;
  color: #627780;
  font-size: 13px;
  line-height: 1.8;
}
.page-header-copy > .el-button {
  margin-bottom: 12px;
  gap: 6px;
}
.page-header-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding-top: 4px;
}
@media (max-width: 700px) {
  .workspace-page-header {
    flex-direction: column;
    gap: 14px;
  }
  .page-header-actions {
    width: 100%;
  }
}
</style>
