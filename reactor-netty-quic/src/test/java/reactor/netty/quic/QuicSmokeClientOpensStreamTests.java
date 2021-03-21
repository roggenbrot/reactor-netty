/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.quic;

import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.ConnectionObserver.State.CONNECTED;

class QuicSmokeClientOpensStreamTests {

	static final String EMPTY_RESPONSE = "Hello Empty!";
	static final String FLUX_RESPONSE = "Hello Flux!";
	static final String MONO_RESPONSE = "Hello Mono!";

	static final String METHOD = "GET";
	static final String PROTOCOL = "http/0.9";

	static final String EMPTY_PATH = " /empty";
	static final String FLUX_PATH = " /flux";
	static final String MONO_PATH = " /mono";
	static final String ERROR_PATH_1 = " /error1";
	static final String ERROR_PATH_2 = " /error2";

	static Connection server;
	static QuicConnection client;

	@BeforeAll
	static void setUp() throws Exception {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		QuicSslContext serverCtx =
				QuicSslContextBuilder.forServer(ssc.privateKey(), null, ssc.certificate())
				                     .applicationProtocols(PROTOCOL)
				                     .build();

		server =
				QuicServer.create()
				          .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
				          .port(7777)
				          .wiretap(true)
				          .secure(serverCtx)
				          .idleTimeout(Duration.ofSeconds(5))
				          .initialSettings(spec ->
				              spec.maxData(10000000)
				                  .maxStreamDataBidirectionalLocal(1000000)
				                  .maxStreamDataBidirectionalRemote(1000000)
				                  .maxStreamsBidirectional(100)
				                  .maxStreamsUnidirectional(100))
				          .streamObserve((conn, state) -> {
				              if (state == CONNECTED) {
				                  conn.addHandlerLast(new LineBasedFrameDecoder(1024));
				              }
				          })
				          .handleStream((in, out) ->
				                  out.sendString(in.receive()
				                                   .asString()
				                                   .flatMap(s -> {
				                                           if ((METHOD + MONO_PATH).equals(s)) {
				                                               return Mono.just(MONO_RESPONSE + "\r\n");
				                                           }
				                                           else if ((METHOD + FLUX_PATH).equals(s)) {
				                                               return Flux.just("Hello", " ", "Flux", "!", "\r\n");
				                                           }
				                                           else if ((METHOD + ERROR_PATH_1).equals(s)) {
				                                               throw new RuntimeException("error1");
				                                           }
				                                           else if ((METHOD + ERROR_PATH_2).equals(s)) {
					                                           return Mono.error(new RuntimeException("error2"));
				                                           }
				                                           return Mono.empty();
				                                   })))
				          .bindNow();

		QuicSslContext clientCtx =
				QuicSslContextBuilder.forClient()
				                     .trustManager(InsecureTrustManagerFactory.INSTANCE)
				                     .applicationProtocols(PROTOCOL)
				                     .build();

		client =
				QuicClient.create()
				          .port(7777)
				          .bindAddress(() -> new InetSocketAddress(0))
				          .wiretap(true)
				          .secure(clientCtx)
				          .idleTimeout(Duration.ofSeconds(5))
				          .initialSettings(spec ->
				              spec.maxData(10000000)
				                  .maxStreamDataBidirectionalLocal(1000000))
				          .streamObserve((conn, state) -> {
				              if (state == CONNECTED) {
				                  conn.addHandlerLast(new LineBasedFrameDecoder(1024));
				              }
				          })
				          .connectNow();
	}

	@AfterAll
	static void tearDown() {
		if (server != null) {
			server.disposeNow();
		}
		if (client != null) {
			client.disposeNow();
		}
	}

	@Test
	void testError_1() throws Exception {
		doTestClientOpensStream(client, Mono.just(METHOD + ERROR_PATH_1 + "\r\n"), EMPTY_RESPONSE);
	}

	@Test
	void testError_2() throws Exception {
		doTestClientOpensStream(client, Mono.just(METHOD + ERROR_PATH_2 + "\r\n"), EMPTY_RESPONSE);
	}

	@Test
	void testEmpty() throws Exception {
		doTestClientOpensStream(client, Mono.just(METHOD + EMPTY_PATH + "\r\n"), EMPTY_RESPONSE);
	}

	@Test
	void testFlux() throws Exception {
		doTestClientOpensStream(client, Flux.just(METHOD, FLUX_PATH, "\r\n"), FLUX_RESPONSE);
	}

	@Test
	void testMono() throws Exception {
		doTestClientOpensStream(client, Mono.just(METHOD + MONO_PATH + "\r\n"), MONO_RESPONSE);
	}

	private void doTestClientOpensStream(QuicConnection client, Publisher<String> body, String expectation) throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<String> response = new AtomicReference<>();
		client.createStream(
		        QuicConnection.StreamType.BIDIRECTIONAL,
		        (in, out) -> {
		            in.receive()
		              .asString()
		              .defaultIfEmpty(EMPTY_RESPONSE)
		              .doOnNext(s -> {
		                  response.set(s);
		                  latch.countDown();
		              })
		             .subscribe();

		            return out.sendString(body);
		            })
		      .block();

		assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

		assertThat(response.get()).isNotNull()
				.isEqualTo(expectation);
	}
}
