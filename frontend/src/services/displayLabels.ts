/*
 * User-facing labels for values that are intentionally kept technical in the
 * API and persistence layers. Keeping this mapping in one place prevents
 * lifecycle codes from leaking into the console while leaving project-owned
 * names and descriptions untouched.
 */

type DisplayDatasource = {
  id?: number;
  name?: string;
  description?: string;
};

export const datasourceTypeLabel = (value?: string) => {
  const normalized = String(value || '').toUpperCase();
  if (normalized === 'MYSQL') return 'MySQL';
  if (normalized === 'POSTGRES' || normalized === 'POSTGRESQL') return 'PostgreSQL';
  return value || '未指定';
};

export const datasourceDisplayName = (
  datasource?: DisplayDatasource,
) => {
  const name = datasource?.name?.trim();
  if (!name) return datasource?.id ? `数据连接 ${datasource.id}` : '未命名数据连接';
  return name;
};

export const datasourceDescriptionLabel = (
  datasource?: DisplayDatasource,
) => {
  const description = datasource?.description?.trim();
  return description;
};

export const datasourceHostLabel = (host?: string) =>
  host === 'host.docker.internal' ? '本机 Docker 网络' : host || '-';

export const versionStatusLabel = (value?: string) =>
  ({
    DRAFT: '草稿',
    VALIDATED: '已验证',
    READY: '验证通过',
    PUBLISHED: '已发布',
    ACTIVE: '当前使用',
    ARCHIVED: '已归档',
    DEPRECATED: '已停用',
  })[String(value || '').toUpperCase()] || value || '未知状态';

export const sourceRoleLabel = (value?: string) =>
  ({
    PRIMARY: '主要来源',
    SECONDARY: '辅助来源',
    FALLBACK: '备用来源',
    AUTHORITY: '权威来源',
  })[String(value || '').toUpperCase()] || value || '未标注';

export const mergeTypeLabel = (value?: string) =>
  ({
    UNION: '纵向合并',
    UNION_ALL: '纵向追加',
    JOIN: '关联合并',
    AGGREGATE: '汇总合并',
  })[String(value || '').toUpperCase()] || value || '未标注';

export const businessDomainNameLabel = (value?: string, code?: string) => {
  void code;
  return value || '未命名业务域';
};

export const businessDomainCodeLabel = (value?: string, domainName?: string) => {
  void domainName;
  return value || '-';
};

export const responsibilityLabel = (value?: string, domainName?: string, domainCode?: string) => {
  void domainName;
  void domainCode;
  return value || '未填写数据职责';
};
