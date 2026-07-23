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
package cn.lgs.semevosql.service.file.impls;

import cn.lgs.semevosql.properties.FileStorageProperties;
import cn.lgs.semevosql.service.file.FileStorageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@AllArgsConstructor
public class LocalFileStorageServiceImpl implements FileStorageService {

	private final FileStorageProperties fileStorageProperties;

	@Override
	public Mono<String> storeFile(FilePart filePart, String subPath) {
		String filename = UUID.randomUUID() + safeExtension(filePart.filename());

		String storagePath = buildStoragePath(subPath, filename);

		Path filePath = fileStorageProperties.getLocalBasePath().resolve(storagePath);

		checkPathSecurity(filePath);

		return Mono.fromCallable(() -> {
			Path uploadDir = filePath.getParent();
			if (!Files.exists(uploadDir)) {
				Files.createDirectories(uploadDir);
			}
			checkPathSecurity(filePath);
			return filePath;
		}).subscribeOn(Schedulers.boundedElastic()).flatMap(filePart::transferTo).then(Mono.fromCallable(() -> {
			log.info("文件存储成功: {}", storagePath);
			return storagePath;
		}));
	}

	@Override
	public String storeFile(MultipartFile file, String subPath) {
		try {
			String filename = UUID.randomUUID() + safeExtension(file.getOriginalFilename());

			String storagePath = buildStoragePath(subPath, filename);

			Path filePath = fileStorageProperties.getLocalBasePath().resolve(storagePath);

			checkPathSecurity(filePath);

			Path uploadDir = filePath.getParent();
			if (!Files.exists(uploadDir)) {
				Files.createDirectories(uploadDir);
			}
			checkPathSecurity(filePath);
			Files.copy(file.getInputStream(), filePath);

			log.info("文件存储成功: {}", storagePath);
			return storagePath;

		}
		catch (IOException e) {
			log.error("文件存储失败", e);
			throw new RuntimeException("文件存储失败: " + e.getMessage(), e);
		}
	}

	@Override
	public boolean deleteFile(String filePath) {
		try {
			Path fullPath = fileStorageProperties.getLocalBasePath().resolve(filePath);
			checkPathSecurity(fullPath);
			if (Files.exists(fullPath)) {
				Files.deleteIfExists(fullPath);
				log.info("成功删除文件: {}", filePath);
			}
			else {
				// 删除是个等幂的操作，不存在也是当做被删除了
				log.info("文件不存在，跳过删除，视为成功: {}", filePath);
			}
			return true;
		}
		catch (IOException e) {
			log.error("删除文件失败: {}", filePath, e);
			return false;
		}
	}

	@Override
	public Resource getFileResource(String filePath) {
		Path fullPath = fileStorageProperties.getLocalBasePath().resolve(filePath);
		checkPathSecurity(fullPath);
		if (Files.exists(fullPath)) {
			return new FileSystemResource(fullPath);
		}
		else {
			throw new RuntimeException("File is not exist: " + filePath);
		}
	}

	/**
	 * 检查文件访问路径是否安全
	 * @param filePath 文件访问路径
	 */
	private void checkPathSecurity(Path filePath) {
		Path basePath = fileStorageProperties.getLocalBasePath().toAbsolutePath().normalize();
		Path normalized = filePath.toAbsolutePath().normalize();
		if (!normalized.startsWith(basePath)) {
			throw new SecurityException("Invalid file path");
		}
		try {
			if (Files.exists(basePath)) {
				Path realBase = basePath.toRealPath();
				Path existingTarget = nearestExisting(normalized);
				if (existingTarget != null && !existingTarget.toRealPath().startsWith(realBase)) {
					throw new SecurityException("Invalid symbolic-link file path");
				}
			}
		}
		catch (IOException ex) {
			throw new SecurityException("Unable to validate file path", ex);
		}
	}

	private Path nearestExisting(Path path) {
		Path current = path;
		while (current != null && !Files.exists(current)) {
			current = current.getParent();
		}
		return current;
	}

	private String safeExtension(String filename) {
		if (!StringUtils.hasText(filename)) {
			return "";
		}
		String normalized = filename.replace('\\', '/');
		String baseName = normalized.substring(normalized.lastIndexOf('/') + 1);
		int dot = baseName.lastIndexOf('.');
		if (dot < 0 || dot == baseName.length() - 1) {
			return "";
		}
		String extension = baseName.substring(dot + 1).toLowerCase();
		return extension.matches("[a-z0-9]{1,16}") ? "." + extension : "";
	}

	/**
	 * 构建本地存储路径
	 */
	private String buildStoragePath(String subPath, String filename) {
		StringBuilder pathBuilder = new StringBuilder();

		if (StringUtils.hasText(fileStorageProperties.getPathPrefix())) {
			pathBuilder.append(fileStorageProperties.getPathPrefix()).append("/");
		}

		if (StringUtils.hasText(subPath)) {
			pathBuilder.append(subPath).append("/");
		}

		pathBuilder.append(filename);

		return pathBuilder.toString();
	}

}
