/** Presentation and draft state are scoped to the actual conversation identity. */
export function uniqueConversations<T extends { conversationId: string }>(items: T[]): T[] {
  const seen = new Set<string>();
  return items.filter(item => {
    if (seen.has(item.conversationId)) return false;
    seen.add(item.conversationId);
    return true;
  });
}

export function queryComposerState(context: {
  projectSelected: boolean;
  modelReady: boolean;
  versionReady: boolean;
  busy: boolean;
  needsConfirmation: boolean;
}) {
  if (!context.projectSelected) return { enabled: false, placeholder: '先选择要查询的项目' };
  if (context.needsConfirmation) return { enabled: false, placeholder: '请先完成上方确认，再继续提问' };
  if (context.busy) return { enabled: false, placeholder: '正在处理你的问题，可查看进度或取消本次执行' };
  if (!context.versionReady) return { enabled: false, placeholder: '项目业务模型发布后，即可直接提问' };
  if (!context.modelReady) return { enabled: false, placeholder: '模型服务暂时不可用，历史结果仍可查看' };
  return { enabled: true, placeholder: '直接描述你的问题，例如指标、时间范围或想比较的内容…' };
}

/** Drafts live only in this page instance; never share them between signed-in users. */
export class ConversationDrafts {
  private drafts = new Map<string, string>();
  private key?: string;

  switchTo(projectId: number | undefined, conversationId: string | undefined, current: string) {
    if (this.key) this.drafts.set(this.key, current);
    this.key = projectId ? `${projectId}:${conversationId || 'new'}` : undefined;
    return this.key ? this.drafts.get(this.key) || '' : '';
  }
}
