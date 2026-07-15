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

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.web.reactive.function.client.WebClient;
import cn.lgs.semevosql.semantic.retrieval.AtomicEmbeddingFixture;
import cn.lgs.semevosql.semantic.retrieval.AtomicEmbeddingIdentity;
import cn.lgs.semevosql.util.JsonUtil;

/** A real local HTTP exchange; provider outputs are synthetic contract fixtures. */
class EmbeddingDimensionContractTest {
    HttpServer server;
    AtomicReference<String> request = new AtomicReference<>();
    AtomicReference<String> response = new AtomicReference<>();
    AtomicInteger calls = new AtomicInteger();
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        response.set("{\"data\":[{\"index\":0,\"embedding\":[0.25,0.5]}]}");
        server.createContext("/v1/embeddings",e->{
            calls.incrementAndGet();request.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body=response.get().getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);
            try(var out=e.getResponseBody()){out.write(body);}
        });server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    OpenAiCompatibleEmbeddingModel model(Integer dimensions){return new OpenAiCompatibleEmbeddingModel(WebClient.builder(),
        "http://127.0.0.1:"+server.getAddress().getPort(),"",null,"synthetic-contract",dimensions,java.time.Duration.ofSeconds(5));}
    @Test void sendsConfiguredDimensionsEvenForTheConvenienceEmbedCall(){
        assertEquals(2,model(2).embed("验收").length);assertTrue(request.get().contains("\"dimensions\":2"));
    }
    @Test void legacyConfigurationDoesNotSilentlyChangeTheProviderDefault(){
        assertEquals(2,model(null).embed("legacy").length);assertFalse(request.get().contains("dimensions"));
    }
    @Test void rejectsConflictingPerRequestDimensionsBeforeNetwork(){
        assertThrows(IllegalArgumentException.class,()->model(2).call(new EmbeddingRequest(List.of("a"),EmbeddingOptions.builder().dimensions(3).build())));
        assertEquals(0,calls.get());
    }
    @Test void rejectsProviderIgnoringDimensionsOrReturningWrongIndex(){
        assertThrows(IllegalStateException.class,()->model(3).embed("a"));
        response.set("{\"data\":[{\"index\":1,\"embedding\":[0.25,0.5]}]}");
        assertThrows(IllegalStateException.class,()->model(2).embed("a"));
    }
    @Test void rejectsMissingVectorsAndNonNumericOrOverflowingCoordinates(){
        assertThrows(IllegalStateException.class,()->model(2).embed(List.of("a","b")));
        for(String coordinate:List.of("null","\"1\"","1e100")){
            response.set("{\"data\":[{\"index\":0,\"embedding\":["+coordinate+",0.5]}]}");
            assertThrows(IllegalStateException.class,()->model(2).embed("a"));
        }
    }
    @Test void sameResponseCertificateReachesSpringMetadataAndScalarReadDoesNotEncode() throws Exception {
        var scalar=AtomicEmbeddingFixture.profile("synthetic-contract",2,"1".repeat(40));
        var certificate=AtomicEmbeddingFixture.response(scalar,List.of("whole input"),List.of(new float[]{0.25f,0.5f}));
        var body=JsonUtil.getObjectMapper().readTree(response.get());
        ((com.fasterxml.jackson.databind.node.ObjectNode)body).set("encoding_identity",certificate);
        response.set(body.toString());var reads=new AtomicInteger();
        server.createContext("/v1/embedding-identity",e->{
            reads.incrementAndGet();assertEquals("GET",e.getRequestMethod());
            assertTrue(e.getRequestURI().getQuery().contains("input_type=document"));
            byte[] bytes=scalar.toString().getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);
            try(var out=e.getResponseBody()){out.write(bytes);}
        });
        var model=model(2);var result=model.call(new EmbeddingRequest(List.of("whole input"),EmbeddingOptions.builder().build()));
        AtomicEmbeddingIdentity identity=result.getMetadata().get(AtomicEmbeddingIdentity.METADATA_KEY);
        assertNotNull(identity);assertEquals(1,calls.get());
        assertEquals(identity.profile(),model.currentDocumentProfile().orElseThrow());
        assertEquals(1,reads.get());assertEquals(1,calls.get());
    }
    @Test void unknownOrWrongAtomicProvenanceKeepsNormalVectorsButCannotEnableReuse() throws Exception {
        var model=model(2);
        assertFalse(model.currentDocumentProfile().isPresent());assertEquals(0,calls.get());
        var certificate=AtomicEmbeddingFixture.response(AtomicEmbeddingFixture.profile("synthetic-contract",2,"1".repeat(40)),
            List.of("different input"),List.of(new float[]{0.25f,0.5f}));
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtil.getObjectMapper().readTree(response.get());
        body.set("encoding_identity",certificate);response.set(body.toString());
        var result=model.call(new EmbeddingRequest(List.of("whole input"),EmbeddingOptions.builder().build()));
        assertEquals(2,result.getResult().getOutput().length);
        assertNull(result.getMetadata().get(AtomicEmbeddingIdentity.METADATA_KEY));assertEquals(1,calls.get());
    }
}
