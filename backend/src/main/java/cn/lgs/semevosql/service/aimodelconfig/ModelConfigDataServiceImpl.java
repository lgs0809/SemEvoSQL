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
package cn.lgs.semevosql.service.aimodelconfig;

import cn.lgs.semevosql.enums.ModelType;
import cn.lgs.semevosql.dto.ModelConfigDTO;
import cn.lgs.semevosql.entity.ModelConfig;
import cn.lgs.semevosql.exception.ModelConfigConflictException;
import cn.lgs.semevosql.exception.ModelConfigNotFoundException;
import cn.lgs.semevosql.mapper.ModelConfigMapper;
import cn.lgs.semevosql.common.SecretCipher;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static cn.lgs.semevosql.converter.ModelConfigConverter.toDTO;
import static cn.lgs.semevosql.converter.ModelConfigConverter.toEntity;

@Slf4j
@Service
@AllArgsConstructor
public class ModelConfigDataServiceImpl implements ModelConfigDataService {

	private final ModelConfigMapper modelConfigMapper;

	private final SecretCipher secretCipher;

	@Override
	public ModelConfig findById(Integer id) {
		return modelConfigMapper.findById(id);
	}

	@Transactional(rollbackFor = Exception.class)
	@Override
	public void switchActiveStatus(Integer id, ModelType type) {
		ModelConfig entity = modelConfigMapper.findById(id);
		if (entity == null) {
			throw new ModelConfigNotFoundException(id);
		}
		if (type == null || entity.getModelType() == null) {
			throw new ModelConfigConflictException("模型配置类型无效");
		}
		if (!type.equals(entity.getModelType())) {
			throw new ModelConfigConflictException("模型配置类型与激活请求不一致");
		}

		modelConfigMapper.deactivateOthers(type.getCode(), id);
		entity.setIsActive(true);
		entity.setUpdatedTime(LocalDateTime.now());
		if (modelConfigMapper.updateById(entity) == 0) {
			throw new RuntimeException("Model configuration activation update failed");
		}
	}

	@Override
	public List<ModelConfigDTO> listConfigs() {
		return modelConfigMapper.findAll().stream().map(this::toPublicDto).collect(Collectors.toList());
	}

	@Override
	public ModelConfigDTO getConfigForTest(Integer id) {
		ModelConfig entity = modelConfigMapper.findById(id);
		if (entity == null) {
			throw new ModelConfigNotFoundException(id);
		}
		ModelConfigDTO dto = toDTO(entity);
		dto.setApiKey(secretCipher.decrypt(entity.getApiKey()));
		dto.setProxyPassword(secretCipher.decrypt(entity.getProxyPassword()));
		return dto;
	}

	@Override
	public void addConfig(ModelConfigDTO dto) {
		clean(dto);
		ModelConfig entity = toEntity(dto);
		entity.setApiKey(secretCipher.encryptPlaintext(entity.getApiKey()));
		entity.setProxyPassword(secretCipher.encryptPlaintext(entity.getProxyPassword()));
		modelConfigMapper.insert(entity);
	}

	private void clean(ModelConfigDTO dto) {
		dto.setModelName(dto.getModelName().trim());
		dto.setBaseUrl(dto.getBaseUrl().trim());
		if (dto.getApiKey() != null) {
			dto.setApiKey(dto.getApiKey().trim());
		}
		if (dto.getProxyPassword() != null) {
			dto.setProxyPassword(dto.getProxyPassword().trim());
		}
		if (dto.getCompletionsPath() != null) {
			dto.setCompletionsPath(dto.getCompletionsPath().trim());
		}
		if (dto.getEmbeddingsPath() != null) {
			dto.setEmbeddingsPath(dto.getEmbeddingsPath().trim());
		}
		if (dto.getRerankPath() != null) {
			dto.setRerankPath(dto.getRerankPath().trim());
		}
	}

