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
package cn.lgs.semevosql.service.schema;

import cn.lgs.semevosql.bo.DbConfigBO;
import cn.lgs.semevosql.dto.schema.ColumnDTO;
import cn.lgs.semevosql.dto.schema.SchemaDTO;
import cn.lgs.semevosql.dto.schema.TableDTO;
import cn.lgs.semevosql.enums.BizDataSourceTypeEnum;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/** Builds the bounded advanced-execution schema directly from published Catalog documents. */
@Service
public class SchemaServiceImpl implements SchemaService {

	@Override
	public void buildSchemaFromDocuments(List<Document> columnDocuments, List<Document> tableDocuments,
			SchemaDTO schemaDTO) {
		List<TableDTO> tables = tableDocuments == null ? new ArrayList<>()
				: new ArrayList<>(tableDocuments.stream().map(this::table).toList());
		if (columnDocuments != null) {
			for (Document document : columnDocuments) {
				attachColumn(document, tables);
			}
		}
		schemaDTO.setTable(tables);
		Set<String> relationships = new LinkedHashSet<>();
		if (tableDocuments != null) {
			for (Document document : tableDocuments) {
				Object raw = document.getMetadata().get("foreignKey");
				if (raw == null || raw.toString().isBlank()) {
					continue;
				}
				Arrays.stream(raw.toString().split("、"))
					.map(String::trim)
					.filter(value -> !value.isBlank())
					.forEach(relationships::add);
			}
		}
		schemaDTO.setForeignKeys(new ArrayList<>(relationships));
	}

	@Override
	public void extractDatabaseName(SchemaDTO schemaDTO, DbConfigBO dbConfig) {
		if (BizDataSourceTypeEnum.isMysqlDialect(dbConfig.getDialectType())) {
			String url = Objects.toString(dbConfig.getUrl(), "");
			int slash = url.lastIndexOf('/');
			if (slash >= 0 && slash + 1 < url.length()) {
				String database = url.substring(slash + 1).split("[?;]", 2)[0];
				if (StringUtils.isNotBlank(database)) {
					schemaDTO.setName(database);
				}
			}
		}
		else if (BizDataSourceTypeEnum.isPgDialect(dbConfig.getDialectType())) {
			schemaDTO.setName(dbConfig.getSchema());
		}
	}

	private TableDTO table(Document document) {
		Map<String, Object> metadata = document.getMetadata();
		TableDTO table = new TableDTO();
		table.setName(Objects.toString(metadata.get("name"), ""));
		table.setDescription(Objects.toString(metadata.get("description"), ""));
		Object primaryKey = metadata.get("primaryKey");
		if (primaryKey instanceof List<?> values) {
			table.setPrimaryKeys(values.stream().filter(Objects::nonNull).map(Object::toString).toList());
		}
		else if (primaryKey != null && StringUtils.isNotBlank(primaryKey.toString())) {
			table.setPrimaryKeys(List.of(primaryKey.toString()));
		}
		return table;
	}

	private void attachColumn(Document document, List<TableDTO> tables) {
		Map<String, Object> metadata = document.getMetadata();
		String tableName = Objects.toString(metadata.get("tableName"), "");
		TableDTO table = tables.stream().filter(value -> Objects.equals(value.getName(), tableName)).findFirst().orElse(null);
		if (table == null) {
			return;
		}
		ColumnDTO column = new ColumnDTO();
		column.setName(Objects.toString(metadata.get("name"), ""));
		column.setDescription(Objects.toString(metadata.get("description"), ""));
		column.setType(Objects.toString(metadata.get("type"), ""));
		Object samples = metadata.get("samples");
		if (samples instanceof List<?> values) {
			column.setData(values.stream().filter(Objects::nonNull).map(Object::toString).toList());
		}
		table.getColumn().add(column);
	}

}
