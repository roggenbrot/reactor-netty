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
package reactor.netty;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

public class ByteBufFluxTest {

	@Test
	public void testFromString_EmptyFlux() {
		doTestFromStringEmptyPublisher(Flux.empty());
	}

	@Test
	public void testFromString_EmptyMono() {
		doTestFromStringEmptyPublisher(Mono.empty());
	}

	@Test
	public void testFromString_Callable() {
		doTestFromString(Mono.fromCallable(() -> "123"));
	}

	@Test
	public void testFromString_Flux() {
		StepVerifier.create(ByteBufFlux.fromString(Flux.just("1", "2", "3")).asString())
		            .expectNext("1", "2", "3")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testFromString_Mono() {
		doTestFromString(Mono.just("123"));
	}

	private void doTestFromString(Publisher<? extends String> source) {
		StepVerifier.create(ByteBufMono.fromString(source).asString())
		            .expectNext("123")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	private void doTestFromStringEmptyPublisher(Publisher<? extends String> source) {
		StepVerifier.create(ByteBufFlux.fromString(source).asString())
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}
}