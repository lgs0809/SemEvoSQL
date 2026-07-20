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
package cn.lgs.semevosql.learning;

/**
 * Central trust boundary for learned assets.
 *
 * <p>Low-scope observations may be promoted automatically after deterministic validation. Assets
 * that can change project-wide semantics require explicit review or replay-gated publication.
 */
public final class LearningAssetTrustPolicy {

	private LearningAssetTrustPolicy() {
	}

	public enum AssetClass {
		USER_PREFERENCE(PromotionMode.AUTOMATIC),
		VALIDATED_QUERY_CASE(PromotionMode.AUTOMATIC),
		REUSABLE_PATTERN(PromotionMode.REVIEW_REQUIRED),
		PROJECT_ALIAS(PromotionMode.REVIEW_REQUIRED),
		SEMANTIC_CATALOG_CHANGE(PromotionMode.REPLAY_GATED);

		private final PromotionMode minimumPromotionMode;

		AssetClass(PromotionMode minimumPromotionMode) {
			this.minimumPromotionMode = minimumPromotionMode;
		}

		public PromotionMode minimumPromotionMode() {
			return minimumPromotionMode;
		}
	}

	public enum PromotionMode {
		AUTOMATIC(0),
		REVIEW_REQUIRED(1),
		REPLAY_GATED(2);

		private final int strength;

		PromotionMode(int strength) {
			this.strength = strength;
		}
	}

	public static void assertPromotionAllowed(AssetClass assetClass, PromotionMode mode) {
		if (assetClass == null || mode == null) {
			throw new IllegalArgumentException("Learning asset class and promotion mode are required");
		}
		if (mode.strength < assetClass.minimumPromotionMode().strength) {
			throw new IllegalStateException("Learning asset " + assetClass + " requires at least "
					+ assetClass.minimumPromotionMode() + " promotion; requested=" + mode);
		}
	}

	public static void assertAutomaticPromotionAllowed(AssetClass assetClass) {
		assertPromotionAllowed(assetClass, PromotionMode.AUTOMATIC);
	}
}
