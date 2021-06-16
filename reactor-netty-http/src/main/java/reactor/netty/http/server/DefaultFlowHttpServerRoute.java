package reactor.netty.http.server;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class DefaultFlowHttpServerRoute implements FlowHttpServerRoute {


	private Subscriber<? super Void> subscriber;

	@Override
	public void onSubscribe(Subscription subscription) {
		subscription.request(Integer.MAX_VALUE);
	}

	@Override
	public void onNext(HttpServerExchange httpServerExchange) {

	}

	@Override
	public void onError(Throwable t) {
		if (subscriber != null) {
			subscriber.onError(t);
		}
	}

	@Override
	public void onComplete() {

	}

	@Override
	public void subscribe(Subscriber<? super Void> subscriber) {
		this.subscriber = subscriber;
		this.subscriber.onSubscribe(new Subscription() {
			@Override
			public void request(long n) {

			}

			@Override
			public void cancel() {

			}
		});
	}
}
