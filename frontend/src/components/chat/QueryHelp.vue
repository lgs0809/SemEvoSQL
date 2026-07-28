<!-- Copyright 2024-2026 the original author or authors. Licensed under the Apache License, Version 2.0. -->
<template>
  <details class="query-help">
    <summary>
      <i class="bi bi-question-circle" aria-hidden="true"></i
      >怎样描述问题和个人口径？
    </summary>
    <div class="help-grid">
      <div>
        <strong>查询数据</strong>
        <p>
          说明你关心的指标、时间范围和比较对象。追问可以引用当前会话中的结果。
        </p>
      </div>
      <div>
        <strong>确认含义</strong>
        <p>
          遇到歧义时直接用业务语言回答，再选择只用于本次、保存给自己或分享为项目建议。
        </p>
      </div>
      <div>
        <strong>修改个人口径</strong>
        <p>
          说出要修改的名称、新的完整含义或使用范围。提交前检查确认内容与历史影响。
        </p>
      </div>
    </div>
    <p class="help-note">
      个人口径与项目正式口径分别保存。分享建议仍需满足贡献与发布条件。
    </p>
  </details>
</template>
<style scoped>
.query-help {
  max-width: 900px;
  margin: 8px auto 0;
  color: #607980;
  font-size: 12px;
}
summary {
  display: flex;
  align-items: center;
  gap: 7px;
  width: fit-content;
  padding: 6px 0;
  color: #52737a;
  cursor: pointer;
  list-style: none;
}
summary::-webkit-details-marker {
  display: none;
}
summary::after {
  content: "+";
  color: #177d73;
  margin-left: 5px;
}
details[open] summary::after {
  content: "−";
}
.help-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-top: 8px;
  padding: 14px;
  border: 1px solid #e0ebe7;
  border-radius: 10px;
  background: #f6faf8;
}
strong {
  color: #2b4e52;
}
p {
  margin: 5px 0 0;
  line-height: 1.75;
}
.help-note {
  padding: 3px 2px 8px;
}
@media (max-width: 700px) {
  .help-grid {
    grid-template-columns: 1fr;
  }
}
</style>
