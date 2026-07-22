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
package cn.lgs.semevosql.clarification;

/** Quorum affects first publication only; it never authorizes changing a public definition. */
public final class ProjectDefinitionPublicationPolicy {
    public record Evidence(int authorizedSources, int validUsers, int validUses, boolean structureReady,
            boolean alignmentReviewed, boolean publicAssetExists, boolean conflict, boolean dependencyValid) {}
    public record Decision(String lifecycle, boolean thresholdReached, boolean automaticPublicationAllowed,
            String blockedReason, boolean administratorMayApprove) {}

    public static Decision assess(Evidence e) {
        boolean threshold = e.validUsers() >= 3 && e.validUses() >= 5;
        if (e.authorizedSources() == 0)
            return new Decision("ACCUMULATING", threshold, false, "SHARING_WITHDRAWN", false);
        if (!e.dependencyValid())
            return new Decision("NEEDS_ADMIN_REVIEW", threshold, false, "DEPENDENCY_INVALID", false);
        if (!e.structureReady())
            return new Decision("ACCUMULATING", threshold, false, "STRUCTURE_PENDING", false);
        if (!e.alignmentReviewed())
            return new Decision("ACCUMULATING", threshold, false, "PUBLIC_ALIGNMENT_PENDING", false);
        if (e.publicAssetExists() || e.conflict())
            return new Decision("NEEDS_ADMIN_REVIEW", threshold, false, "PUBLIC_CHANGE_REQUIRES_ADMIN", true);
        return new Decision(threshold ? "READY_FOR_PUBLISH" : "ACCUMULATING", threshold, threshold,
                threshold ? null : "INSUFFICIENT_CONTRIBUTIONS", true);
    }

    private ProjectDefinitionPublicationPolicy() {}
}
