<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <div class="home-redirect" v-loading="true" aria-label="正在进入 SemEvoSQL"></div>
</template>

<script setup lang="ts">
  import { onMounted } from 'vue';
  import { useRouter } from 'vue-router';
  import { defaultHomeForRole } from '@/services/projectCapabilities.mjs';

  const router = useRouter();

  onMounted(async () => {
    await router.replace(defaultHomeForRole());
  });
</script>

<style scoped>
  .home-redirect {
    min-height: 100vh;
  }
</style>
