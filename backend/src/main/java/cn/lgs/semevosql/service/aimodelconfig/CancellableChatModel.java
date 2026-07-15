/*
 * Copyright 2026 the original author or authors.
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
package cn.lgs.semevosql.service.aimodelconfig;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Binds each model subscription to its HTTP exchange, including pre-header retries.
 * Spring AI 1.1.0's stream aggregation can retain upstream subscriptions after cancellation;
 * the transport therefore also observes this explicit, request-local cancellation signal.
 */
final class CancellableChatModel implements ChatModel {
    private static final Object REQUEST_CANCELLATION = new Object();
    private final ChatModel delegate;

    CancellableChatModel(ChatModel delegate) { this.delegate = delegate; }

    @Override public ChatResponse call(Prompt prompt) { return delegate.call(prompt); }
    @Override public ChatOptions getDefaultOptions() { return delegate.getDefaultOptions(); }

    @Override public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.using(() -> Sinks.<Void>empty(),
            signal -> Flux.defer(() -> {
                var finished = new AtomicBoolean();
                return delegate.stream(prompt).doOnNext(response -> {
                    // Usage-only frames have no generation. For tool continuations, a later
                    // content frame resets the prior call's finish marker.
                    if (response.getResult() != null) finished.set(StringUtils.hasText(
                        response.getResult().getMetadata().getFinishReason()));
                }).concatWith(Mono.defer(() -> finished.get() ? Mono.empty()
                    : Mono.error(new TransientAiException("Model stream ended without a finish reason"))))
                    .contextWrite(context -> context.put(REQUEST_CANCELLATION, signal.asMono()));
            }),
            signal -> signal.tryEmitEmpty());
    }

    static Mono<ClientResponse> guardExchange(Mono<ClientResponse> exchange) {
        return Mono.deferContextual(context -> {
            Mono<Void> cancellation = context.getOrDefault(REQUEST_CANCELLATION, Mono.never());
            return exchange.takeUntilOther(cancellation).map(response -> response.mutate()
                .body(body -> body.takeUntilOther(cancellation)).build());
        });
    }
}
