package reactor.netty.http.server;

public interface HttpServerExchange {

	HttpServerRequest request();

	HttpServerResponse response();
}
