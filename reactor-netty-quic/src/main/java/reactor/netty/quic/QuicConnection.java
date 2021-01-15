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

import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;

import java.util.function.BiFunction;

/**
 * API for creating and handling streams.
 *
 * @author Violeta Georgieva
 */
public interface QuicConnection extends Connection {

	/**
	 * The type of the stream.
	 */
	enum StreamType {

		/**
		 * An unidirectional stream.
		 */
		UNIDIRECTIONAL,
		/**
		 * A bidirectional stream.
		 */
		BIDIRECTIONAL
	}

	/**
	 * Creates a bidirectional stream. A {@link Mono} completing when the stream is created,
	 * then the provided callback is invoked. If the stream creation is not
	 * successful the returned {@link Mono} fails.
	 *
	 * @param streamHandler the I/O handler for the stream
	 * @return a {@link Mono} completing when the stream is created, otherwise fails
	 */
	default Mono<Void> createStream(
			BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> streamHandler) {
		return createStream(StreamType.BIDIRECTIONAL, streamHandler);
	}

	/**
	 * Creates a stream. A {@link Mono} completing when the stream is created,
	 * then the provided callback is invoked. If the stream creation is not
	 * successful the returned {@link Mono} fails.
	 *
	 * @param streamType the {@link StreamType}
	 * @param streamHandler the I/O handler for the stream
	 * @return a {@link Mono} completing when the stream is created, otherwise fails
	 */
	Mono<Void> createStream(
			StreamType streamType,
			BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> streamHandler);
}
