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
package cn.lgs.semevosql.controller;

import cn.lgs.semevosql.dto.ModelConfigDTO;
import cn.lgs.semevosql.enums.ModelType;
import cn.lgs.semevosql.properties.ModelClientProperties;
import cn.lgs.semevosql.service.aimodelconfig.ModelConfigDataService;
import cn.lgs.semevosql.service.aimodelconfig.ModelConfigOpsService;
import cn.lgs.semevosql.vo.ApiResponse;
import cn.lgs.semevosql.vo.ModelCheckVo;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@AllArgsConstructor
@RestController
@RequestMapping("/api/model-config")
public class ModelConfigController {

	private final ModelConfigDataService modelConfigDataService;

	private final ModelConfigOpsService modelConfigOpsService;

	private final ModelClientProperties modelClientProperties;

	@GetMapping("/list")
	public ApiResponse<List<ModelConfigDTO>> list() {
		return ApiResponse.success("获取模型配置列表成功", modelConfigDataService.listConfigs());
	}

	@PostMapping("/add")
	public ApiResponse<String> add(@Valid @RequestBody ModelConfigDTO config) {
		modelConfigDataService.addConfig(config);
		return ApiResponse.success("配置已保存");
	}

	@PutMapping("/update")
	public ApiResponse<String> update(@Valid @RequestBody ModelConfigDTO config) {
		modelConfigOpsService.updateAndRefresh(config);
		return ApiResponse.success("配置已更新，请重新验证可用性后再设为当前模型");
	}

	@DeleteMapping("/{id}")
	public ApiResponse<String> delete(@PathVariable Integer id) {
		modelConfigDataService.deleteConfig(id);
		return ApiResponse.success("配置已删除");
	}

	@PostMapping("/activate/{id}")
	public ApiResponse<String> activate(@PathVariable Integer id) {
		modelConfigOpsService.activateConfig(id);
		return ApiResponse.success("模型切换成功！");
	}

	/**
	 * 接收前端表单里的配置参数，尝试发起一次真实调用。
	 */
	@PostMapping("/test")
	public Mono<ApiResponse<String>> testConnection(@Valid @RequestBody ModelConfigDTO config) {
		return Mono.fromCallable(() -> {
			ModelConfigDTO effective = config.getId() == null ? config
					: modelConfigDataService.getConfigForTest(config.getId());
			try {
				modelConfigOpsService.testConnection(effective);
				if (config.getId() != null) {
					modelConfigDataService.recordValidation(config.getId(), true);
				}
				return ApiResponse.<String>success("连接测试成功，当前配置已验证可用。");
			}
			catch (RuntimeException ex) {
				if (config.getId() != null) {
					modelConfigDataService.recordValidation(config.getId(), false);
				}
				throw ex;
			}
		}).subscribeOn(Schedulers.boundedElastic());
	}

	/** 返回各模型能力的当前验证状态。Chat、Embedding、Rerank 均为标准问数链路的必需能力。 */
	@GetMapping("/check-ready")
	public ApiResponse<ModelCheckVo> checkReady() {
		ModelConfigDTO chatModel = modelConfigDataService.getActiveConfigByType(ModelType.CHAT);
		ModelConfigDTO embeddingModel = modelConfigDataService.getActiveConfigByType(ModelType.EMBEDDING);
		ModelConfigDTO rerankModel = modelConfigDataService.getActiveConfigByType(ModelType.RERANK);
		String chatStatus = readinessStatus(chatModel);
		String embeddingStatus = readinessStatus(embeddingModel);
		String rerankStatus = readinessStatus(rerankModel);
		boolean chatModelReady = "VERIFIED".equals(chatStatus);
		boolean embeddingModelReady = "VERIFIED".equals(embeddingStatus);
		boolean rerankModelReady = "VERIFIED".equals(rerankStatus);
		return ApiResponse.success("模型配置检查完成", ModelCheckVo.builder()
			.chatModelConfigured(chatModel != null)
			.chatModelReady(chatModelReady)
			.chatModelStatus(chatStatus)
			.chatModelLastValidationTime(chatModel == null ? null : chatModel.getLastValidationTime())
			.embeddingModelConfigured(embeddingModel != null)
			.embeddingModelReady(embeddingModelReady)
			.embeddingModelStatus(embeddingStatus)
			.embeddingModelLastValidationTime(embeddingModel == null ? null : embeddingModel.getLastValidationTime())
			.rerankModelConfigured(rerankModel != null)
			.rerankModelReady(rerankModelReady)
			.rerankModelStatus(rerankStatus)
			.rerankModelLastValidationTime(rerankModel == null ? null : rerankModel.getLastValidationTime())
			.ready(chatModelReady && embeddingModelReady && rerankModelReady)
			.build());
	}

	private String readinessStatus(ModelConfigDTO model) {
		if (model == null) {
			return "NOT_CONFIGURED";
		}
		if ("FAILED".equals(model.getValidationStatus())) {
			return "UNAVAILABLE";
		}
		if (!"PASSED".equals(model.getValidationStatus()) || model.getLastValidationTime() == null) {
			return "CONFIGURED";
		}
		LocalDateTime staleBefore = LocalDateTime.now().minus(modelClientProperties.getValidationFreshness());
		return model.getLastValidationTime().isBefore(staleBefore) ? "STALE" : "VERIFIED";
	}

}
