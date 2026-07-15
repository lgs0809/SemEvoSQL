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

import java.util.List;

public interface ModelConfigDataService {

	ModelConfig findById(Integer id);

	void switchActiveStatus(Integer id, ModelType type);

	List<ModelConfigDTO> listConfigs();

	ModelConfigDTO getConfigForTest(Integer id);

	void addConfig(ModelConfigDTO dto);

	ModelConfig updateConfigInDb(ModelConfigDTO dto);

	void recordValidation(Integer id, boolean passed);

	void deleteConfig(Integer id);

	ModelConfigDTO getActiveConfigByType(ModelType modelType);

}
