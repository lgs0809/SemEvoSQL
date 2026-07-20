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
package cn.lgs.semevosql.model;

import java.time.Duration;
import java.util.function.Predicate;
import reactor.util.retry.Retry;

/** One network policy shared by gateway calls and pre-response streaming connections. */
public final class ModelNetworkRetry {
    public static final int MAX_HTTP_ATTEMPTS = 5;

    private ModelNetworkRetry() {}

    public static int retries(int requested) {
        return Math.min(MAX_HTTP_ATTEMPTS - 1, Math.max(0, requested));
    }

    public static Retry backoff(int requestedRetries, Duration initial, Duration maximum, double jitter,
            Predicate<Throwable> retryable) {
        Duration first = initial == null || initial.isNegative() || initial.isZero() ? Duration.ofMillis(1) : initial;
        Duration last = maximum == null || maximum.compareTo(first) < 0 ? first : maximum;
        return Retry.backoff(retries(requestedRetries), first).maxBackoff(last).jitter(jitter)
            .filter(retryable).onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }
}
