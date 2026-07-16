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
package cn.lgs.semevosql.semantic.retrieval;

/** A safe publication gate response; internal inference errors stay in server logs. */
public class SemanticIndexNotReadyException extends IllegalStateException {

    private final Long projectId;
    private final Long versionId;
    private final SemanticRetrievalIndexService.IndexReadinessStatus status;
    private final int totalModels;
    private final int readyModels;

    public SemanticIndexNotReadyException(Long projectId, Long versionId,
            SemanticRetrievalIndexService.IndexReadiness readiness) {
        super(readiness.status() == SemanticRetrievalIndexService.IndexReadinessStatus.INDEX_FAILED
            ? "业务模型索引准备失败，草稿已保留。请联系管理员检查模型服务后重新验证。"
            : "业务模型索引正在后台准备，草稿已保留。请稍后刷新并重新验证，通过后才能发布。");
        this.projectId = projectId;
        this.versionId = versionId;
        this.status = readiness.status();
        this.totalModels = readiness.documentCount();
        this.readyModels = readiness.vectorCount();
    }

    public Long projectId() { return projectId; }
    public Long versionId() { return versionId; }
    public SemanticRetrievalIndexService.IndexReadinessStatus status() { return status; }
    public int totalModels() { return totalModels; }
    public int readyModels() { return readyModels; }
}