	/**
	 * 更新配置到数据库 (不处理热切换) 返回更新后的实体，以便上层业务判断是否需要刷新内存
	 */
	@Transactional(rollbackFor = Exception.class)
	@Override
	public ModelConfig updateConfigInDb(ModelConfigDTO dto) {
		clean(dto);
		// 1. 查旧数据
		ModelConfig entity = modelConfigMapper.findById(dto.getId());
		if (entity == null) {
			throw new ModelConfigNotFoundException(dto.getId());
		}

		// 不准更改模型类型
		if (!entity.getModelType().getCode().equals(dto.getModelType())) {
			throw new ModelConfigConflictException("模型类型不允许修改");
		}

		// 2. 合并非敏感字段；凭据只在显式提交新值时替换
		mergeDtoToEntity(dto, entity);
		if (StringUtils.hasText(dto.getApiKey())) {
			entity.setApiKey(secretCipher.encryptPlaintext(dto.getApiKey()));
		}
		if (StringUtils.hasText(dto.getProxyPassword())) {
			entity.setProxyPassword(secretCipher.encryptPlaintext(dto.getProxyPassword()));
		}
		entity.setUpdatedTime(LocalDateTime.now());

		// 3. 更新数据库。任何配置修改都会使旧连接验证失效；如果修改的是当前模型，
		// 同时退出 active，避免未重新验证的配置继续被运行时加载。
		if (Boolean.TRUE.equals(entity.getIsActive())) {
			entity.setIsActive(false);
		}
		modelConfigMapper.updateById(entity);
		modelConfigMapper.invalidateValidation(entity.getId());
		entity.setValidationStatus("UNVERIFIED");
		entity.setLastValidationTime(null);

		return entity;
	}

	private static void mergeDtoToEntity(ModelConfigDTO dto, ModelConfig oldEntity) {
		oldEntity.setProvider(dto.getProvider());
		oldEntity.setBaseUrl(dto.getBaseUrl());
		oldEntity.setModelName(dto.getModelName());
		oldEntity.setTemperature(dto.getTemperature());
		oldEntity.setMaxTokens(dto.getMaxTokens()); // 新增字段
		oldEntity.setCompletionsPath(dto.getCompletionsPath());
		oldEntity.setEmbeddingsPath(dto.getEmbeddingsPath());
		oldEntity.setEmbeddingDimensions(dto.getEmbeddingDimensions());
		oldEntity.setRerankPath(dto.getRerankPath());
		oldEntity.setRequestTimeoutSeconds(dto.getRequestTimeoutSeconds());
		oldEntity.setUpdatedTime(LocalDateTime.now());
		oldEntity.setProxyEnabled(dto.getProxyEnabled());
		oldEntity.setProxyHost(dto.getProxyHost());
		oldEntity.setProxyPort(dto.getProxyPort());
		oldEntity.setProxyUsername(dto.getProxyUsername());
	}

	@Override
	public void recordValidation(Integer id, boolean passed) {
		if (id == null || modelConfigMapper.recordValidation(id, passed ? "PASSED" : "FAILED") != 1) {
			throw new ModelConfigNotFoundException(id);
		}
	}

	@Override
	public void deleteConfig(Integer id) {
		// 1. 先查询是否存在
		ModelConfig entity = modelConfigMapper.findById(id);
		if (entity == null) {
			throw new ModelConfigNotFoundException(id);
		}

		// 2. 如果是激活状态，禁止删除
		if (Boolean.TRUE.equals(entity.getIsActive())) {
			throw new ModelConfigConflictException("该配置当前正在使用中，无法删除！请先激活其他配置，再进行删除操作。");
		}

		// 3. 执行删除逻辑
		entity.setIsDeleted(1);
		entity.setUpdatedTime(LocalDateTime.now());
		int updated = modelConfigMapper.updateById(entity);
		if (updated == 0) {
			throw new RuntimeException("删除失败");
		}
	}

	@Override
	public ModelConfigDTO getActiveConfigByType(ModelType modelType) {
		ModelConfig entity = modelConfigMapper.selectActiveByType(modelType.getCode());
		if (entity == null) {
			log.warn("Activation model configuration of type [{}] not found, attempting to downgrade...", modelType);
			return null;
		}
		ModelConfigDTO dto = toDTO(entity);
		dto.setApiKey(secretCipher.decrypt(entity.getApiKey()));
		dto.setProxyPassword(secretCipher.decrypt(entity.getProxyPassword()));
		return dto;
	}

	private ModelConfigDTO toPublicDto(ModelConfig entity) {
		ModelConfigDTO dto = toDTO(entity);
		dto.setApiKey(null);
		dto.setApiKeyHint(secretCipher.hint(entity.getApiKey()));
		dto.setProxyPassword(null);
		return dto;
	}

}
