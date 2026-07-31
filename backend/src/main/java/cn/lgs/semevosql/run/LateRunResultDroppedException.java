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

/**
 * Signals that a blocking Graph node returned after its durable Run/Attempt authority had ended.
 *
 * <p>This exception is a control fence, not a new Run failure. The terminal or superseding attempt
 * remains authoritative and the late result must be discarded without business side effects.</p>
 */
public class LateRunResultDroppedException extends IllegalStateException {

	public LateRunResultDroppedException(String message) {
		super(message);
	}
}
