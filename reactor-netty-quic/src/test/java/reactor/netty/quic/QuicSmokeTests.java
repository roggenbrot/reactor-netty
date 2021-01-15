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

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class QuicSmokeTests {

	@Test
	void testClientOpensStream() throws Exception {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		QuicSslContext serverCtx =
				QuicSslContextBuilder.forServer(ssc.privateKey(), null, ssc.certificate())
				                     .applicationProtocols("http/0.9")
				                     .build();

		Connection server =
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
				          .handleStream((in, out) -> in.receive()
				                                       .asString()
				                                       .flatMap(s -> {
				                                           if ("GET /\r\n".equals(s)) {
				                                               return out.sendString(Mono.just("Hello World!"));
				                                           }
				                                           return Mono.error(new RuntimeException("Unexpected request"));
				                                       }))
				          .bindNow();

		QuicSslContext clientCtx =
				QuicSslContextBuilder.forClient()
				                     .trustManager(InsecureTrustManagerFactory.INSTANCE)
				                     .applicationProtocols("http/0.9")
				                     .build();

		QuicConnection client =
				QuicClient.create()
				          .port(7777)
				          .bindAddress(() -> new InetSocketAddress(0))
				          .wiretap(true)
				          .secure(clientCtx)
				          .idleTimeout(Duration.ofSeconds(5))
				          .initialSettings(spec ->
				              spec.maxData(10000000)
				                  .maxStreamDataBidirectionalLocal(1000000))
				          .connectNow();

		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<String> response = new AtomicReference<>();
		client.createStream(
		        QuicConnection.StreamType.BIDIRECTIONAL,
		        (in, out) -> out.sendString(Mono.just("GET /\r\n"))
		                        .then(in.receive()
		                                .asString()
		                                .doOnNext(s -> {
		                                    response.set(s);
		                                    latch.countDown();
		                                })
		                                .then()))
		        .block();

		assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

		assertThat(response.get()).isNotNull()
				.isEqualTo("Hello World!");
	}
}
