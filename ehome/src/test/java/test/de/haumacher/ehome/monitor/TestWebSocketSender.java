/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.monitor;

import java.io.IOException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.monitor.WebSocketSender;

/**
 * {@link WebSocketSender} (haumacher/ehome #209): a message sent while the one before is still
 * pending waits for it, as the {@link WebSocket} of the JDK requires, a binary message and the
 * close as well. The JDK refuses a second send with "Send pending": the subscription of the monitor
 * stream, sent when the controller had answered the authentication before the authentication's send
 * had completed, was lost.
 */
@SuppressWarnings("javadoc")
public class TestWebSocketSender extends TestCase {

	public void testSendsOneAfterAnother() throws Exception {
		StrictSocket socket = new StrictSocket();
		WebSocketSender sender = new WebSocketSender(socket);

		CompletableFuture<WebSocket> auth = sender.send("auth");
		// The answer to the authentication arrives while its send has not completed yet.
		CompletableFuture<WebSocket> subscribe = sender.send("subscribe");
		assertEquals(List.of("auth"), socket.texts());
		assertFalse(subscribe.isDone());

		socket.complete(0);
		assertSame(socket, auth.get(1, TimeUnit.SECONDS));
		assertEquals(List.of("auth", "subscribe"), socket.texts());
		assertFalse(subscribe.isDone());

		socket.complete(1);
		assertSame(socket, subscribe.get(1, TimeUnit.SECONDS));
		assertEquals(0, socket.refused());
	}

	public void testFailureDoesNotBlockTheNext() throws Exception {
		StrictSocket socket = new StrictSocket();
		WebSocketSender sender = new WebSocketSender(socket);

		CompletableFuture<WebSocket> first = sender.send("first");
		CompletableFuture<WebSocket> second = sender.send("second");
		socket.fail(0, new IOException("broken"));
		try {
			first.get(1, TimeUnit.SECONDS);
			fail("The failure of the send was not reported.");
		} catch (ExecutionException ex) {
			assertEquals("broken", ex.getCause().getMessage());
		}
		assertEquals(List.of("first", "second"), socket.texts());
		socket.complete(1);
		assertSame(socket, second.get(1, TimeUnit.SECONDS));
		assertEquals(0, socket.refused());
	}

	public void testBinaryAndCloseInOrder() throws Exception {
		StrictSocket socket = new StrictSocket();
		WebSocketSender sender = new WebSocketSender(socket);

		sender.send("head");
		CompletableFuture<WebSocket> data = sender.sendBinary(ByteBuffer.wrap(new byte[3]));
		CompletableFuture<WebSocket> close = sender.close(WebSocket.NORMAL_CLOSURE, "bye");
		assertEquals(List.of("head"), socket.texts());

		socket.complete(0);
		assertEquals(List.of("head", "binary 3"), socket.texts());
		assertFalse(close.isDone());

		socket.complete(1);
		assertSame(socket, data.get(1, TimeUnit.SECONDS));
		assertSame(socket, close.get(1, TimeUnit.SECONDS));
		assertEquals(List.of("head", "binary 3", "close 1000"), socket.texts());
		assertEquals(0, socket.refused());
	}

	/**
	 * A {@link WebSocket} that, as the one of the JDK, refuses a text while the previous one is
	 * pending; the test completes the sends.
	 */
	private static final class StrictSocket implements WebSocket {

		private final List<String> _texts = new ArrayList<>();

		private final List<CompletableFuture<WebSocket>> _sends = new ArrayList<>();

		private boolean _pending;

		private int _refused;

		@Override
		public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
			return message(data.toString());
		}

		/** A text or binary message: one may be pending at a time. */
		private synchronized CompletableFuture<WebSocket> message(String text) {
			if (_pending) {
				_refused++;
				return CompletableFuture.failedFuture(new IllegalStateException("Send pending"));
			}
			_pending = true;
			_texts.add(text);
			CompletableFuture<WebSocket> send = new CompletableFuture<>();
			_sends.add(send);
			return send;
		}

		synchronized List<String> texts() {
			return new ArrayList<>(_texts);
		}

		synchronized int refused() {
			return _refused;
		}

		void complete(int index) {
			send(index).complete(this);
		}

		void fail(int index, Throwable error) {
			send(index).completeExceptionally(error);
		}

		/** The send of the given index, no longer pending: completing it starts the next. */
		private synchronized CompletableFuture<WebSocket> send(int index) {
			_pending = false;
			return _sends.get(index);
		}

		@Override
		public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
			return message("binary " + data.remaining());
		}

		@Override
		public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
			throw new UnsupportedOperationException();
		}

		@Override
		public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
			throw new UnsupportedOperationException();
		}

		@Override
		public synchronized CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
			_texts.add("close " + statusCode);
			return CompletableFuture.completedFuture(this);
		}

		@Override
		public void request(long n) {
			// Nothing received.
		}

		@Override
		public String getSubprotocol() {
			return "";
		}

		@Override
		public boolean isOutputClosed() {
			return false;
		}

		@Override
		public boolean isInputClosed() {
			return false;
		}

		@Override
		public void abort() {
			// Nothing to abort.
		}
	}

	public static Test suite() {
		return new TestSuite(TestWebSocketSender.class);
	}

}
