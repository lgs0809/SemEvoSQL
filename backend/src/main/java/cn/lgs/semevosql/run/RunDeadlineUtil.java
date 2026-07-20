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
package cn.lgs.semevosql.run;

import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import java.time.Duration;
import reactor.core.publisher.Flux;

/** Shared conversion of the durable absolute Run deadline into a remaining call budget. */
public final class RunDeadlineUtil {

	private RunDeadlineUtil() {
	}

	public static Duration remaining(OverAllState state) {
		Long deadline = StateUtil.getObjectValue(state, cn.lgs.semevosql.constant.Constant.RUN_DEADLINE_EPOCH_MILLIS,
				Long.class, (Long) null);
		return remaining(deadline);
	}

	public static Duration remaining(Long deadlineEpochMillis) {
		if (deadlineEpochMillis == null) {
			return null;
		}
		long remainingMillis = deadlineEpochMillis - System.currentTimeMillis();
		if (remainingMillis <= 0L) {
			throw new RunDeadlineExceededException("Interactive Run deadline exhausted before model invocation");
		}
		return Duration.ofMillis(remainingMillis);
	}

	public static <T> Flux<T> bound(Flux<T> source, OverAllState state) {
		Duration budget = remaining(state);
		return budget == null ? source : source.timeout(budget);
	}

}
