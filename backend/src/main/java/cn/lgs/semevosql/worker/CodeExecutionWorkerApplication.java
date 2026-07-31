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
package cn.lgs.semevosql.worker;

import cn.lgs.semevosql.properties.CodeExecutorProperties;
import cn.lgs.semevosql.service.code.CodePoolExecutorService;
import cn.lgs.semevosql.service.code.CodePoolExecutorService.TaskRequest;
import cn.lgs.semevosql.service.code.CodePoolExecutorService.TaskResponse;
import cn.lgs.semevosql.service.code.impls.DockerCodePoolExecutorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

/**
 * Minimal internal HTTP runtime for the only process that owns Docker daemon access. It
 * intentionally does not start Spring, database clients, AI models, scheduled jobs, or
 * public SemEvoSQL controllers.
 */
public final class CodeExecutionWorkerApplication implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(CodeExecutionWorkerApplication.class);

	private static final String EXECUTION_PATH = "/internal/code-execution/tasks";

	private static final String HEALTH_PATH = "/actuator/health";

	private final CodeExecutorProperties properties;

	private final CodePoolExecutorService executorService;

	private final ObjectMapper objectMapper;

	private HttpServer server;

	private ThreadPoolExecutor httpExecutor;

	public CodeExecutionWorkerApplication(CodeExecutorProperties properties, CodePoolExecutorService executorService,
			ObjectMapper objectMapper) {
		this.properties = properties;
		this.executorService = executorService;
		this.objectMapper = objectMapper;
		validateProperties(properties);
	}

	public static void run(String[] args) {
		CodeExecutorProperties properties = loadProperties(System.getenv());
		validateProperties(properties);
		CodeExecutionWorkerApplication application = new CodeExecutionWorkerApplication(properties,
				new DockerCodePoolExecutorService(properties), new ObjectMapper());
		int port = integer(System.getenv(), "SERVER_PORT", 8066);
		application.start(port);
		Runtime.getRuntime().addShutdownHook(new Thread(application::close, "semevosql-execution-worker-shutdown"));
	}

	public synchronized int start(int port) {
		if (server != null) {
			throw new IllegalStateException("Execution worker is already running");
		}
		try {
			int threads = Math.max(1, properties.getMaxThreadSize());
			httpExecutor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
					new ArrayBlockingQueue<>(Math.max(1, properties.getThreadQueueSize())), runnable -> {
						Thread thread = new Thread(runnable,
								"semevosql-execution-http-" + THREAD_SEQUENCE.incrementAndGet());
						thread.setDaemon(false);
						return thread;
					}, new ThreadPoolExecutor.CallerRunsPolicy());
			server = HttpServer.create(new InetSocketAddress("0.0.0.0", port),
					Math.max(1, properties.getThreadQueueSize()));
			server.setExecutor(httpExecutor);
			server.createContext(EXECUTION_PATH, this::handleExecution);
			server.createContext(HEALTH_PATH, this::handleHealth);
			server.start();
			int actualPort = server.getAddress().getPort();
			log.info("SemEvoSQL execution worker listening on port {}", actualPort);
			return actualPort;
		}
		catch (IOException ex) {
			close();
			throw new IllegalStateException("Unable to start execution worker", ex);
		}
	}

	private void handleExecution(HttpExchange exchange) throws IOException {
		if (!EXECUTION_PATH.equals(exchange.getRequestURI().getPath())) {
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
			return;
		}
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			write(exchange, 405, TaskResponse.exception("Method not allowed"));
			return;
		}
		if (!authorized(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION))) {
			write(exchange, 401, TaskResponse.exception("Unauthorized request"));
			return;
		}
		long maximumBodyBytes = (long) properties.getMaxCodeBytes() + properties.getMaxInputBytes()
				+ properties.getMaxRequirementBytes() + 65_536L;
		String contentLength = exchange.getRequestHeaders().getFirst(HttpHeaders.CONTENT_LENGTH);
		if (StringUtils.hasText(contentLength) && parseLong(contentLength, maximumBodyBytes + 1) > maximumBodyBytes) {
			write(exchange, 413, TaskResponse.exception("Execution request exceeds the configured limit"));
			return;
		}
		try {
			byte[] payload = readLimited(exchange.getRequestBody(), maximumBodyBytes);
			TaskRequest request = objectMapper.readValue(payload, TaskRequest.class);
			String error = validateRequest(request);
			if (error != null) {
				write(exchange, 400, TaskResponse.exception(error));
				return;
			}
			write(exchange, 200, executorService.runTask(request));
		}
		catch (PayloadTooLargeException ex) {
			write(exchange, 413, TaskResponse.exception(ex.getMessage()));
		}
		catch (Exception ex) {
			log.warn("Rejected malformed execution request: {}", ex.getMessage());
			write(exchange, 400, TaskResponse.exception("Malformed execution request"));
		}
	}

	private void handleHealth(HttpExchange exchange) throws IOException {
		if (!HEALTH_PATH.equals(exchange.getRequestURI().getPath())) {
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
			return;
		}
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(405, -1);
			exchange.close();
			return;
		}
		byte[] payload = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
		exchange.getResponseHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
		exchange.sendResponseHeaders(200, payload.length);
		exchange.getResponseBody().write(payload);
		exchange.close();
	}

	private boolean authorized(String authorization) {
		if (!StringUtils.hasText(authorization)) {
			return false;
		}
		byte[] expected = ("Bearer " + properties.getInternalToken().trim()).getBytes(StandardCharsets.UTF_8);
		byte[] actual = authorization.trim().getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(expected, actual);
	}

	private String validateRequest(TaskRequest request) {
		if (request == null) {
			return "Execution request is required";
		}
		if (size(request.code()) > properties.getMaxCodeBytes()) {
			return "Python source exceeds the configured limit";
		}
		if (size(request.input()) > properties.getMaxInputBytes()) {
			return "Execution input exceeds the configured limit";
		}
		if (size(request.requirement()) > properties.getMaxRequirementBytes()) {
			return "Requirements payload exceeds the configured limit";
		}
		if (StringUtils.hasText(request.requirement())) {
			return "Runtime package installation is disabled; use the pinned SemEvoSQL runner dependencies";
		}
		return null;
	}

	private byte[] readLimited(InputStream input, long limit) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		long total = 0L;
		int read;
		while ((read = input.read(buffer)) >= 0) {
			total += read;
			if (total > limit) {
				throw new PayloadTooLargeException("Execution request exceeds the configured limit");
			}
			output.write(buffer, 0, read);
		}
		return output.toByteArray();
	}

	private void write(HttpExchange exchange, int status, TaskResponse response) throws IOException {
		byte[] payload = objectMapper.writeValueAsBytes(response);
		exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
		exchange.getResponseHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
		exchange.sendResponseHeaders(status, payload.length);
		exchange.getResponseBody().write(payload);
		exchange.close();
	}

	private static void validateProperties(CodeExecutorProperties properties) {
		if (!StringUtils.hasText(properties.getInternalToken()) || properties.getInternalToken().trim().length() < 32) {
			throw new IllegalStateException("Execution worker credential must contain at least 32 characters");
		}
		if (properties.getMaxCodeBytes() <= 0 || properties.getMaxInputBytes() <= 0
				|| properties.getMaxRequirementBytes() <= 0) {
			throw new IllegalStateException("Execution worker payload limits must be positive");
		}
		if (!"semevosql/python-runner:1.0.0".equals(properties.getImageName())) {
			throw new IllegalStateException("Execution worker must use semevosql/python-runner:1.0.0");
		}
		if (!"none".equalsIgnoreCase(properties.getNetworkMode())) {
			throw new IllegalStateException("Execution worker containers must use network mode none");
		}
		if (!Boolean.TRUE.equals(properties.getReadOnlyRootFilesystem())) {
			throw new IllegalStateException("Execution worker containers must use a read-only root filesystem");
		}
		if (!StringUtils.hasText(properties.getRunAsUser()) || properties.getRunAsUser().startsWith("0:")
				|| "0".equals(properties.getRunAsUser())) {
			throw new IllegalStateException("Execution worker containers must not run as root");
		}
		if (properties.getPidsLimit() == null || properties.getPidsLimit() <= 0 || properties.getLimitMemory() == null
				|| properties.getLimitMemory() <= 0 || properties.getCpuCore() == null || properties.getCpuCore() <= 0) {
			throw new IllegalStateException("Execution worker resource limits must be positive");
		}
	}

	@Override
	public synchronized void close() {
		if (server != null) {
			server.stop(1);
			server = null;
		}
		if (httpExecutor != null) {
			httpExecutor.shutdownNow();
			httpExecutor = null;
		}
	}

	private int size(String value) {
		return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
	}

	private long parseLong(String value, long fallback) {
		try {
			return Long.parseLong(value);
		}
		catch (NumberFormatException ex) {
			return fallback;
		}
	}

	static CodeExecutorProperties loadProperties(Map<String, String> environment) {
		CodeExecutorProperties properties = new CodeExecutorProperties();
		properties.setInternalToken(environment.get("SEMEVOSQL_EXECUTION_INTERNAL_TOKEN"));
		properties.setMaxCodeBytes(
				integer(environment, "SEMEVOSQL_EXECUTION_MAX_CODE_BYTES", properties.getMaxCodeBytes()));
		properties.setMaxInputBytes(
				integer(environment, "SEMEVOSQL_EXECUTION_MAX_INPUT_BYTES", properties.getMaxInputBytes()));
		properties.setMaxRequirementBytes(integer(environment, "SEMEVOSQL_EXECUTION_MAX_REQUIREMENT_BYTES",
				properties.getMaxRequirementBytes()));
		properties.setPidsLimit(longValue(environment, "SEMEVOSQL_EXECUTION_PIDS_LIMIT", properties.getPidsLimit()));
		properties
			.setTmpfsSizeMb(integer(environment, "SEMEVOSQL_EXECUTION_TMPFS_SIZE_MB", properties.getTmpfsSizeMb()));
		properties
			.setLimitMemory(longValue(environment, "SEMEVOSQL_EXECUTION_MEMORY_MB", properties.getLimitMemory()));
		properties.setCpuCore(longValue(environment, "SEMEVOSQL_EXECUTION_CPU_CORES", properties.getCpuCore()));
		properties.setRunAsUser(value(environment, "SEMEVOSQL_EXECUTION_RUN_AS_USER", properties.getRunAsUser()));
		properties
			.setNetworkMode(value(environment, "SEMEVOSQL_EXECUTION_NETWORK_MODE", properties.getNetworkMode()));
		properties.setImageName(value(environment, "SEMEVOSQL_EXECUTION_IMAGE", properties.getImageName()));
		properties
			.setCodeTimeout(value(environment, "SEMEVOSQL_EXECUTION_CODE_TIMEOUT", properties.getCodeTimeout()));
		properties.setReadOnlyRootFilesystem(booleanValue(environment, "SEMEVOSQL_EXECUTION_READ_ONLY_ROOT",
				properties.getReadOnlyRootFilesystem()));
		return properties;
	}

	private static String value(Map<String, String> environment, String key, String fallback) {
		String value = environment.get(key);
		return StringUtils.hasText(value) ? value.trim() : fallback;
	}

	private static int integer(Map<String, String> environment, String key, int fallback) {
		try {
			return Integer.parseInt(value(environment, key, String.valueOf(fallback)));
		}
		catch (NumberFormatException ex) {
			throw new IllegalStateException(key + " must be an integer", ex);
		}
	}

	private static long longValue(Map<String, String> environment, String key, long fallback) {
		try {
			return Long.parseLong(value(environment, key, String.valueOf(fallback)));
		}
		catch (NumberFormatException ex) {
			throw new IllegalStateException(key + " must be an integer", ex);
		}
	}

	private static boolean booleanValue(Map<String, String> environment, String key, boolean fallback) {
		return Boolean.parseBoolean(value(environment, key, String.valueOf(fallback)));
	}

	private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

	private static final class PayloadTooLargeException extends IOException {

		private PayloadTooLargeException(String message) {
			super(message);
		}

	}

}
