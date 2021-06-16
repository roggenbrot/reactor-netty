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

package reactor.netty.http.server;

import org.reactivestreams.Publisher;

import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * A generic route with predicate and a I/O handler that if the predicate is matched is invoked
 *
 * @since 1.0.8
 * @author Sascha Dais
 */
public interface HttpServerRoute {

	/**
	 * Return predicate applied on each inbound request
	 *
	 * @return Predicate applied on each inbound request
	 */
	Predicate<? super HttpServerRequest> condition();

	/**
	 *  Returns the I/O handler to invoke on match of predicate returned by {@link #condition()}
	 *
	 * @return I/O handler
	 */
	BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler();
}
