<!-- Copyright 2026 the original author or authors. Licensed under the Apache License, Version 2.0. -->
<template>
  <main class="local-login">
    <div class="login-shell">
      <section class="login-intro" aria-label="SemEvoSQL 工作区">
        <div class="login-brand">
          <span><i class="bi bi-diagram-3" aria-hidden="true"></i></span
          ><strong>SemEvoSQL</strong>
        </div>
        <span class="login-eyebrow">语义数据工作台</span>
        <h2>用业务语言，<br />读懂你的数据。</h2>
        <p>从业务模型到查询结果，每一步都有可核对的依据。</p>
        <ol>
          <li>
            <i class="bi bi-database" aria-hidden="true"></i
            >连接业务数据，确认关键口径
          </li>
          <li>
            <i class="bi bi-chat-square-text" aria-hidden="true"></i
            >自然语言提问，遇到歧义先问询
          </li>
          <li>
            <i class="bi bi-shield-check" aria-hidden="true"></i
            >核对来源，审查变更与发布
          </li>
        </ol>
        <small>自托管工作区 · 使用现有账号登录</small>
      </section>
      <el-card class="login-card" shadow="never">
        <h1>登录 SemEvoSQL</h1>
        <p>使用本地工作区账号访问自己的项目和查询记录。</p>
        <el-alert
          v-if="route.query.expired === '1'"
          title="登录已失效，请重新登录。已有查询仍在后台执行，登录后返回原会话查看结果。"
          type="warning"
          :closable="false"
        />
        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item label="账号">
            <el-input
              v-model="username"
              aria-label="账号"
              placeholder="输入工作区账号"
              size="large"
              autocomplete="username"
            />
          </el-form-item>
          <el-form-item label="密码">
            <el-input
              v-model="password"
              aria-label="密码"
              placeholder="输入密码"
              size="large"
              type="password"
              show-password
              autocomplete="current-password"
            />
          </el-form-item>
          <el-alert
            v-if="error"
            :title="error"
            type="error"
            :closable="false"
          />
          <el-button
            type="primary"
            size="large"
            native-type="submit"
            :loading="busy"
            :disabled="!username || !password"
            >登录</el-button
          >
        </el-form>
      </el-card>
    </div>
  </main>
</template>
<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { localSession } from "@/services/localSession";
import { platformContext } from "@/services/platformContext";
const username = ref("");
const password = ref("");
const error = ref("");
const busy = ref(false);
const route = useRoute();
const router = useRouter();
const submit = async () => {
  busy.value = true;
  error.value = "";
  try {
    await localSession.login(username.value, password.value);
    password.value = "";
    platformContext.invalidateOperator();
    platformContext.invalidateReadiness();
    const target = String(route.query.returnTo || "/projects");
    await router.replace(
      target.startsWith("/") && !target.startsWith("//") ? target : "/projects",
    );
  } catch {
    error.value = "登录失败，请检查账号和密码；服务暂不可用时稍后重试。";
  } finally {
    busy.value = false;
  }
};
</script>
<style scoped>
.local-login {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 32px 20px;
  background:
    radial-gradient(ellipse at 20% 30%, #e1f0e9 0, transparent 60%), #f4f6f8;
}
.login-shell {
  display: grid;
  grid-template-columns: 1.15fr 1fr;
  width: min(100%, 980px);
  min-height: 520px;
  border: 1px solid #dbe6e3;
  border-radius: 22px;
  overflow: hidden;
  background: #fff;
  box-shadow: 0 24px 80px rgb(16 42 41 / 9%);
}
.login-intro {
  display: flex;
  flex-direction: column;
  padding: 38px;
  background: #102d32;
  color: #e5f4ee;
}
.login-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 42px;
}
.login-brand > span {
  display: grid;
  width: 35px;
  height: 35px;
  place-items: center;
  border-radius: 10px;
  background: #68d1b7;
  color: #10372f;
}
.login-brand strong {
  font-size: 17px;
  letter-spacing: -0.03em;
}
.login-eyebrow {
  color: #8dc2b6;
  font-size: 11px;
  letter-spacing: 0.14em;
}
h2 {
  margin: 10px 0 18px;
  font-size: clamp(28px, 3vw, 36px);
  line-height: 1.45;
  letter-spacing: -0.04em;
}
.login-intro p {
  color: #a8c5c5;
  line-height: 1.8;
  font-size: 13px;
}
ol {
  list-style: none;
  display: grid;
  gap: 18px;
  margin-top: 26px;
  font-size: 13px;
  color: #cfdfdf;
}
li {
  display: flex;
  align-items: center;
  gap: 10px;
}
li i {
  color: #68d1b7;
}
.login-intro small {
  margin-top: auto;
  padding-top: 30px;
  color: #8cadad;
  font-size: 11px;
}
.login-card {
  align-self: center;
  margin: 30px;
  border: 0 !important;
  box-shadow: none !important;
}
h1 {
  margin-bottom: 8px;
  font-size: 23px;
  letter-spacing: -0.02em;
}
.login-card p {
  color: #657c84;
  margin-bottom: 26px;
  font-size: 13px;
  line-height: 1.8;
}
.el-form {
  margin-top: 20px;
}
.el-button {
  width: 100%;
  margin-top: 12px;
}
@media (max-width: 700px) {
  .login-shell {
    grid-template-columns: 1fr;
    min-height: 0;
  }
  .login-intro {
    padding: 24px;
  }
  .login-brand {
    margin-bottom: 14px;
  }
  h2 {
    font-size: 26px;
  }
  ol,
  .login-intro > p {
    display: none;
  }
  .login-intro small {
    padding-top: 12px;
  }
  .login-card {
    margin: 20px 12px;
  }
}
</style>
