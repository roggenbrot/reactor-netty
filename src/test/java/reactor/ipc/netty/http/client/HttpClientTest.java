/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.http.client;

import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.CharsetUtil;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.AbortedException;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.options.ClientProxyOptions.Proxy;
import reactor.ipc.netty.resources.PoolResources;
import reactor.ipc.netty.tcp.TcpServer;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Stephane Maldini
 * @since 0.6
 */
public class HttpClientTest {

	@Test
	public void abort() {
		NettyContext x =
				TcpServer.create("localhost", 0)
				         .newHandler((in, out) ->
				                 in.receive()
				                   .take(1)
				                   .thenMany(Flux.defer(() ->
				                           out.context(c ->
				                                   c.addHandlerFirst(new HttpResponseEncoder()))
				                                    .sendObject(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				                                                                            HttpResponseStatus.ACCEPTED))
				                                    .then(Mono.delay(Duration.ofSeconds(2)).then()))))
				         .block(Duration.ofSeconds(30));

		assertThat(x).isNotNull();

		PoolResources pool = PoolResources.fixed("test", 1);

		HttpClient.create(opts -> opts.host("localhost")
		                              .port(x.address().getPort())
		                              .poolResources(pool))
		          .get("/")
		          .flatMap(r -> {
		                  r.dispose();
		                  return Mono.just(r.status().code());
		          })
		          .log()
		          .block(Duration.ofSeconds(30));

		HttpClientResponse resp =
				HttpClient.create(opts -> opts.host("localhost")
				                              .port(x.address().getPort())
				                              .poolResources(pool))
				          .get("/")
				          .log()
				          .block(Duration.ofSeconds(30));
		assertThat(resp).isNotNull();
		resp.dispose();

		resp = HttpClient.create(opts -> opts.host("localhost")
		                                     .port(x.address().getPort())
		                                     .poolResources(pool))
		                 .get("/")
		                 .log()
		                 .block(Duration.ofSeconds(30));
		assertThat(resp).isNotNull();
		resp.dispose();

		x.dispose();

