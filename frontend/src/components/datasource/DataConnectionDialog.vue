<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <el-dialog
    :model-value="modelValue"
    :title="editing ? '编辑数据连接' : '新建数据连接'"
    width="620px"
    destroy-on-close
    @close="emit('update:modelValue', false)"
    @opened="initialize"
  >
    <el-alert
      v-if="editing"
      class="credential-alert"
      type="info"
      show-icon
      :closable="false"
      title="数据库密码已安全保存，不会回显。留空表示保持原密码。"
    />
    <el-form label-position="top" :model="form">
      <div class="form-grid">
        <el-form-item label="连接名称" required>
          <el-input v-model="form.name" placeholder="输入连接名称" />
        </el-form-item>
        <el-form-item label="数据库类型" required>
          <el-select
            v-model="form.type"
            filterable
            placeholder="选择数据库类型"
            @change="applyDefaultPort"
          >
            <el-option
              v-for="item in datasourceTypes"
              :key="item.code"
              :label="item.displayName || item.dialect || item.typeName"
              :value="item.typeName"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="主机地址" required>
          <el-input v-model="form.host" placeholder="输入数据库主机地址" />
        </el-form-item>
        <el-form-item label="端口" required>
          <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" />
        </el-form-item>
        <el-form-item label="数据库名" required>
          <el-input v-model="form.databaseName" placeholder="输入数据库名称" />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="off" />
        </el-form-item>
      </div>
      <el-form-item :label="editing ? '密码（留空保持原密码）' : '密码'">
        <el-input
          v-model="form.password"
          type="password"
          show-password
          autocomplete="new-password"
          :placeholder="editing ? '已配置' : '输入数据库密码'"
        />
      </el-form-item>
      <el-form-item label="说明">
        <el-input v-model="form.description" type="textarea" :rows="2" placeholder="可选" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button :loading="saving" @click="save(false)">保存</el-button>
      <el-button type="primary" :loading="testing" @click="save(true)">保存并测试</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
  import { reactive, ref } from 'vue';
  import { ElMessage } from 'element-plus';
  import datasourceService, { type Datasource, type DatasourceType } from '@/services/datasource';
  import { getApiErrorMessage } from '@/services/common';

  const props = defineProps<{
    modelValue: boolean;
    datasource?: Datasource | null;
  }>();
  const emit = defineEmits<{
    'update:modelValue': [value: boolean];
    saved: [datasource: Datasource];
  }>();

  const datasourceTypes = ref<DatasourceType[]>([]);
  const saving = ref(false);
  const testing = ref(false);
  const editing = ref(false);
  const form = reactive<Datasource>({
    name: '',
    type: '',
    host: '',
    port: 3306,
    databaseName: '',
    username: '',
    password: '',
    description: '',
    status: 'active',
  });

  const defaultPorts: Record<string, number> = {
    MYSQL: 3306,
    POSTGRESQL: 5432,
    POSTGRES: 5432,
  };

  const initialize = async () => {
    editing.value = Boolean(props.datasource?.id);
    Object.assign(form, {
      name: props.datasource?.name || '',
      type: props.datasource?.type || '',
      host: props.datasource?.host || '',
      port: props.datasource?.port || 3306,
      databaseName: props.datasource?.databaseName || '',
      username: props.datasource?.username || '',
      password: '',
      description: props.datasource?.description || '',
      status: props.datasource?.status || 'active',
    });
    try {
      const result = await datasourceService.getDatasourceTypes();
      datasourceTypes.value = result.data || [];
      if (!form.type && datasourceTypes.value.length > 0) {
        form.type = datasourceTypes.value[0].typeName;
        applyDefaultPort();
      }
    } catch (error) {
      ElMessage.error(getApiErrorMessage(error, '数据源类型加载失败'));
    }
  };

  const applyDefaultPort = () => {
    const key = String(form.type || '').toUpperCase();
    const next = defaultPorts[key];
    if (next) form.port = next;
  };

  const validate = () => {
    if (
      !form.name?.trim() ||
      !form.type ||
      !form.host?.trim() ||
      !form.port ||
      !form.databaseName?.trim()
    ) {
      ElMessage.warning('请填写连接名称、数据库类型、主机地址、端口和数据库名');
      return false;
    }
    return true;
  };

  const payload = (): Datasource => ({
    name: form.name?.trim(),
    type: form.type,
    host: form.host?.trim(),
    port: form.port,
    databaseName: form.databaseName?.trim(),
    username: form.username?.trim() || '',
    ...(form.password ? { password: form.password } : {}),
    description: form.description?.trim() || '',
    status: form.status || 'active',
  });

  const save = async (testAfterSave: boolean) => {
    if (!validate()) return;
    saving.value = !testAfterSave;
    testing.value = testAfterSave;
    try {
      const saved = props.datasource?.id
        ? await datasourceService.updateDatasource(props.datasource.id, payload())
        : await datasourceService.createDatasource(payload());
      if (testAfterSave) {
        if (!saved.id) throw new Error('数据连接保存成功，但未返回连接 ID');
        await datasourceService.testConnection(saved.id);
        saved.testStatus = 'success';
        ElMessage.success('数据连接已保存，连接测试成功');
      } else {
        ElMessage.success(editing.value ? '数据连接已更新' : '数据连接已创建');
      }
      emit('saved', saved);
      emit('update:modelValue', false);
    } catch (error) {
      ElMessage.error(
        getApiErrorMessage(error, testAfterSave ? '保存或测试连接失败' : '保存数据连接失败'),
      );
    } finally {
      saving.value = false;
      testing.value = false;
    }
  };
</script>

<style scoped>
  .credential-alert {
    margin-bottom: 18px;
  }
  .form-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0 18px;
  }
  @media (max-width: 720px) {
    .form-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
