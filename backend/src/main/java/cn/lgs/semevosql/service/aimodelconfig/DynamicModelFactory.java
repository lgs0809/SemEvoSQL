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

import cn.lgs.semevosql.dto.ModelConfigDTO;
import cn.lgs.semevosql.properties.ModelClientProperties;
import cn.lgs.semevosql.semantic.retrieval.RerankModel;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;
import reactor.util.retry.Retry;

@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicModelFactory {

	private static final String DEFAULT_CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

	private final ModelClientProperties modelClientProperties;

	/**
	 * 统一使用 OpenAiChatModel，通过 baseUrl 实现多厂商兼容
	 */
	public ChatModel createChatModel(ModelConfigDTO config) {

		log.info("Creating NEW ChatModel instance. Provider: {}, Model: {}, BaseUrl: {}", config.getProvider(),
				config.getModelName(), config.getBaseUrl());
		// 1. 验证参数
		checkBasic(config);

		// 2. 构建 OpenAiApi (核心通讯对象)
		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		ModelEndpointResolver.Endpoint endpoint = ModelEndpointResolver.resolve(config.getBaseUrl(),
				config.getCompletionsPath(), DEFAULT_CHAT_COMPLETIONS_PATH);
		OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
			.apiKey(apiKey)
			.baseUrl(endpoint.baseUrl())
			.completionsPath(endpoint.path())
			.restClientBuilder(getProxiedRestClientBuilder(config))
			.webClientBuilder(getProxiedWebClientBuilder(config));
		OpenAiApi openAiApi = apiBuilder.build();

		// 3. 构建运行时选项 (设置默认的模型名称，如 "deepseek-chat" 或 "gpt-4")
		OpenAiChatOptions openAiChatOptions = OpenAiChatOptions.builder()
			.model(config.getModelName())
			.temperature(config.getTemperature())
			.maxTokens(config.getMaxTokens())
			.streamUsage(true)
			.build();
		// 4. 返回统一的 OpenAiChatModel
		return new CancellableChatModel(OpenAiChatModel.builder().openAiApi(openAiApi).defaultOptions(openAiChatOptions)
            .retryTemplate(org.springframework.retry.support.RetryTemplate.builder().maxAttempts(1).build()).build());
	}

	/**
	 * Embedding 同理
	 */
	public EmbeddingModel createEmbeddingModel(ModelConfigDTO config) {
        return createEmbeddingModel(config, retrievalBudget(config));
    }

    /** Independent connectivity probes use the configured deadline, not a query retrieval deadline. */
    EmbeddingModel createEmbeddingProbe(ModelConfigDTO config) {
        return createEmbeddingModel(config, Duration.ofMillis(requestTimeoutMillis(config)));
    }

    public EmbeddingModel createIndexEmbeddingModel(ModelConfigDTO config) {
        return createEmbeddingModel(config, Duration.ofMillis(requestTimeoutMillis(config)));
    }

    private EmbeddingModel createEmbeddingModel(ModelConfigDTO config, Duration budget) {
		log.info("Creating NEW EmbeddingModel instance. Provider: {}, Model: {}, BaseUrl: {}", config.getProvider(),
				config.getModelName(), config.getBaseUrl());
		checkBasic(config);

		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		return new OpenAiCompatibleEmbeddingModel(getRetrievalWebClientBuilder(config), config.getBaseUrl(), apiKey,
				config.getEmbeddingsPath(), config.getModelName(), config.getEmbeddingDimensions(), budget);
	}

	public RerankModel createRerankModel(ModelConfigDTO config) {
        return createRerankModel(config, retrievalBudget(config));
    }

    RerankModel createRerankProbe(ModelConfigDTO config) {
        return createRerankModel(config, Duration.ofMillis(requestTimeoutMillis(config)));
    }

    private RerankModel createRerankModel(ModelConfigDTO config, Duration budget) {
		log.info("Creating NEW RerankModel instance. Provider: {}, Model: {}, BaseUrl: {}", config.getProvider(),
				config.getModelName(), config.getBaseUrl());
		checkBasic(config);
		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		return new HttpRerankModel(getRetrievalWebClientBuilder(config), config.getBaseUrl(), apiKey,
				config.getRerankPath(), config.getModelName(), budget);
	}

	private static void checkBasic(ModelConfigDTO config) {
		Assert.hasText(config.getBaseUrl(), "baseUrl must not be empty");
		Assert.hasText(config.getModelName(), "modelName must not be empty");
	}

	private RestClient.Builder getProxiedRestClientBuilder(ModelConfigDTO config) {
		int timeoutValue = requestTimeoutMillis(config);
		CloseableHttpClient httpClient;
		if (config.getProxyEnabled() == null || !config.getProxyEnabled()) {
			httpClient = HttpClients.custom().disableAutomaticRetries().build();
		}
		else {
			log.info("Model [{}] is using HTTP proxy -> {}:{}", config.getModelName(), config.getProxyHost(),
					config.getProxyPort());
			BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
			if (StringUtils.hasText(config.getProxyUsername())) {
				credsProvider.setCredentials(new AuthScope(config.getProxyHost(), config.getProxyPort()),
						new UsernamePasswordCredentials(config.getProxyUsername(),
								StringUtils.hasText(config.getProxyPassword()) ? config.getProxyPassword().toCharArray()
										: new char[0]));
			}
			httpClient = HttpClients.custom().disableAutomaticRetries()
					.setProxy(new HttpHost(config.getProxyHost(), config.getProxyPort()))
					.setDefaultCredentialsProvider(credsProvider)
					.build();
		}
		HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
		requestFactory.setConnectTimeout(timeoutValue);
		requestFactory.setConnectionRequestTimeout(timeoutValue);
		requestFactory.setReadTimeout(timeoutValue);
		return RestClient.builder().requestFactory(requestFactory).requestInterceptor((request,body,execution)->{
            var budget=cn.lgs.semevosql.model.ModelTransportBudget.blocking();
            if(budget!=null)budget.beforeRequest();
            return execution.execute(request,body);
        });
	}

	private Duration retrievalBudget(ModelConfigDTO config) {
        return Duration.ofMillis(Math.min(5000, requestTimeoutMillis(config)));
    }

    private WebClient.Builder getRetrievalWebClientBuilder(ModelConfigDTO config) {
        // The adapter bounds the whole response, including connection acquisition and a slowly streamed body.
        // Optional retrieval channels do not inherit chat connection retries.
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(proxiedHttpClient(config)))
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024));
    }

	private WebClient.Builder getProxiedWebClientBuilder(ModelConfigDTO config) {
        WebClient.Builder builder=WebClient.builder().clientConnector(new ReactorClientHttpConnector(proxiedHttpClient(config)));
        return configureConnectionRetry(builder,config.getModelName());
    }
    private HttpClient proxiedHttpClient(ModelConfigDTO config) {
		Duration timeout = Duration.ofMillis(requestTimeoutMillis(config));
		HttpClient nettyClient = HttpClient.create().disableRetry(true).responseTimeout(timeout);
		if (config.getProxyEnabled() != null && config.getProxyEnabled()) {
			log.info("Model [{}] is using HTTP proxy -> {}:{}", config.getModelName(), config.getProxyHost(),
					config.getProxyPort());
			nettyClient = nettyClient.proxy(p -> {
				ProxyProvider.Builder proxyBuilder = p.type(ProxyProvider.Proxy.HTTP)
					.host(config.getProxyHost())
					.port(config.getProxyPort());
				if (StringUtils.hasText(config.getProxyUsername())) {
					proxyBuilder.username(config.getProxyUsername()).password(s -> config.getProxyPassword());
				}
			});
		}
        return nettyClient;
	}

	private int requestTimeoutMillis(ModelConfigDTO config) {
		if (config.getRequestTimeoutSeconds() != null && config.getRequestTimeoutSeconds() > 0) {
			long configured = config.getRequestTimeoutSeconds().longValue() * 1000L;
			return (int) Math.min(Integer.MAX_VALUE, configured);
		}
		Duration fallback = modelClientProperties.getRequestTimeout();
		long fallbackMillis = fallback == null ? 60000L : Math.max(1L, fallback.toMillis());
		return (int) Math.min(Integer.MAX_VALUE, fallbackMillis);
	}

	WebClient.Builder configureConnectionRetry(WebClient.Builder builder, String modelName) {
        Retry retry=cn.lgs.semevosql.model.ModelNetworkRetry.backoff(modelClientProperties.getConnectionMaxRetries(),
            modelClientProperties.getConnectionInitialBackoff(),modelClientProperties.getConnectionMaxBackoff(),
            modelClientProperties.getConnectionRetryJitter(),WebClientRequestException.class::isInstance);
        return builder.filter((request,next)->reactor.core.publisher.Mono.deferContextual(context->{
            cn.lgs.semevosql.model.ModelTransportBudget governed=context.getOrDefault(cn.lgs.semevosql.model.ModelTransportBudget.class,null);
            var budget=governed==null?new cn.lgs.semevosql.model.ModelTransportBudget(java.util.UUID.randomUUID().toString(),
                cn.lgs.semevosql.model.ModelCallPurpose.OTHER,modelClientProperties.getConnectionMaxRetries()+1):governed;
            var exchange=reactor.core.publisher.Mono.defer(()->{
                budget.beforeRequest();
                return next.exchange(request);
            });
            // Gateway retries own the whole response. An inner connection loop must not multiply that budget.
            if(governed==null && modelClientProperties.getConnectionMaxRetries()>0)exchange=exchange.retryWhen(retry);
            return CancellableChatModel.guardExchange(exchange);
        }));
    }

}
