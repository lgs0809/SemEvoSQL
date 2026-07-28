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
package cn.lgs.semevosql.service.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.properties.FileStorageProperties;
import cn.lgs.semevosql.properties.OssStorageProperties;
import cn.lgs.semevosql.service.file.impls.OssFileStorageServiceImpl;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

class OssFileStorageServiceImplTest {

	@Test
	void fileResourceCanBeReadRepeatedlyWithoutReusingConsumedOssStream() throws Exception {
		OssStorageProperties ossProperties = new OssStorageProperties();
		ossProperties.setBucketName("bucket");
		OssFileStorageServiceImpl service = new OssFileStorageServiceImpl(new FileStorageProperties(), ossProperties);
		OSS oss = mock(OSS.class);
		OSSObject first = object("abc");
		OSSObject second = object("abc");
		ObjectMetadata metadata = new ObjectMetadata();
		metadata.setContentLength(3L);
		when(oss.getObject("bucket", "docs/file.txt")).thenReturn(first, second);
		when(oss.getObjectMetadata("bucket", "docs/file.txt")).thenReturn(metadata);
		ReflectionTestUtils.setField(service, "ossClient", oss);

		Resource resource = service.getFileResource("docs/file.txt");

		assertThat(resource.contentLength()).isEqualTo(3L);
		assertThat(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("abc");
		assertThat(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("abc");
	}

	private OSSObject object(String value) {
		OSSObject object = new OSSObject();
		object.setObjectContent(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
		return object;
	}
}
