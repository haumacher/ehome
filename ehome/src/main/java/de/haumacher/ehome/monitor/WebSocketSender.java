/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Sends the messages of a {@link WebSocket} one after another.
 *
 * <p>
 * A {@link WebSocket} takes one text message at a time: a {@link WebSocket#sendText(CharSequence,
 * boolean)} while the previous one has not completed fails with an {@link IllegalStateException}
 * ("Send pending"). The previous send completes only after its frame was written, and the peer may
 * have answered it before: a message sent in reaction to the answer (the subscription after the
 * authentication) would be refused. Each send of this class starts only when the one before has
 * completed, whether it succeeded or not; the close comes after the messages sent before it.
 * </p>
 */
public final class WebSocketSender {

	private final WebSocket _socket;

	private CompletableFuture<?> _last = CompletableFuture.completedFuture(null);

	/**
	 * Creates a {@link WebSocketSender} for the given socket.
	 */
	public WebSocketSender(WebSocket socket) {
		_socket = socket;
	}

	/**
	 * Sends the given text as one message, after the messages sent before.
	 *
	 * @return Completes when the message is sent, exceptionally when sending it failed.
	 */
	public CompletableFuture<WebSocket> send(String text) {
		return enqueue(socket -> socket.sendText(text, true));
	}

	/**
	 * Sends the given data as one binary message, after the messages sent before.
	 *
	 * @return Completes when the message is sent, exceptionally when sending it failed.
	 */
	public CompletableFuture<WebSocket> sendBinary(ByteBuffer data) {
		return enqueue(socket -> socket.sendBinary(data, true));
	}

	/**
	 * Closes the output of the socket, after the messages sent before.
	 *
	 * @return Completes when the close is sent, exceptionally when sending it failed.
	 */
	public CompletableFuture<WebSocket> close(int statusCode, String reason) {
		return enqueue(socket -> socket.sendClose(statusCode, reason));
	}

	private synchronized CompletableFuture<WebSocket> enqueue(
			Function<WebSocket, CompletableFuture<WebSocket>> send) {
		CompletableFuture<WebSocket> result =
			_last.handle((previous, error) -> null).thenCompose(ignored -> send.apply(_socket));
		_last = result;
		return result;
	}

}