		pool.dispose();
	}

	private DefaultFullHttpResponse response() {
		DefaultFullHttpResponse r = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
		                                                        HttpResponseStatus.ACCEPTED);
		r.headers()
		 .set(HttpHeaderNames.CONTENT_LENGTH, 0);
		return r;
	}

	@Test
	public void userIssue() throws Exception {
		final PoolResources pool = PoolResources.fixed("local", 1);
		CountDownLatch latch = new CountDownLatch(3);
		Set<String> localAddresses = ConcurrentHashMap.newKeySet();
		NettyContext serverContext =
				HttpServer.create(8080)
				          .newRouter(r -> r.post("/",
				                  (req, resp) -> req.receive()
				                                    .asString()
				                                    .flatMap(data -> {
				                                        latch.countDown();
				                                        return resp.status(200)
				                                                   .send();
				                                    })))
				          .block();

		assertThat(serverContext).isNotNull();

		final HttpClient client = HttpClient.create(options -> {
			options.poolResources(pool);
			options.connectAddress(() -> new InetSocketAddress(8080));
		});
		Flux.just("1", "2", "3")
		    .concatMap(data -> client.post("/", req -> req.sendString(Flux.just(data)))
		                             .doOnNext(r -> r.receive()
		                                             .subscribe()))
		    .subscribe(response ->
		        localAddresses.add(response.channel()
		                                   .localAddress()
		                                   .toString()));

		latch.await();
		pool.dispose();
		serverContext.dispose();
		System.out.println("Local Addresses used: " + localAddresses);
	}

	@Test
	@Ignore
	public void pipelined() {
		NettyContext x = TcpServer.create("localhost", 0)
		                          .newHandler((in, out) -> out.context(c -> c.addHandlerFirst(new
		                                  HttpResponseEncoder()))
		                                                      .sendObject(Flux.just(response(),
		                                                                            response()))
		                                                      .neverComplete())
		                          .block(Duration.ofSeconds(30));

		assertThat(x).isNotNull();

		PoolResources pool = PoolResources.fixed("test", 1);

		HttpClient.create(opts -> opts.host("localhost")
		                              .port(x.address().getPort())
		                              .poolResources(pool))
		          .get("/")
		          .flatMap(r -> {
		                  r.dispose();
		                  return Mono.just(r.status().code());
		          })
		          .log()
		          .block(Duration.ofSeconds(30));

		try {
			HttpClient.create(opts -> opts.host("localhost")
			                              .port(x.address().getPort())
			                              .poolResources(pool))
			          .get("/")
			          .log()
			          .block(Duration.ofSeconds(30));
		}
		catch (AbortedException ae) {
			return;
		}

		x.dispose();
		pool.dispose();
		Assert.fail("Not aborted");
	}

	@Test
	public void backpressured() throws Exception {
		Path resource = Paths.get(getClass().getResource("/public").toURI());
		NettyContext c = HttpServer.create(0)
		                           .newRouter(routes -> routes.directory("/test", resource))
		                           .block(Duration.ofSeconds(30));

		assertThat(c).isNotNull();

		Mono<HttpClientResponse> remote = HttpClient.create(c.address().getPort())
		                                            .get("/test/test.css");

		Mono<String> page = remote
				.flatMapMany(r -> r.receive()
				                   .asString()
				                   .limitRate(1))
				.reduce(String::concat);

		Mono<String> cancelledPage = remote
				.flatMapMany(r -> r.receive()
				                   .asString()
				                   .take(5)
				                   .limitRate(1))
				.reduce(String::concat);

		page.block(Duration.ofSeconds(30));
		cancelledPage.block(Duration.ofSeconds(30));
		page.block(Duration.ofSeconds(30));
		c.dispose();
	}

	@Test
	public void serverInfiniteClientClose() throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		NettyContext c =
				HttpServer.create(0)
				          .newHandler((req, resp) -> {
				                  req.context()
				                     .onClose(latch::countDown);

				                  return Flux.interval(Duration.ofSeconds(1))
				                             .flatMap(d -> {
				                                     req.context()
				                                        .channel()
				                                        .config()
				                                        .setAutoRead(true);

				                                     return resp.sendObject(Unpooled.EMPTY_BUFFER)
				                                                .then()
				                                                .doOnSuccess(x -> req.context()
				                                                                     .channel()
				                                                                     .config()
				                                                                     .setAutoRead(false));
				                             });
				          })
				          .block(Duration.ofSeconds(30));

		assertThat(c).isNotNull();

		Mono<HttpClientResponse> remote = HttpClient.create(c.address().getPort())
		                                            .get("/");

		HttpClientResponse r = remote.block();
		assertThat(r).isNotNull();
		r.dispose();
		latch.await();
		c.dispose();
	}

	@Test
	@Ignore
	public void postUpload() {
		InputStream f = getClass().getResourceAsStream("/public/index.html");
		HttpClient.create("google.com")
		          .put("/post",
		                  c -> c.sendForm(form -> form.multipart(true)
		                                              .file("test", f)
		                                              .attr("att1", "attr2")
		                                              .file("test2", f))
		                        .log()
		                        .then())
		          .flatMap(r -> {
		                  r.dispose();
		                  return Mono.just(r.status().code());
		          })
		          .block(Duration.ofSeconds(30));
		Integer res = HttpClient.create("google.com")
		                        .get("/search", c -> c.followRedirect()
		                                              .sendHeaders())
		                        .flatMap(r -> {
		                                r.dispose();
		                                return Mono.just(r.status().code());
		                        })
		                        .log()
		                        .block(Duration.ofSeconds(30));

		assertThat(res).isNotNull();
		if (res != 200) {
			throw new IllegalStateException("test status failed with " + res);
		}
	}

	@Test
	public void simpleTest404() {
		doSimpleTest404(HttpClient.create("google.com"));
	}

	@Test
	public void simpleTest404_1() {
		HttpClient client =
				HttpClient.create(ops -> ops.host("google.com")
				                            .port(80)
				                            .poolResources(PoolResources.fixed("http", 1)));
		doSimpleTest404(client);
		doSimpleTest404(client);
	}

	private void doSimpleTest404(HttpClient client) {
		Integer res = client.get("/unsupportedURI", c -> c.followRedirect()
				                                          .sendHeaders())
				            .flatMap(r -> {
				                    r.dispose();
				                    return Mono.just(r.status().code());
				            })
				            .log()
				            .onErrorResume(HttpClientException.class,
				                    e -> {
				                        HttpResponse msg = e.message();
				                        if (msg instanceof FullHttpResponse) {
				                            ((FullHttpResponse) msg).release();
				                        }
				                        return Mono.just(e.status()
				                                          .code());
				                    })
				            .block(Duration.ofSeconds(30));

		assertThat(res).isNotNull();
		if (res != 404) {
			throw new IllegalStateException("test status failed with " + res);
		}
	}

	@Test
	public void disableChunkForced() {
		HttpClientResponse r = HttpClient.create("google.com")
		                                 .get("/unsupportedURI",
		                                         c -> c.chunkedTransfer(false)
		                                               .failOnClientError(false)
		                                               .sendString(Flux.just("hello")))
		                                 .block();

		assertThat(r).isNotNull();

		FutureMono.from(r.context()
		                 .channel()
		                 .closeFuture())
		          .block(Duration.ofSeconds(5));

		Assert.assertTrue(Objects.equals(r.status(), HttpResponseStatus.NOT_FOUND));
		r.dispose();
	}

	@Test
	public void disableChunkForced2() {
		HttpClientResponse r = HttpClient.create("google.com")
		                                 .get("/unsupportedURI",
		                                         c -> c.chunkedTransfer(false)
		                                               .failOnClientError(false)
		                                               .keepAlive(false))
		                                 .block(Duration.ofSeconds(30));

		assertThat(r).isNotNull();

		FutureMono.from(r.context()
		                 .channel()
		                 .closeFuture())
		          .block(Duration.ofSeconds(5));

		Assert.assertTrue(Objects.equals(r.status(), HttpResponseStatus.NOT_FOUND));
		r.dispose();
	}

	@Test
	public void disableChunkImplicit() {
		PoolResources p = PoolResources.fixed("test", 1);

		HttpClientResponse r = HttpClient.create(opts -> opts.poolResources(p))
		                                 .get("http://google.com/unsupportedURI",
		                                         c -> c.failOnClientError(false)
		                                               .sendHeaders())
		                                 .block(Duration.ofSeconds(30));

		HttpClientResponse r2 = HttpClient.create(opts -> opts.poolResources(p))
		                                  .get("http://google.com/unsupportedURI",
		                                          c -> c.failOnClientError(false)
		                                                .sendHeaders())
		                                  .block(Duration.ofSeconds(30));
		assertThat(r).isNotNull();
		assertThat(r2).isNotNull();
		Assert.assertSame(r.context()
		                   .channel(), r2.context()
		                                 .channel());

		Assert.assertTrue(Objects.equals(r.status(), HttpResponseStatus.NOT_FOUND));
		r.dispose();
		r2.dispose();
		p.dispose();
	}

	@Test
	public void disableChunkImplicitDefault() {
		PoolResources p = PoolResources.fixed("test", 1);

		HttpClientResponse r = HttpClient.create(opts -> opts.poolResources(p))
		                                 .get("http://google.com/unsupportedURI",
		                                         c -> c.chunkedTransfer(false)
		                                               .failOnClientError(false))
		                                 .block(Duration.ofSeconds(30));

		HttpClientResponse r2 = HttpClient.create(opts -> opts.poolResources(p))
		                                  .get("http://google.com/unsupportedURI",
		                                         c -> c.chunkedTransfer(false)
		                                               .failOnClientError(false))
		                                 .block(Duration.ofSeconds(30));
		assertThat(r).isNotNull();
		assertThat(r2).isNotNull();
		Assert.assertSame(r.context()
		                   .channel(), r2.context()
		                                 .channel());

		Assert.assertTrue(Objects.equals(r.status(), HttpResponseStatus.NOT_FOUND));
		r.dispose();
		r2.dispose();
		p.dispose();
	}

	@Test
	public void contentHeader() {
		PoolResources fixed = PoolResources.fixed("test", 1);
		HttpClientResponse r = HttpClient.create(opts -> opts.poolResources(fixed))
		                                 .get("http://google.com",
		                                         c -> c.header("content-length", "1")
		                                               .failOnClientError(false)
		                                               .sendString(Mono.just(" ")))
		                                 .block(Duration.ofSeconds(30));

		HttpClientResponse r1 = HttpClient.create(opts -> opts.poolResources(fixed))
		                                  .get("http://google.com",
		                                          c -> c.header("content-length", "1")
		                                                .failOnClientError(false)
		                                                .sendString(Mono.just(" ")))
		                                  .block(Duration.ofSeconds(30));
		assertThat(r).isNotNull();
		assertThat(r1).isNotNull();
		Assert.assertTrue(Objects.equals(r.status(), HttpResponseStatus.BAD_REQUEST));
		r.dispose();
		r1.dispose();
		fixed.dispose();
	}

	@Test
	public void simpleTestHttps() {
		StepVerifier.create(HttpClient.create()
		                              .get("https://developer.chrome.com")
		                              .flatMap(r -> {
		                                  r.dispose();
		                                  return Mono.just(r.status().code());
		                              }))
		            .expectNextMatches(status -> status >= 200 && status < 400)
		            .expectComplete()
		            .verify();

		StepVerifier.create(HttpClient.create()
		                              .get("https://developer.chrome.com")
		                              .flatMap(r -> {
		                                  r.dispose();
		                                  return Mono.just(r.status().code());
		                              }))
		            .expectNextMatches(status -> status >= 200 && status < 400)
		            .expectComplete()
		            .verify();
	}

	@Test
	public void prematureCancel() {
		DirectProcessor<Void> signal = DirectProcessor.create();
		NettyContext x =
				TcpServer.create("localhost", 0)
				         .newHandler((in, out) -> {
				                 signal.onComplete();
				                 return out.context(c ->
				                                c.addHandlerFirst(new HttpResponseEncoder()))
				                           .sendObject(Mono.delay(Duration.ofSeconds(2))
				                                           .map(t -> new DefaultFullHttpResponse(
				                                                     HttpVersion.HTTP_1_1,
				                                                     HttpResponseStatus.PROCESSING)))
				                           .neverComplete();
				                  })
				         .block(Duration.ofSeconds(30));

		StepVerifier.create(createHttpClientForContext(x)
		                              .get("/")
		                              .timeout(signal))
		            .verifyError(TimeoutException.class);
	}

	@Test
	public void gzip() {
		String content = "HELLO WORLD";

		NettyContext c =
				HttpServer.create(opts -> opts.compression(true).port(0))
				          .newHandler((req, res) -> res.sendString(Mono.just(content)))
				          .block();

		assertThat(c).isNotNull();

		//verify gzip is negotiated (when no decoder)
		StepVerifier.create(
				HttpClient.create(c.address().getPort())
				          .get("/", req -> req
				                  .followRedirect()
				                  .addHeader("Accept-Encoding", "gzip")
				                  .addHeader("Accept-Encoding", "deflate"))
				          .flatMap(r -> r.receive().aggregate().asString()
				                         .zipWith(Mono.just(r.responseHeaders().get("Content-Encoding", "")))
				                         .zipWith(Mono.just(r))))
		            .expectNextMatches(tuple ->
		                    !tuple.getT1().getT1().equals(content)
		                    && "gzip".equals(tuple.getT1().getT2()))
		            .expectComplete()
		            .verify();

		//verify decoder does its job and removes the header
		StepVerifier.create(
				HttpClient.create(c.address().getPort())
				          .get("/", req -> {
				              req.context().addHandlerFirst("gzipDecompressor", new HttpContentDecompressor());
				              return req.followRedirect()
				                        .addHeader("Accept-Encoding", "gzip")
				                        .addHeader("Accept-Encoding", "deflate");
				          })
				          .flatMap(r -> r.receive().aggregate().asString()
				                         .zipWith(Mono.just(r.responseHeaders().get("Content-Encoding", "")))
				                         .zipWith(Mono.just(r))))
		            .expectNextMatches(tuple ->
		                    tuple.getT1().getT1().equals(content)
		                    && "".equals(tuple.getT1().getT2()))
		            .expectComplete()
		            .verify();

		c.dispose();
	}

	@Test
	public void gzipEnabled() {
		doTestGzip(true);
	}

	@Test
	public void gzipDisabled() {
		doTestGzip(false);
	}

	private void doTestGzip(boolean gzipEnabled) {
		String expectedResponse = gzipEnabled ? "gzip" : "no gzip";
		NettyContext server = HttpServer.create(0)
		        .newHandler((req,res) -> res.sendString(
		                Mono.just(req.requestHeaders().get(HttpHeaderNames.ACCEPT_ENCODING, "no gzip"))))
		        .block(Duration.ofSeconds(30));
		assertThat(server).isNotNull();
		StepVerifier.create(
		        HttpClient.create(ops -> ops.port(server.address().getPort()).compression(gzipEnabled))
		                  .get("/")
		                  .flatMap(r -> r.receive()
		                                 .asString()
		                                 .elementAt(0)
		                                 .zipWith(Mono.just(r))))
		            .expectNextMatches(tuple -> {
		                tuple.getT2().dispose();
		                return expectedResponse.equals(tuple.getT1());
		            })
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		server.dispose();
	}

	@Test
	public void testUserAgent() {
		NettyContext c =
				HttpServer.create(0)
				          .newHandler((req, resp) -> {
				                  Assert.assertTrue("" + req.requestHeaders()
				                                            .get(HttpHeaderNames.USER_AGENT),
				                                    req.requestHeaders()
				                                       .contains(HttpHeaderNames.USER_AGENT) && req.requestHeaders()
				                                                                                   .get(HttpHeaderNames.USER_AGENT)
				                                                                                   .equals(HttpClient.USER_AGENT));

				                  return resp;
				          })
				          .block();
		assertThat(c).isNotNull();
		HttpClientResponse resp = HttpClient.create(c.address().getPort())
		                                    .get("/")
		                                    .block();

		assertThat(resp).isNotNull();
		resp.dispose();
		c.dispose();
	}

	@Test
	public void toStringShowsOptions() {
		HttpClient client = HttpClient.create(opt -> opt.host("foo")
		                                                .port(123)
		                                                .compression(true));

		assertThat(client.toString()).isEqualTo("HttpClient: connecting to foo:123 with gzip");
	}

	@Test
	public void gettingOptionsDuplicates() {
		HttpClient client = HttpClient.create(opt -> opt.host("foo").port(123).compression(true));
		assertThat(client.options())
				.isNotSameAs(client.options)
				.isNotSameAs(client.options());
	}

	@Test
	public void sshExchangeRelativeGet() throws CertificateException, SSLException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
		                                        .build();
		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
		                                        .build();

		NettyContext context =
				HttpServer.create(opt -> opt.sslContext(sslServer))
				          .newHandler((req, resp) -> resp.sendString(Flux.just("hello ", req.uri())))
				          .block();

		assertThat(context).isNotNull();

		HttpClientResponse response = HttpClient.create(
				opt -> applyHostAndPortFromContext(opt, context)
				          .sslContext(sslClient))
		                                        .get("/foo")
		                                        .block();
		context.dispose();
		context.onClose().block();

		assertThat(response).isNotNull();
		String responseString = response.receive().aggregate().asString(CharsetUtil.UTF_8).block();
		assertThat(responseString).isEqualTo("hello /foo");
	}

	@Test
	public void sshExchangeAbsoluteGet() throws CertificateException, SSLException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE).build();

		NettyContext context =
				HttpServer.create(opt -> opt.sslContext(sslServer))
				          .newHandler((req, resp) -> resp.sendString(Flux.just("hello ", req.uri())))
				          .block();

		assertThat(context).isNotNull();

		HttpClientResponse response =
				HttpClient.create(opt -> applyHostAndPortFromContext(opt, context)
				                         .sslContext(sslClient))
				          .get("/foo").block();
		context.dispose();
		context.onClose().block();

		assertThat(response).isNotNull();
		String responseString = response.receive().aggregate().asString(CharsetUtil.UTF_8).block();
		assertThat(responseString).isEqualTo("hello /foo");
	}

	@Test
	public void secureSendFile()
			throws CertificateException, SSLException, URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
		AtomicReference<String> uploaded = new AtomicReference<>();

		NettyContext context =
				HttpServer.create(opt -> opt.sslContext(sslServer))
				          .newRouter(r -> r.post("/upload", (req, resp) ->
				                  req.receive()
				                     .aggregate()
				                     .asString(StandardCharsets.UTF_8)
				                     .doOnNext(uploaded::set)
				                     .then(resp.status(201).sendString(Mono.just("Received File")).then())))
				          .block();

		assertThat(context).isNotNull();

		HttpClientResponse response =
				HttpClient.create(opt -> applyHostAndPortFromContext(opt, context)
				                            .sslContext(sslClient))
				          .post("/upload", r -> r.sendFile(largeFile))
				          .block(Duration.ofSeconds(30));

		context.dispose();
		context.onClose().block();

		assertThat(response).isNotNull();
		String responseBody = response.receive().aggregate().asString().block();
		assertThat(response.status().code()).isEqualTo(201);
		assertThat(responseBody).isEqualTo("Received File");

		assertThat(uploaded.get())
				.startsWith("This is an UTF-8 file that is larger than 1024 bytes. " + "It contains accents like é.")
				.contains("1024 mark here -><- 1024 mark here")
				.endsWith("End of File");
	}

	@Test
	public void chunkedSendFile() throws URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		AtomicReference<String> uploaded = new AtomicReference<>();

		NettyContext context =
				HttpServer.create(opt -> opt.host("localhost"))
				          .newRouter(r -> r.post("/upload", (req, resp) ->
				                  req.receive()
				                     .aggregate()
				                     .asString(StandardCharsets.UTF_8)
				                     .doOnNext(uploaded::set)
				                     .then(resp.status(201).sendString(Mono.just("Received File")).then())))
				          .block();

		assertThat(context).isNotNull();

		HttpClientResponse response =
				createHttpClientForContext(context)
				          .post("/upload", r -> r.sendFile(largeFile))
				          .block(Duration.ofSeconds(30));

		context.dispose();
		context.onClose().block();

		assertThat(response).isNotNull();
		String responseBody = response.receive().aggregate().asString().block();
		assertThat(response.status().code()).isEqualTo(201);
		assertThat(responseBody).isEqualTo("Received File");

		assertThat(uploaded.get())
				.startsWith("This is an UTF-8 file that is larger than 1024 bytes. " + "It contains accents like é.")
				.contains("1024 mark here -><- 1024 mark here")
				.endsWith("End of File");
	}

	@Test
	public void test() {
		NettyContext context =
				HttpServer.create(opt -> opt.host("localhost"))
				          .newRouter(r -> r.put("/201", (req, res) -> res.addHeader("Content-Length", "0")
				                                                         .status(HttpResponseStatus.CREATED)
				                                                         .sendHeaders())
				                           .put("/204", (req, res) -> res.status(HttpResponseStatus.NO_CONTENT)
				                                                         .sendHeaders())
				                           .get("/200", (req, res) -> res.addHeader("Content-Length", "0")
				                                                         .sendHeaders()))
				          .block(Duration.ofSeconds(30));

		assertThat(context).isNotNull();

		HttpClientResponse response1 =
				createHttpClientForContext(context)
				          .put("/201", HttpClientRequest::sendHeaders)
				          .block();
		assertThat(response1).isNotNull();

		HttpClientResponse response2 =
				createHttpClientForContext(context)
				          .put("/204", HttpClientRequest::sendHeaders)
				          .block(Duration.ofSeconds(30));
		assertThat(response2).isNotNull();

		HttpClientResponse response3 =
				createHttpClientForContext(context)
				          .get("/200", HttpClientRequest::sendHeaders)
				          .block(Duration.ofSeconds(30));
		assertThat(response3).isNotNull();

		response1.dispose();
		response2.dispose();
		response3.dispose();
		context.dispose();
	}



	@Test
	public void closePool() {
		PoolResources pr = PoolResources.fixed("wstest", 1);
		NettyContext httpServer =
				HttpServer.create(0)
				          .newHandler((in, out) ->
				                  out.options(NettyPipeline.SendOptions::flushOnEach)
				                     .sendString(Mono.just("test")
				                                     .delayElement(Duration.ofMillis(100))
				                                     .repeat()))
				          .block(Duration.ofSeconds(30));

		assertThat(httpServer).isNotNull();

		Flux<String> ws = HttpClient.create(opts -> opts.port(httpServer.address()
		                                                                .getPort())
		                                                .poolResources(pr))
		                            .get("/")
		                            .flatMapMany(in -> in.receive()
		                                                 .asString());

		StepVerifier.create(
				Flux.range(1, 10)
				    .concatMap(i -> ws.take(2)
				                      .log()))
		            .expectNextSequence(
		                    Objects.requireNonNull(Flux.range(1, 20)
		                                               .map(v -> "test")
		                                               .collectList()
		                                               .block()))
		            .expectComplete()
		            .verify();

		httpServer.dispose();
		pr.dispose();
	}

	private HttpClient createHttpClientForContext(NettyContext context) {
		return HttpClient.create(opt -> applyHostAndPortFromContext(opt, context));
	}

	private HttpClientOptions.Builder applyHostAndPortFromContext(HttpClientOptions.Builder httpClientOptions,
			NettyContext context) {
		httpClientOptions.connectAddress(context::address);
		return httpClientOptions;
	}

	@Test
	public void testIssue303() {
		NettyContext server =
				HttpServer.create(0)
				          .newHandler((req, resp) -> resp.sendString(Mono.just("OK")))
				          .block(Duration.ofSeconds(30));

		assertThat(server).isNotNull();

		Mono<String> content =
				HttpClient.create(server.address().getPort())
				          .get("/", req ->
				                  req.sendByteArray(Mono.defer(() -> Mono.just("Hello".getBytes(Charset.defaultCharset())))))
				          .flatMap(it -> it.receive().aggregate().asString());

		StepVerifier.create(content)
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		server.dispose();
	}

	@Test
	public void testIssue361() {
		NettyContext server =
				HttpServer.create(0)
				          .newHandler((req, res) -> req.receive()
				                                       .aggregate()
				                                       .asString()
				                                       .flatMap(s -> res.sendString(Mono.just(s))
				                                                        .then()))
				          .block(Duration.ofSeconds(30));

		assertThat(server).isNotNull();

		PoolResources fixedPool = PoolResources.fixed("test", 1);
		HttpClient client =
				HttpClient.create(ops -> ops.port(server.address().getPort())
				                            .poolResources(fixedPool));

		String response = client.post("/", req -> req.sendString(Mono.just("test"))
		                                             .send(Mono.error(new Exception("error"))))
		                        .flatMap(res -> res.receive()
		                                           .aggregate()
		                                           .asString())
		                        .onErrorResume(t -> Mono.just(t.getMessage()))
		                        .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("error");

		response = client.post("/", req -> req.sendString(Mono.just("test")))
		                 .flatMap(res -> res.receive()
		                                    .aggregate()
		                                    .asString())
		                 .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("test");

		server.dispose();
		fixedPool.dispose();
	}

	@Test
	public void testConnectAddressNotSpecifiedNewHandler() {
		StepVerifier.create(
				HttpClient.create()
				          .newHandler((res, req) -> Mono.empty()))
				    .expectError(ConnectException.class)
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testConnectAddressNotSpecifiedGetRequest1() {
		StepVerifier.create(
				HttpClient.create()
				          .get(""))
				    .expectError(ConnectException.class)
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testConnectAddressNotSpecifiedGetRequest2() {
		StepVerifier.create(
				HttpClient.create()
				          .get("/"))
				    .expectError(ConnectException.class)
				    .verify(Duration.ofSeconds(30));
	}
}