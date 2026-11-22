/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.monitor;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.monitor.WebSocketSender;

/**
 * Probe of {@link WebSocketSender} (haumacher/ehome #209): threads sending at the same time on a socket
 * that refuses a second pending send and completes its sends on another thread, as the JDK's does.
 */
public class TestWebSocketSenderProbe extends TestCase {

	private static final int THREADS = 8;

	private static final int MESSAGES = 200;

	/**
	 * Every message of every thread arrives, none is refused, and the messages of one thread keep their
	 * order.
	 */
	public void testConcurrentSenders() throws Exception {
		ExecutorService network = Executors.newSingleThreadExecutor();
		ExecutorService senders = Executors.newFixedThreadPool(THREADS);
		try {
			AsyncSocket socket = new AsyncSocket(network);
			WebSocketSender sender = new WebSocketSender(socket);
			CountDownLatch start = new CountDownLatch(1);
			List<CompletableFuture<WebSocket>> sends = Collections.synchronizedList(new ArrayList<>());
			for (int thread = 0; thread < THREADS; thread++) {
				int id = thread;
				senders.execute(() -> {
					try {
						start.await();
					} catch (InterruptedException ex) {
						return;
					}
					for (int n = 0; n < MESSAGES; n++) {
						sends.add(sender.send(id + ":" + n));
					}
				});
			}
			start.countDown();
			senders.shutdown();
			assertTrue(senders.awaitTermination(10, TimeUnit.SECONDS));
			CompletableFuture.allOf(sends.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);

			assertEquals("Refused sends.", 0, socket.refused());
			List<String> texts = socket.texts();
			assertEquals(THREADS * MESSAGES, texts.size());
			int[] next = new int[THREADS];
			for (String text : texts) {
				String[] parts = text.split(":");
				int id = Integer.parseInt(parts[0]);
				assertEquals("Order of thread " + id + ".", next[id], Integer.parseInt(parts[1]));
				next[id]++;
			}
		} finally {
			network.shutdownNow();
			senders.shutdownNow();
		}
	}

	/** A socket that refuses a send while one is pending and completes each send later on its executor. */
	private static final class AsyncSocket implements WebSocket {

		private final ExecutorService _network;

		private final List<String> _texts = new ArrayList<>();

		private boolean _pending;

		private int _refused;

		AsyncSocket(ExecutorService network) {
			_network = network;
		}

		synchronized int refused() {
			return _refused;
		}

		synchronized List<String> texts() {
			return new ArrayList<>(_texts);
		}

		@Override
		public synchronized CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
			if (_pending) {
				_refused++;
				return CompletableFuture.failedFuture(new IllegalStateException("Send pending"));
			}
			_pending = true;
			_texts.add(data.toString());
			CompletableFuture<WebSocket> result = new CompletableFuture<>();
			_network.execute(() -> {
				if (ThreadLocalRandom.current().nextInt(4) == 0) {
					Thread.yield();
				}
				synchronized (AsyncSocket.this) {
					_pending = false;
				}
				result.complete(this);
			});
			return result;
		}

		@Override
		public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
			throw new UnsupportedOperationException();
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
		public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void request(long n) {
			// Nothing to receive.
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

	/**
	 * The suite of this test.
	 */
	public static Test suite() {
		return new TestSuite(TestWebSocketSenderProbe.class);
	}

}
