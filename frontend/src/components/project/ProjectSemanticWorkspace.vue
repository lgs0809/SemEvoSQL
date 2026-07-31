<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section class="semantic-workspace" v-loading="loading">
    <div class="toolbar">
      <div>
        <div class="title-row">
          <h2>业务模型</h2>
          <el-tag v-if="selectedVersion" :type="editable ? 'warning' : 'success'" effect="plain">
            {{ editable ? '草稿 · 可编辑' : '已发布/验证版本 · 只读' }}
          </el-tag>
        </div>
        <p>用业务对象、指标、维度、枚举、时间和关系描述“系统应该怎样理解业务问题”。</p>
      </div>
      <el-select
        v-model="selectedVersionId"
        class="version-select"
        placeholder="选择版本"
        @change="load"
      >
        <el-option
          v-for="version in versions"
          :key="version.id"
          :label="`v${version.versionNumber} · ${sharedVersionStatusLabel(version.status)}`"
          :value="version.id"
        />
      </el-select>
    </div>

    <el-alert
      v-if="errorMessage"
      class="section-gap"
      type="error"
      :closable="false"
      :title="errorMessage"
      show-icon
    />

    <template v-if="catalog && policy">
      <div class="stats-grid">
        <button
          v-for="item in catalogStats"
          :key="item.key"
          type="button"
          :class="{ active: activeSection === item.key }"
          @click="activeSection = item.key"
        >
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
        </button>
      </div>

      <el-alert
        v-if="violations.length"
        class="section-gap"
        type="warning"
        :closable="false"
        show-icon
        :title="`当前业务模型还有 ${violations.length} 项多数据源发布校验未满足`"
      >
        <template #default>
          <el-collapse class="violation-collapse">
            <el-collapse-item title="查看校验项">
              <ul>
                <li v-for="item in violations" :key="item">{{ item }}</li>
              </ul>
            </el-collapse-item>
          </el-collapse>
        </template>
      </el-alert>

      <div v-if="!editable" class="readonly-notice">
        <i class="bi bi-lock"></i>
        <span>
          这个版本保持只读。如需调整业务口径，请创建草稿，再经过校验、回归测试、发布和激活。
        </span>
      </div>

      <el-tabs v-model="activeSection" class="business-tabs">
        <el-tab-pane label="业务对象" name="objects">
          <div class="section-heading">
            <div>
              <h3>业务对象</h3>
              <p>业务对象对应系统可查询的核心实体；物理表只作为来源依据展示。</p>
            </div>
          </div>
          <div v-if="catalog.models.length" class="asset-card-grid">
            <article
              v-for="(model, index) in catalog.models"
              :key="assetKey(model, index)"
              class="asset-card"
            >
              <div class="asset-heading">
                <div>
                  <strong>
                    {{ businessObjectName(model.modelCode) || '未命名业务对象' }}
                  </strong>
                  <span>
                    {{ text(model.description) || '尚未填写业务描述' }}
                  </span>
                </div>
                <el-button v-if="editable" link type="primary" @click="editAsset('models', index)">
                  编辑
                </el-button>
              </div>
              <dl class="asset-facts">
                <div>
                  <dt>对象编码</dt>
                  <dd>{{ text(model.modelCode) || '-' }}</dd>
                </div>
                <div>
                  <dt>指标</dt>
                  <dd>{{ countForModel('metrics', model.modelCode) }}</dd>
                </div>
                <div>
                  <dt>维度</dt>
                  <dd>{{ countForModel('dimensions', model.modelCode) }}</dd>
                </div>
                <div>
                  <dt>物理来源</dt>
                  <dd>{{ text(model.physicalTable) || '-' }}</dd>
                </div>
              </dl>
              <div v-if="text(model.evidence)" class="evidence-line">
                <span>来源证据</span>
                  <small>{{ evidenceLabel(model.evidence) }}</small>
              </div>
            </article>
          </div>
          <el-empty v-else description="还没有识别到业务对象" />
        </el-tab-pane>

        <el-tab-pane label="指标" name="metrics">
          <div class="section-heading">
            <div>
              <h3>指标</h3>
              <p>查看指标业务定义、计算方式、默认过滤、默认时间和可用维度。</p>
            </div>
            <el-button v-if="editable" type="primary" plain @click="addAsset('metrics')">
              新增指标
            </el-button>
          </div>
          <div v-if="catalog.metrics.length" class="metric-list">
            <article
              v-for="(metric, index) in catalog.metrics"
              :key="assetKey(metric, index)"
              class="metric-card"
            >
              <div class="asset-heading">
                <div>
                  <strong>
                    {{ text(metric.businessName) || text(metric.metricCode) || '未命名指标' }}
                  </strong>
                  <span>
                    {{ text(metric.description) || `指标编码：${text(metric.metricCode) || '-'}` }}
                  </span>
                </div>
                <div class="asset-actions">
                  <el-tag size="small" effect="plain">
                    {{ assetStatusLabel(metric.status) }}
                  </el-tag>
                  <el-button
                    v-if="editable"
                    link
                    type="primary"
                    @click="editAsset('metrics', index)"
                  >
                    编辑
                  </el-button>
                  <el-button
                    v-if="editable"
                    link
                    :type="text(metric.status) === 'DISABLED' ? 'success' : 'warning'"
                    @click="toggleAssetStatus('metrics', index)"
                  >
                    {{ text(metric.status) === 'DISABLED' ? '恢复' : '停用' }}
                  </el-button>
                </div>
              </div>
              <div class="metric-detail-grid">
                <div>
                  <span>业务定义</span>
                  <strong>
                    {{ text(metric.description) || text(metric.businessName) || '-' }}
                  </strong>
                </div>
                <div>
                  <span>计算方式</span>
                  <code>{{ text(metric.expression) || '-' }}</code>
                </div>
                <div>
                  <span>默认过滤</span>
                  <code>{{ text(metric.filterExpression) || '无' }}</code>
                </div>
                <div>
                  <span>默认时间</span>
                  <strong>{{ text(metric.timeColumn) || '未指定' }}</strong>
                </div>
                <div>
                  <span>聚合 / 单位</span>
                  <strong>
                    {{
                      [text(metric.aggregation), text(metric.unit)].filter(Boolean).join(' · ') ||
                      '-'
                    }}
                  </strong>
                </div>
                <div>
                  <span>可用维度</span>
                  <strong>{{ dimensionsForModel(metric.modelCode) }}</strong>
                </div>
                <div class="wide">
                  <span>来源证据</span>
                  <strong>{{ text(metric.evidence) || '未记录' }}</strong>
                </div>
              </div>
            </article>
          </div>
          <el-empty v-else description="还没有定义指标" />
        </el-tab-pane>

        <el-tab-pane label="维度" name="dimensions">
          <div class="section-heading">
            <div>
              <h3>维度</h3>
              <p>描述“按什么看数据”，包括所属业务对象、字段/表达式、层级与业务说明。</p>
            </div>
            <el-button v-if="editable" type="primary" plain @click="addAsset('dimensions')">
              新增维度
            </el-button>
          </div>
          <el-table :data="catalog.dimensions" empty-text="还没有定义维度">
            <el-table-column label="维度" min-width="180">
              <template #default="scope">
                <div class="table-primary">
                  <strong>
                    {{ text(scope.row.businessName) || text(scope.row.dimensionCode) }}
                  </strong>
                  <small>{{ text(scope.row.description) || text(scope.row.dimensionCode) }}</small>
                </div>
              </template>
            </el-table-column>
                <el-table-column label="业务对象" min-width="140">
                  <template #default="scope">{{ businessObjectName(scope.row.modelCode) }}</template>
                </el-table-column>
            <el-table-column label="来源" min-width="180">
              <template #default="scope">
                {{ text(scope.row.expression) || text(scope.row.columnName) || '-' }}
              </template>
            </el-table-column>
            <el-table-column prop="dimensionType" label="类型" width="130" />
            <el-table-column prop="hierarchy" label="层级" min-width="140" />
            <el-table-column v-if="editable" label="操作" width="140" fixed="right">
              <template #default="scope">
                <el-button link type="primary" @click="editAsset('dimensions', scope.$index)">
                  编辑
                </el-button>
                <el-button
                  link
                  :type="text(scope.row.status) === 'DISABLED' ? 'success' : 'warning'"
                  @click="toggleAssetStatus('dimensions', scope.$index)"
                >
                  {{ text(scope.row.status) === 'DISABLED' ? '恢复' : '停用' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="枚举" name="enums">
          <div class="section-heading">
            <div>
              <h3>枚举</h3>
              <p>把数据库代码翻译为业务含义，并保留用户常用别名。</p>
            </div>
            <el-button v-if="editable" type="primary" plain @click="addAsset('enumValues')">
              新增枚举
            </el-button>
          </div>
          <el-table :data="catalog.enumValues" empty-text="还没有定义枚举">
            <el-table-column label="业务含义" min-width="170">
              <template #default="scope">
                <strong>{{ text(scope.row.businessName) || text(scope.row.valueCode) }}</strong>
              </template>
            </el-table-column>
            <el-table-column prop="valueCode" label="数据库值" min-width="130" />
            <el-table-column label="字段" min-width="180">
              <template #default="scope">
                {{ text(scope.row.modelCode) }}.{{ text(scope.row.columnName) }}
              </template>
            </el-table-column>
            <el-table-column prop="aliases" label="同义叫法" min-width="190" />
            <el-table-column
              prop="description"
              label="说明"
              min-width="220"
              show-overflow-tooltip
            />
            <el-table-column v-if="editable" label="操作" width="140" fixed="right">
              <template #default="scope">
                <el-button link type="primary" @click="editAsset('enumValues', scope.$index)">
                  编辑
                </el-button>
                <el-button
                  link
                  :type="text(scope.row.status) === 'DISABLED' ? 'success' : 'warning'"
                  @click="toggleAssetStatus('enumValues', scope.$index)"
                >
                  {{ text(scope.row.status) === 'DISABLED' ? '恢复' : '停用' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="时间" name="time">
          <div class="section-heading">
            <div>
              <h3>时间口径</h3>
              <p>集中查看业务对象粒度、默认时间字段和时间相关业务规则。</p>
            </div>
          </div>
          <div class="time-layout">
            <section class="fact-panel">
              <h4>默认时间字段</h4>
              <el-table :data="timeColumns" size="small" empty-text="尚未识别时间字段">
                <el-table-column prop="businessName" label="业务含义" min-width="160" />
                <el-table-column label="业务对象" min-width="130">
                  <template #default="scope">{{ businessObjectName(scope.row.modelCode) }}</template>
                </el-table-column>
                <el-table-column prop="columnName" label="字段" min-width="150" />
                <el-table-column prop="synonyms" label="同义词" min-width="180" />
                <el-table-column v-if="editable" label="操作" width="70">
                  <template #default="scope">
                    <el-button link type="primary" @click="editColumn(scope.row)">编辑</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </section>
            <section class="fact-panel">
              <h4>业务粒度</h4>
              <div v-if="catalog.grains.length" class="stack-list">
                <div
                  v-for="(grain, index) in catalog.grains"
                  :key="assetKey(grain, index)"
                  class="stack-item"
                >
                  <div>
                    <strong>{{ text(grain.description) || text(grain.grainCode) }}</strong>
                    <small>
                      {{ businessObjectName(grain.modelCode) }} · 关联键：{{ text(grain.keyColumns) || '-' }} · 时间：
                      {{ text(grain.timeColumn) || '-' }}
                    </small>
                  </div>
                  <el-button
                    v-if="editable"
                    link
                    type="primary"
                    @click="editAsset('grains', index)"
                  >
                    编辑
                  </el-button>
                </div>
              </div>
              <el-empty v-else :image-size="50" description="尚未定义业务粒度" />
            </section>
          </div>
          <section class="fact-panel time-rules">
            <h4>时间与查询规则</h4>
            <div v-if="timeRules.length" class="stack-list">
              <div
                v-for="rule in timeRules"
                :key="assetKey(rule.item, rule.index)"
                class="stack-item"
              >
                <div>
                  <strong>{{ text(rule.item.businessName) || text(rule.item.ruleCode) }}</strong>
                  <small>
                    {{ text(rule.item.description) || text(rule.item.expression) || '-' }}
                  </small>
                </div>
                <el-button
                  v-if="editable"
                  link
                  type="primary"
                  @click="editAsset('rules', rule.index)"
                >
                  编辑
                </el-button>
              </div>
            </div>
            <el-empty v-else :image-size="50" description="尚无专门时间规则" />
          </section>
        </el-tab-pane>

        <el-tab-pane label="关系" name="relationships">
          <div class="section-heading">
            <div>
              <h3>业务关系</h3>
              <p>说明业务对象之间如何关联；关联条件默认作为依据，而不是用户首先需要理解的概念。</p>
            </div>
            <el-button v-if="editable" type="primary" plain @click="addAsset('relationships')">
              新增关系
            </el-button>
          </div>
          <div v-if="catalog.relationships.length" class="relationship-list">
            <article
              v-for="(relationship, index) in catalog.relationships"
              :key="assetKey(relationship, index)"
              class="relationship-card"
            >
              <div class="relationship-flow">
                <strong>{{ businessObjectName(relationship.sourceModelCode) }}</strong>
                <span>→</span>
                <strong>{{ businessObjectName(relationship.targetModelCode) }}</strong>
                <el-tag size="small" effect="plain">
                  {{ text(relationship.cardinality) || '未标注基数' }}
                </el-tag>
              </div>
              <p>
                {{
                  text(relationship.description) ||
                  text(relationship.relationshipCode) ||
                  '业务对象关系'
                }}
              </p>
              <details>
                <summary>查看关联依据</summary>
                <code>
                  {{ text(relationship.joinType) || '关联' }} ·
                  {{ text(relationship.joinCondition) || '未记录关联条件' }}
                </code>
                <small v-if="text(relationship.evidence)">
                  来源：{{ text(relationship.evidence) }}
                </small>
              </details>
              <div v-if="editable" class="asset-actions">
                <el-button link type="primary" @click="editAsset('relationships', index)">
                  编辑关系
                </el-button>
                <el-button
                  link
                  :type="text(relationship.status) === 'DISABLED' ? 'success' : 'warning'"
                  @click="toggleAssetStatus('relationships', index)"
                >
                  {{ text(relationship.status) === 'DISABLED' ? '恢复' : '停用' }}
                </el-button>
              </div>
            </article>
          </div>
          <el-empty v-else description="还没有定义业务关系" />
        </el-tab-pane>

        <el-tab-pane label="多数据源策略" name="sources">
          <div class="section-heading">
            <div>
              <h3>多数据源策略</h3>
              <p>回答“同一个业务事实应该信哪个来源、数据多快可用、跨库如何对应、结果如何合并”。</p>
            </div>
          </div>
          <div class="policy-summary-grid">
            <article>
              <span>统一字段映射</span>
              <strong>{{ policy.logicalBindings.length }}</strong>
              <small>跨来源如何映射同一业务属性</small>
            </article>
            <article>
              <span>权威来源规则</span>
              <strong>{{ policy.authorityRules.length }}</strong>
              <small>同一指标/维度优先相信哪个来源</small>
            </article>
            <article>
              <span>数据时效规则</span>
              <strong>{{ policy.freshnessPolicies.length }}</strong>
              <small>来源更新时间与可用边界</small>
            </article>
            <article>
              <span>跨源关系</span>
              <strong>{{ policy.crossSourceRelationships.length }}</strong>
              <small>不同数据库对象如何对应</small>
            </article>
            <article>
              <span>结果合并规则</span>
              <strong>{{ policy.mergePolicies.length }}</strong>
              <small>有限结果集如何安全合并</small>
            </article>
          </div>
          <el-tabs class="policy-tabs">
            <el-tab-pane label="权威来源">
              <el-table :data="policy.authorityRules" empty-text="尚无权威来源规则" size="small">
                <el-table-column prop="logicalAssetCode" label="业务资产" min-width="180" />
                <el-table-column prop="logicalAssetType" label="类型" width="130" />
                <el-table-column label="数据连接" min-width="160">
                  <template #default="scope">{{ datasourceLabel(scope.row.datasourceId) }}</template>
                </el-table-column>
                <el-table-column label="来源角色" min-width="140">
                  <template #default="scope">{{ sourceRoleLabel(scope.row.sourceRole) }}</template>
                </el-table-column>
                <el-table-column prop="priority" label="优先级" width="90" />
                <el-table-column label="允许回退" width="100">
                  <template #default="scope">
                    {{ scope.row.allowFallback === false ? '否' : '是' }}
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="数据时效">
              <el-table :data="policy.freshnessPolicies" empty-text="尚无数据时效规则" size="small">
                <el-table-column label="数据连接" min-width="160">
                  <template #default="scope">{{ datasourceLabel(scope.row.datasourceId) }}</template>
                </el-table-column>
                <el-table-column prop="businessDateField" label="业务日期字段" min-width="160" />
                <el-table-column prop="freshnessType" label="更新方式" min-width="130" />
                <el-table-column prop="latencyMinutes" label="允许延迟(分钟)" width="140" />
                <el-table-column prop="timeZone" label="时区" min-width="140" />
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="跨源关系">
              <el-table
                :data="policy.crossSourceRelationships"
                empty-text="尚无跨源关系"
                size="small"
              >
                <el-table-column prop="relationshipCode" label="关系" min-width="150" />
                <el-table-column label="左侧" min-width="210">
                  <template #default="scope">
                    {{ datasourceLabel(scope.row.leftDatasourceId) }} · {{ scope.row.leftModelCode }}.{{
                      scope.row.leftKey
                    }}
                  </template>
                </el-table-column>
                <el-table-column label="右侧" min-width="210">
                  <template #default="scope">
                    {{ datasourceLabel(scope.row.rightDatasourceId) }} · {{ scope.row.rightModelCode }}.{{
                      scope.row.rightKey
                    }}
                  </template>
                </el-table-column>
                <el-table-column prop="cardinality" label="基数" width="130" />
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="结果合并">
              <el-table :data="policy.mergePolicies" empty-text="尚无结果合并规则" size="small">
                <el-table-column prop="policyCode" label="规则" min-width="160" />
                <el-table-column label="合并方式" min-width="170">
                  <template #default="scope">{{ mergeTypeLabel(scope.row.mergeType) }}</template>
                </el-table-column>
                <el-table-column prop="inputGrain" label="输入粒度" min-width="140" />
                <el-table-column prop="maxRows" label="最大行数" width="110" />
                <el-table-column prop="partialFailurePolicy" label="部分失败策略" min-width="160" />
              </el-table>
            </el-tab-pane>
          </el-tabs>
          <el-alert
            v-if="editable"
            class="policy-edit-hint"
            type="info"
            :closable="false"
            show-icon
            title="多数据源策略包含跨来源唯一性、基数、时区与合并上限等安全约束；当前仍通过高级模式完整编辑，并由后端结构校验保护。"
          />
        </el-tab-pane>

        <el-tab-pane label="高级模式" name="advanced">
          <el-alert
            type="warning"
            show-icon
            :closable="false"
            title="高级模式直接编辑完整结构化快照。普通业务口径请优先使用上面的字段级编辑。"
          />
          <el-tabs v-model="activeAdvancedEditor" class="advanced-tabs">
            <el-tab-pane label="业务模型 JSON" name="catalog">
              <el-input
                v-model="catalogJson"
                type="textarea"
                :autosize="{ minRows: 18, maxRows: 36 }"
                spellcheck="false"
                :readonly="!editable"
              />
              <div class="editor-actions">
                <span>保存后仍由服务端完整结构校验；已发布版本不可写。</span>
                <el-button
                  type="primary"
                  :disabled="!editable"
                  :loading="savingCatalog"
                  @click="saveCatalogJson"
                >
                  保存业务模型
                </el-button>
              </div>
            </el-tab-pane>
            <el-tab-pane label="多数据源策略 JSON" name="policy">
              <el-input
                v-model="policyJson"
                type="textarea"
                :autosize="{ minRows: 18, maxRows: 36 }"
                spellcheck="false"
                :readonly="!editable"
              />
              <div class="editor-actions">
                <span>保存时继续验证数据源、字段、时区、唯一性、基数和合并上限。</span>
                <el-button
                  type="primary"
                  :disabled="!editable"
                  :loading="savingPolicy"
                  @click="savePolicyJson"
                >
                  保存多数据源策略
                </el-button>
              </div>
            </el-tab-pane>
          </el-tabs>
        </el-tab-pane>
      </el-tabs>
    </template>

    <el-drawer v-model="editorVisible" :title="editorTitle" size="520px" destroy-on-close>
      <el-alert
        class="editor-alert"
        type="info"
        :closable="false"
        show-icon
        title="这次修改只写入当前 Draft。正式查询版本不会被直接改动。"
      />
      <el-form v-if="editorDraft" label-position="top">
        <el-form-item v-for="field in editorFields" :key="field.key" :label="field.label">
          <el-input
            v-if="field.type === 'textarea'"
            v-model="editorDraft[field.key]"
            type="textarea"
            :rows="field.rows || 3"
            :placeholder="field.placeholder"
          />
          <el-select
            v-else-if="field.type === 'select'"
            v-model="editorDraft[field.key]"
            clearable
            filterable
          >
            <el-option
              v-for="option in field.options || []"
              :key="option"
              :label="option"
              :value="option"
            />
          </el-select>
          <el-input v-else v-model="editorDraft[field.key]" :placeholder="field.placeholder" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingCatalog" @click="saveEditedAsset">
          保存到草稿
        </el-button>
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref, watch } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import datasourceService from '@/services/datasource';
  import {
    semEvoSQLService,
    type MultiSourcePolicySnapshot,
    type SemanticCatalogColumn,
    type SemanticCatalogSnapshot,
    type SemanticProjectVersion,
  } from '@/services/semevosql';
  import {
    datasourceDisplayName,
    mergeTypeLabel,
    sourceRoleLabel,
    versionStatusLabel as sharedVersionStatusLabel,
  } from '@/services/displayLabels';

  type BusinessSection =
    | 'objects'
    | 'metrics'
    | 'dimensions'
    | 'enums'
    | 'time'
    | 'relationships'
    | 'sources'
    | 'advanced';
  type CatalogCollection =
    | 'models'
    | 'columns'
    | 'metrics'
    | 'dimensions'
    | 'enumValues'
    | 'relationships'
    | 'grains'
    | 'rules';
  type EditableAsset = Record<string, unknown>;
  type EditorField = {
    key: string;
    label: string;
    type?: 'input' | 'textarea' | 'select';
    rows?: number;
    placeholder?: string;
    options?: string[];
  };

  const props = defineProps<{
    projectId: number;
    versions: SemanticProjectVersion[];
    canEdit?: boolean;
  }>();
  const selectedVersionId = ref<number>();
  const catalog = ref<SemanticCatalogSnapshot>();
  const policy = ref<MultiSourcePolicySnapshot>();
  const catalogJson = ref('');
  const policyJson = ref('');
  const violations = ref<string[]>([]);
  const datasourceNames = ref<Record<number, string>>({});
  const loading = ref(false);
  const savingCatalog = ref(false);
  const savingPolicy = ref(false);
  const errorMessage = ref('');
  const activeSection = ref<BusinessSection>('objects');
  const activeAdvancedEditor = ref('catalog');
  const editorVisible = ref(false);
  const editorCollection = ref<CatalogCollection>();
  const editorIndex = ref(-1);
  const editorCreating = ref(false);
  const editorDraft = ref<EditableAsset>();

  const selectedVersion = computed(() =>
    props.versions.find(item => item.id === selectedVersionId.value),
  );
  const editable = computed(
    () => selectedVersion.value?.status === 'DRAFT' && props.canEdit !== false,
  );
  const datasourceLabel = (datasourceId?: number) =>
    (datasourceId ? datasourceNames.value[datasourceId] : undefined) || '未命名数据连接';
  const catalogStats = computed(() => [
    { key: 'objects' as const, label: '业务对象', value: catalog.value?.models.length || 0 },
    { key: 'metrics' as const, label: '指标', value: catalog.value?.metrics.length || 0 },
    { key: 'dimensions' as const, label: '维度', value: catalog.value?.dimensions.length || 0 },
    { key: 'enums' as const, label: '枚举', value: catalog.value?.enumValues.length || 0 },
    {
      key: 'relationships' as const,
      label: '关系',
      value: catalog.value?.relationships.length || 0,
    },
    {
      key: 'sources' as const,
      label: '数据源策略',
      value: policy.value?.authorityRules.length || 0,
    },
  ]);
  const timeColumns = computed(() =>
    (catalog.value?.columns || []).filter(
      column => String(column.role || '').toUpperCase() === 'TIME',
    ),
  );
  const timeRules = computed(() =>
    (catalog.value?.rules || [])
      .map((item, index) => ({ item, index }))
      .filter(({ item }) =>
        /TIME|DATE|PERIOD|RANGE/i.test(
          `${item.ruleType || ''} ${item.ruleCode || ''} ${item.businessName || ''}`,
        ),
      ),
  );

  const editorTitle = computed(() => {
    const editLabels: Record<CatalogCollection, string> = {
      models: '编辑业务对象',
      columns: '编辑业务字段',
      metrics: '编辑指标',
      dimensions: '编辑维度',
      enumValues: '编辑枚举',
      relationships: '编辑关系',
      grains: '编辑业务粒度',
      rules: '编辑业务规则',
    };
    const createLabels: Partial<Record<CatalogCollection, string>> = {
      metrics: '新增指标',
      dimensions: '新增维度',
      enumValues: '新增枚举',
      relationships: '新增关系',
    };
    if (!editorCollection.value) return '编辑业务模型';
    return editorCreating.value
      ? createLabels[editorCollection.value] || editLabels[editorCollection.value]
      : editLabels[editorCollection.value];
  });
  const editorFields = computed<EditorField[]>(() => {
    const fields: Record<CatalogCollection, EditorField[]> = {
      models: [
        { key: 'businessName', label: '业务对象名称' },
        { key: 'description', label: '业务定义', type: 'textarea' },
        { key: 'modelCode', label: '对象编码' },
        { key: 'modelType', label: '对象类型' },
        { key: 'physicalTable', label: '物理表' },
        { key: 'evidence', label: '来源证据', type: 'textarea' },
      ],
      columns: [
        { key: 'businessName', label: '业务名称' },
        { key: 'description', label: '业务说明', type: 'textarea' },
        { key: 'synonyms', label: '同义词' },
        {
          key: 'role',
          label: '字段角色',
          type: 'select',
          options: ['ATTRIBUTE', 'DIMENSION', 'METRIC', 'TIME', 'IDENTIFIER'],
        },
        { key: 'expression', label: '表达式' },
        { key: 'evidence', label: '来源证据', type: 'textarea' },
      ],
      metrics: [
        { key: 'businessName', label: '指标名称' },
        { key: 'description', label: '业务定义', type: 'textarea' },
        { key: 'metricCode', label: '指标编码' },
        { key: 'modelCode', label: '所属业务对象' },
        { key: 'expression', label: '计算方式', type: 'textarea' },
        { key: 'aggregation', label: '聚合方式' },
        { key: 'filterExpression', label: '默认过滤', type: 'textarea' },
        { key: 'timeColumn', label: '默认时间字段' },
        { key: 'unit', label: '单位' },
        { key: 'additiveType', label: '可加性' },
        { key: 'evidence', label: '来源证据', type: 'textarea' },
      ],
      dimensions: [
        { key: 'businessName', label: '维度名称' },
        { key: 'description', label: '业务定义', type: 'textarea' },
        { key: 'dimensionCode', label: '维度编码' },
        { key: 'modelCode', label: '所属业务对象' },
        { key: 'columnName', label: '来源字段' },
        { key: 'expression', label: '表达式' },
        { key: 'dimensionType', label: '维度类型' },
        { key: 'hierarchy', label: '层级' },
        { key: 'evidence', label: '来源证据', type: 'textarea' },
      ],
      enumValues: [
        { key: 'businessName', label: '业务含义' },
        { key: 'valueCode', label: '数据库值' },
        { key: 'modelCode', label: '所属业务对象' },
        { key: 'columnName', label: '来源字段' },
        { key: 'aliases', label: '同义叫法' },
        { key: 'description', label: '说明', type: 'textarea' },
        { key: 'evidence', label: '来源证据', type: 'textarea' },
      ],
      relationships: [
        { key: 'relationshipCode', label: '关系编码' },
        { key: 'sourceModelCode', label: '来源业务对象' },
        { key: 'targetModelCode', label: '目标业务对象' },
        {
          key: 'cardinality',
          label: '基数',
          type: 'select',
          options: ['ONE_TO_ONE', 'ONE_TO_MANY', 'MANY_TO_ONE', 'MANY_TO_MANY'],
        },
        { key: 'joinType', label: '关联方式' },
        { key: 'joinCondition', label: '关联条件', type: 'textarea' },
        { key: 'description', label: '业务说明', type: 'textarea' },
        { key: 'evidence', label: '来源证据', type: 'textarea' },
      ],
      grains: [
        { key: 'description', label: '业务粒度说明', type: 'textarea' },
        { key: 'grainCode', label: '粒度编码' },
        { key: 'modelCode', label: '所属业务对象' },
        { key: 'keyColumns', label: '唯一键字段' },
        { key: 'timeColumn', label: '时间字段' },
        { key: 'uniquenessRule', label: '唯一性规则', type: 'textarea' },
        { key: 'evidence', label: '来源证据', type: 'textarea' },
      ],
      rules: [
        { key: 'businessName', label: '规则名称' },
        { key: 'description', label: '业务说明', type: 'textarea' },
        { key: 'ruleCode', label: '规则编码' },
        { key: 'ruleType', label: '规则类型' },
        { key: 'modelCode', label: '所属业务对象' },
        { key: 'expression', label: '规则表达式', type: 'textarea' },
        { key: 'severity', label: '严重级别' },
        { key: 'evidence', label: '来源证据', type: 'textarea' },
      ],
    };
    return editorCollection.value ? fields[editorCollection.value] : [];
  });

  const selectDefaultVersion = () => {
    if (
      !selectedVersionId.value ||
      !props.versions.some(item => item.id === selectedVersionId.value)
    ) {
      selectedVersionId.value =
        props.versions.find(item => item.status === 'DRAFT')?.id || props.versions[0]?.id;
    }
  };

  const load = async () => {
    if (!selectedVersionId.value) return;
    loading.value = true;
    errorMessage.value = '';
    try {
      const [nextCatalog, nextPolicy, nextViolations, datasources] = await Promise.all([
        semEvoSQLService.semanticCatalog(props.projectId, selectedVersionId.value),
        semEvoSQLService.multiSourcePolicy(props.projectId, selectedVersionId.value),
        semEvoSQLService.multiSourcePolicyViolations(props.projectId, selectedVersionId.value),
        datasourceService.getAllDatasource(),
      ]);
      catalog.value = nextCatalog;
      policy.value = nextPolicy;
      violations.value = nextViolations;
      datasourceNames.value = Object.fromEntries(
        datasources
          .filter(item => item.id)
          .map(item => [item.id as number, datasourceDisplayName(item)]),
      );
      syncJsonEditors();
    } catch (error) {
      errorMessage.value = error instanceof Error ? error.message : '业务模型加载失败';
    } finally {
      loading.value = false;
    }
  };

  const syncJsonEditors = () => {
    catalogJson.value = JSON.stringify(catalog.value, null, 2);
    policyJson.value = JSON.stringify(policy.value, null, 2);
  };

  function parseJson<T>(value: string, label: string): T {
    try {
      return JSON.parse(value) as T;
    } catch (error) {
      throw new Error(
        `${label}不是有效 JSON：${error instanceof Error ? error.message : String(error)}`,
      );
    }
  }

  const saveCatalogSnapshot = async (
    nextCatalog: SemanticCatalogSnapshot,
    successMessage: string,
  ) => {
    if (!selectedVersionId.value || !editable.value) return;
    savingCatalog.value = true;
    try {
      catalog.value = await semEvoSQLService.replaceSemanticCatalog(
        props.projectId,
        selectedVersionId.value,
        nextCatalog,
      );
      catalogJson.value = JSON.stringify(catalog.value, null, 2);
      violations.value = await semEvoSQLService.multiSourcePolicyViolations(
        props.projectId,
        selectedVersionId.value,
      );
      ElMessage.success(successMessage);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '业务模型保存失败');
      throw error;
    } finally {
      savingCatalog.value = false;
    }
  };

  const saveCatalogJson = async () => {
    if (!catalog.value) return;
    try {
      await saveCatalogSnapshot(
        parseJson<SemanticCatalogSnapshot>(catalogJson.value, '业务模型'),
        '业务模型 Draft 已保存',
      );
    } catch {
      // The shared saver has already shown the backend validation error.
    }
  };

  const savePolicyJson = async () => {
    if (!selectedVersionId.value || !editable.value) return;
    savingPolicy.value = true;
    try {
      policy.value = await semEvoSQLService.replaceMultiSourcePolicy(
        props.projectId,
        selectedVersionId.value,
        parseJson<MultiSourcePolicySnapshot>(policyJson.value, '多数据源策略'),
      );
      policyJson.value = JSON.stringify(policy.value, null, 2);
      violations.value = await semEvoSQLService.multiSourcePolicyViolations(
        props.projectId,
        selectedVersionId.value,
      );
      ElMessage.success('多数据源策略 Draft 已保存');
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '多数据源策略保存失败');
    } finally {
      savingPolicy.value = false;
    }
  };

  const newAssetDefaults = (collection: CatalogCollection): EditableAsset => {
    const modelCodes = (catalog.value?.models || [])
      .map(item => text(item.modelCode))
      .filter(Boolean);
    const base = {
      projectId: props.projectId,
      projectVersionId: selectedVersionId.value,
      status: 'ENABLED',
      evidence: '管理员通过业务模型结构化编辑',
    };
    if (collection === 'metrics') {
      return {
        ...base,
        modelCode: modelCodes[0] || '',
        aggregation: 'SUM',
        additiveType: 'ADDITIVE',
      };
    }
    if (collection === 'dimensions') {
      return { ...base, modelCode: modelCodes[0] || '', dimensionType: 'CATEGORICAL' };
    }
    if (collection === 'enumValues') {
      return { ...base, modelCode: modelCodes[0] || '', sortOrder: 0 };
    }
    if (collection === 'relationships') {
      return {
        ...base,
        sourceModelCode: modelCodes[0] || '',
        targetModelCode: modelCodes[1] || modelCodes[0] || '',
        cardinality: 'MANY_TO_ONE',
        joinType: 'LEFT',
      };
    }
    return base;
  };

  const addAsset = (collection: CatalogCollection) => {
    if (!catalog.value || !editable.value) return;
    editorCollection.value = collection;
    editorIndex.value = -1;
    editorCreating.value = true;
    editorDraft.value = newAssetDefaults(collection);
    editorVisible.value = true;
  };

  const editAsset = (collection: CatalogCollection, index: number) => {
    if (!catalog.value || !editable.value) return;
    const asset = catalog.value[collection][index] as EditableAsset | undefined;
    if (!asset) return;
    editorCollection.value = collection;
    editorIndex.value = index;
    editorCreating.value = false;
    editorDraft.value = JSON.parse(JSON.stringify(asset)) as EditableAsset;
    editorVisible.value = true;
  };

  const editColumn = (column: SemanticCatalogColumn) => {
    const index = catalog.value?.columns.indexOf(column) ?? -1;
    if (index >= 0) editAsset('columns', index);
  };

  const toggleAssetStatus = async (collection: CatalogCollection, index: number) => {
    if (!catalog.value || !editable.value) return;
    const current = catalog.value[collection][index] as EditableAsset | undefined;
    if (!current) return;
    const disabled = text(current.status) === 'DISABLED';
    if (!disabled) {
      try {
        await ElMessageBox.confirm(
          '停用后，新查询不会再把这个资产作为可用业务语义。已有正式版本不会被直接修改。是否继续？',
          '停用业务资产',
          { type: 'warning', confirmButtonText: '确认停用', cancelButtonText: '取消' },
        );
      } catch (error) {
        if (error === 'cancel' || error === 'close') return;
        throw error;
      }
    }
    const next = JSON.parse(JSON.stringify(catalog.value)) as SemanticCatalogSnapshot;
    const nextAsset = (next[collection] as EditableAsset[])[index];
    if (!nextAsset) return;
    nextAsset.status = disabled ? 'ENABLED' : 'DISABLED';
    try {
      await saveCatalogSnapshot(
        next,
        disabled ? '业务资产已恢复到 Draft' : '业务资产已在 Draft 中停用',
      );
    } catch {
      // Backend validation message has already been shown.
    }
  };

  const saveEditedAsset = async () => {
    if (!catalog.value || !editorCollection.value || !editorDraft.value) return;
    const next = JSON.parse(JSON.stringify(catalog.value)) as SemanticCatalogSnapshot;
    const collection = next[editorCollection.value] as EditableAsset[];
    if (editorCreating.value) collection.push(editorDraft.value);
    else if (editorIndex.value >= 0) collection[editorIndex.value] = editorDraft.value;
    else return;
    try {
      await saveCatalogSnapshot(next, `${editorTitle.value}已保存到草稿`);
      editorVisible.value = false;
      editorCreating.value = false;
    } catch {
      // Keep the drawer open so the user can correct backend validation errors.
    }
  };

  const text = (value: unknown) => (value == null ? '' : String(value));
  const assetStatusLabel = (value: unknown) => (text(value) === 'DISABLED' ? '已停用' : '可用');
  const assetKey = (asset: Record<string, unknown>, index: number) =>
    text(asset.id) ||
    text(asset.metricCode) ||
    text(asset.dimensionCode) ||
    text(asset.relationshipCode) ||
    text(asset.ruleCode) ||
    `${index}`;
  const countForModel = (collection: 'metrics' | 'dimensions', modelCode: unknown) =>
    catalog.value?.[collection].filter(item => item.modelCode === modelCode).length || 0;
  const dimensionsForModel = (modelCode: unknown) => {
    const names = (catalog.value?.dimensions || [])
      .filter(item => item.modelCode === modelCode)
      .map(item => text(item.businessName) || text(item.dimensionCode))
      .filter(Boolean);
    return names.length ? names.slice(0, 8).join(' / ') : '未定义';
  };
  const businessObjectName = (modelCode: unknown) => {
    const model = catalog.value?.models.find(item => item.modelCode === modelCode);
    const code = text(modelCode);
    const businessName = text(model?.businessName);
    return businessName && businessName !== code ? businessName : '业务对象';
  };
  const evidenceLabel = (value: unknown) => {
    const raw = text(value);
    if (/database-schema-scan:table=/i.test(raw)) {
      return `数据库结构扫描（物理表：${raw.split('=').slice(1).join('=') || '未标注'}）`;
    }
    if (/^(docs|document|file|upload)\//i.test(raw)) return '业务资料（文档来源）';
    return raw || '未记录来源证据';
  };
  watch(
    () => props.versions,
    () => {
      selectDefaultVersion();
      void load();
    },
    { deep: true },
  );
  onMounted(() => {
    selectDefaultVersion();
    void load();
  });
</script>

<style scoped>
  .semantic-workspace {
    min-height: 420px;
  }
  .toolbar,
  .title-row,
  .section-heading,
  .asset-heading,
  .relationship-flow,
  .editor-actions,
  .stack-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 14px;
  }
  .toolbar,
  .section-heading {
    align-items: flex-start;
  }
  .title-row {
    justify-content: flex-start;
  }
  .toolbar h2 {
    margin: 0;
    color: #0f172a;
  }
  .toolbar p,
  .section-heading p,
  .editor-actions span {
    margin: 6px 0 0;
    color: #64748b;
    line-height: 1.55;
  }
  .version-select {
    width: 280px;
  }
  .section-gap {
    margin: 16px 0;
  }
  .violation-collapse {
    margin-top: 8px;
    border: 0;
  }
  .violation-collapse ul {
    margin: 0;
    padding-left: 20px;
  }
  .readonly-notice {
    display: flex;
    align-items: center;
    gap: 9px;
    margin: 16px 0;
    padding: 11px 14px;
    border: 1px solid #dbeafe;
    border-radius: 10px;
    background: #f8fbff;
    color: #475569;
    font-size: 13px;
  }
  .stats-grid {
    display: grid;
    grid-template-columns: repeat(6, 1fr);
    gap: 10px;
    margin: 18px 0;
  }
  .stats-grid button {
    display: grid;
    gap: 5px;
    padding: 12px;
    border: 1px solid #e2e8f0;
    border-radius: 10px;
    background: #fff;
    text-align: left;
    cursor: pointer;
  }
  .stats-grid button:hover,
  .stats-grid button.active {
    border-color: #bfdbfe;
    background: #f8fbff;
  }
  .stats-grid span {
    color: #64748b;
    font-size: 11px;
  }
  .stats-grid strong {
    color: #0f172a;
    font-size: 20px;
  }
  .business-tabs {
    margin-top: 8px;
  }
  .section-heading {
    margin-bottom: 16px;
  }
  .section-heading h3 {
    margin: 0;
    color: #0f172a;
    font-size: 17px;
  }
  .asset-card-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 14px;
  }
  .asset-card,
  .metric-card,
  .relationship-card,
  .fact-panel {
    padding: 16px;
    border: 1px solid #e2e8f0;
    border-radius: 12px;
    background: #fff;
  }
  .asset-heading {
    align-items: flex-start;
  }
  .asset-heading > div:first-child,
  .table-primary,
  .stack-item > div {
    display: grid;
    gap: 4px;
  }
  .asset-heading strong,
  .table-primary strong,
  .relationship-flow strong,
  .stack-item strong {
    color: #0f172a;
  }
  .asset-heading span,
  .table-primary small,
  .stack-item small {
    color: #64748b;
    line-height: 1.45;
  }
  .asset-facts {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
    margin: 15px 0 0;
  }
  .asset-facts div,
  .metric-detail-grid > div {
    display: grid;
    gap: 3px;
  }
  dt,
  .metric-detail-grid span,
  .evidence-line span {
    color: #64748b;
    font-size: 11px;
  }
  dd {
    margin: 0;
    color: #334155;
    font-weight: 600;
  }
  .evidence-line {
    display: grid;
    gap: 3px;
    margin-top: 12px;
    padding-top: 10px;
    border-top: 1px dashed #e2e8f0;
  }
  .evidence-line small {
    color: #475569;
  }
  .metric-list,
  .relationship-list {
    display: grid;
    gap: 14px;
  }
  .asset-actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .metric-detail-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 14px;
    margin-top: 16px;
  }
  .metric-detail-grid strong,
  .metric-detail-grid code {
    color: #334155;
    line-height: 1.55;
    word-break: break-word;
  }
  .metric-detail-grid code {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
  }
  .metric-detail-grid .wide {
    grid-column: 1 / -1;
  }
  .time-layout {
    display: grid;
    grid-template-columns: 1.2fr 1fr;
    gap: 14px;
  }
  .fact-panel h4 {
    margin: 0 0 12px;
    color: #0f172a;
  }
  .time-rules {
    margin-top: 14px;
  }
  .stack-list {
    display: grid;
    gap: 8px;
  }
  .stack-item {
    align-items: flex-start;
    padding: 9px 0;
    border-bottom: 1px dashed #e2e8f0;
  }
  .stack-item:last-child {
    border-bottom: 0;
  }
  .relationship-card p {
    margin: 10px 0;
    color: #64748b;
  }
  .relationship-flow {
    justify-content: flex-start;
  }
  .relationship-card details {
    margin: 10px 0;
    color: #64748b;
    font-size: 12px;
  }
  .relationship-card details code,
  .relationship-card details small {
    display: block;
    margin-top: 6px;
    color: #475569;
    line-height: 1.5;
  }
  .policy-summary-grid {
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: 10px;
  }
  .policy-summary-grid article {
    display: grid;
    gap: 5px;
    padding: 13px;
    border: 1px solid #e2e8f0;
    border-radius: 10px;
    background: #fbfdff;
  }
  .policy-summary-grid span,
  .policy-summary-grid small {
    color: #64748b;
    font-size: 11px;
  }
  .policy-summary-grid strong {
    color: #0f172a;
    font-size: 20px;
  }
  .policy-tabs,
  .policy-edit-hint,
  .advanced-tabs {
    margin-top: 16px;
  }
  .editor-actions {
    margin-top: 14px;
  }
  .advanced-tabs :deep(textarea) {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
  }
  .editor-alert {
    margin-bottom: 16px;
  }
  @media (max-width: 1050px) {
    .stats-grid {
      grid-template-columns: repeat(3, 1fr);
    }
    .metric-detail-grid,
    .policy-summary-grid {
      grid-template-columns: repeat(2, 1fr);
    }
    .time-layout {
      grid-template-columns: 1fr;
    }
  }
  @media (max-width: 760px) {
    .toolbar,
    .editor-actions {
      align-items: stretch;
      flex-direction: column;
    }
    .version-select {
      width: 100%;
    }
    .stats-grid,
    .asset-card-grid,
    .metric-detail-grid,
    .policy-summary-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
