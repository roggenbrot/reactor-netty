package reactor.netty.http.server;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

public interface FlowHttpServerRoute extends Publisher<Void>, Subscriber<HttpServerExchange> {
}
