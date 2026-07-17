/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.lgs.semevosql.service.graph.Context;

import static cn.lgs.semevosql.constant.Constant.CONVERSATION_CONTEXT_ENVELOPE;
import static cn.lgs.semevosql.constant.Constant.MULTI_TURN_CONTEXT;

import cn.lgs.semevosql.service.graph.Context.ConversationContextPromptRenderer.Stage;
import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;

/** Resolves a stage-specific context view with durable string fallback. */
public final class ConversationContextStateView {

	private ConversationContextStateView() {
	}

	public static String render(OverAllState state, ConversationContextPromptRenderer renderer, Stage stage) {
		ConversationContextEnvelope envelope = StateUtil.getObjectValue(state, CONVERSATION_CONTEXT_ENVELOPE,
				ConversationContextEnvelope.class, (ConversationContextEnvelope) null);
		return envelope == null ? StateUtil.getStringValue(state, MULTI_TURN_CONTEXT, "(无)")
				: renderer.render(envelope, stage);
	}

}
