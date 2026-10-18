/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx.transport;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import tuwien.auto.calimero.CloseEvent;
import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.Priority;
import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.NetworkLinkListener;

import com.top_logic.basic.Logger;

/**
 * The client side of a connection of the KNX transport layer to one device, on a Calimero
 * {@link KNXNetworkLink} (e.g. a KNXnet/IP tunnel): connect, data with sequence numbers,
 * acknowledgements and repetitions, the answer of the device to a request, and the end of the
 * connection.
 *
 * <p>
 * Used instead of Calimero's <code>TransportLayerImpl</code> (haumacher/ehome #31): that one queues
 * the device's frames while it waits for an acknowledgement and stops reading the queue when the
 * acknowledgement is found; a frame of the device arriving in between (its answer, right after the
 * acknowledgement) stays in the queue until the next send. The answer is then missed, the device
 * repeats it, and the stranded original is acknowledged a second time later, which a device takes
 * for an acknowledgement out of sequence and ends the connection.
 * </p>
 *
 * <p>
 * Here every frame of the device and every timer is handled on one thread, in the order they come;
 * the caller waits for the outcome of its request. An acknowledgement of the data unit before the
 * one awaited (the device received a unit twice) is ignored ({@link ConnectionSequence}). A
 * repeated data unit of the device is acknowledged again and not delivered twice.
 * </p>
 *
 * <p>
 * The client sends only what it is given: what a service means (and whether it changes the device)
 * is decided by its caller.
 * </p>
 */
public final class TransportClient implements NetworkLinkListener, AutoCloseable {

	/**
	 * The timing of the client.
	 *
	 * @param ackTimeout
	 *        How long to wait for the acknowledgement of a data unit before sending it again (KNX:
	 *        3 s).
	 * @param repetitions
	 *        How often a data unit is sent again (KNX: 3).
	 * @param responseTimeout
	 *        How long to wait for the device's answer after the acknowledgement.
	 * @param idleTimeout
	 *        After how long without traffic the client ends the connection (KNX: 6 s).
	 */
	public record Timing(Duration ackTimeout, int repetitions, Duration responseTimeout, Duration idleTimeout) {

		/** The KNX values, with 5 s for an answer. */
		public static final Timing DEFAULT =
			new Timing(Duration.ofSeconds(3), 3, Duration.ofSeconds(5), Duration.ofSeconds(6));

		/**
		 * The longest a request can take before one of the timers ends it.
		 */
		Duration bound() {
			return ackTimeout.multipliedBy(repetitions + 1L).plus(responseTimeout).plus(idleTimeout)
				.plusSeconds(10);
		}
	}

	/**
	 * Who ended the connection after a final service ({@link TransportClient#requestAndEnd(byte[])}).
	 */
	public enum End {
		/** The client, after the device acknowledged the service. */
		CLIENT,

		/** The device (it disconnected before or instead of acknowledging). */
		DEVICE,

		/**
		 * The client, after the device acknowledged none of the repetitions: a device may restart
		 * before acknowledging (the MDT actuator AKK-0416.03 does), which KNX does not treat as an
		 * error.
		 */
		UNACKNOWLEDGED;
	}

	/**
	 * A failure of the connection or a missing answer.
	 */
	public static final class TransportException extends Exception {

		/**
		 * Creates a {@link TransportException}.
		 */
		public TransportException(String message) {
			super(message);
		}

		/**
		 * Creates a {@link TransportException} with its cause.
		 */
		public TransportException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	private final KNXNetworkLink _link;

	private final IndividualAddress _device;

	private final Timing _timing;

	private final ScheduledExecutorService _executor;

	private final ConnectionSequence _sequence = new ConnectionSequence();

	private boolean _open;

	private Request _current;

	private ScheduledFuture<?> _idleTimer;

	/** Counts the timers, so that one that fires after its request ended does nothing. */
	private long _generation;

	private int _staleAcks;

	/**
	 * A request of the caller in progress.
	 */
	private final class Request {

		final byte[] _apdu;

		final Predicate<byte[]> _answer;

		final boolean _final;

		final CompletableFuture<Object> _outcome = new CompletableFuture<>();

		byte[] _tpdu;

		int _repeat;

		boolean _acknowledged;

		byte[] _response;

		ScheduledFuture<?> _timer;

		Request(byte[] apdu, Predicate<byte[]> answer, boolean isFinal) {
			_apdu = apdu;
			_answer = answer;
			_final = isFinal;
		}
	}

	/**
	 * Creates a {@link TransportClient} for the given device on the given link.
	 */
	public TransportClient(KNXNetworkLink link, int device, Timing timing) {
		_link = link;
		_device = new IndividualAddress(device);
		_timing = timing;
		_executor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread thread = new Thread(r, "KNX transport to " + _device);
			thread.setDaemon(true);
			return thread;
		});
		link.addLinkListener(this);
	}

	/**
	 * The device.
	 */
	public IndividualAddress device() {
		return _device;
	}

	/**
	 * How often an acknowledgement of a data unit acknowledged before arrived (and was ignored).
	 */
	public int staleAcknowledgements() {
		return call(() -> Integer.valueOf(_staleAcks)).intValue();
	}

	/**
	 * Sends an application unit in the connection (opened first if none is open) and waits for its
	 * acknowledgement and, if requested, for the device's answer.
	 *
	 * @param apdu
	 *        The application unit (APCI and data; the transport bits are set here).
	 * @param answer
	 *        Tells the device's answer among its data units (the application units), <code>null</code>
	 *        for a service without answer.
	 * @return The answer (application unit), <code>null</code> for a service without answer.
	 */
	public byte[] request(byte[] apdu, Predicate<byte[]> answer) throws TransportException, InterruptedException {
		return (byte[]) await(new Request(apdu, answer, false));
	}

	/**
	 * Sends a last application unit in the connection (A_Restart) and ends the connection: when the
	 * device has acknowledged it, the client disconnects at once instead of waiting for the device to
	 * do so (it may or may not, haumacher/ehome #30). A device that disconnects before or instead of
	 * acknowledging ends it as well. A device that acknowledges none of the repetitions has
	 * restarted before acknowledging: the client ends the connection, and that is no failure.
	 */
	public End requestAndEnd(byte[] apdu) throws TransportException, InterruptedException {
		return (End) await(new Request(apdu, null, true));
	}

	/**
	 * Ends the connection, if one is open.
	 */
	public void disconnect() {
		call(() -> {
			if (_open) {
				close(true);
			}
			return null;
		});
	}

	/**
	 * Whether a connection is open.
	 */
	public boolean isConnected() {
		return call(() -> Boolean.valueOf(_open)).booleanValue();
	}

	@Override
	public void close() {
		if (!_executor.isShutdown()) {
			disconnect();
		}
		_link.removeLinkListener(this);
		_executor.shutdownNow();
	}

	@Override
	public void indication(FrameEvent e) {
		if (e.getFrame() instanceof CEMILData frame && frame.getSource().equals(_device)) {
			execute(() -> onFrame(frame));
		}
	}

	@Override
	public void confirmation(FrameEvent e) {
		// The confirmation of a send is awaited by sendRequestWait().
	}

	@Override
	public void linkClosed(CloseEvent e) {
		execute(() -> {
			_open = false;
			cancelIdle();
			fail("The connection to the gateway was closed: " + e.getReason());
		});
	}

	private Object await(Request request) throws TransportException, InterruptedException {
		execute(() -> start(request));
		try {
			return request._outcome.get(_timing.bound().toMillis(), TimeUnit.MILLISECONDS);
		} catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof TransportException transport) {
				throw new TransportException(transport.getMessage(), transport);
			}
			throw new TransportException(String.valueOf(cause), cause);
		} catch (TimeoutException ex) {
			throw new TransportException("No outcome of the request to " + _device + ".", ex);
		} catch (InterruptedException ex) {
			execute(() -> {
				if (_current == request) {
					_current = null;
					cancel(request);
				}
			});
			throw ex;
		}
	}

	private <T> T call(Callable<T> task) {
		CompletableFuture<T> result = new CompletableFuture<>();
		if (_executor.isShutdown()) {
			try {
				return task.call();
			} catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}
		_executor.execute(() -> {
			try {
				result.complete(task.call());
			} catch (Exception ex) {
				result.completeExceptionally(ex);
			}
		});
		try {
			return result.get();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		} catch (ExecutionException ex) {
			throw new IllegalStateException(ex.getCause());
		}
	}

	private void execute(Runnable task) {
		if (_executor.isShutdown()) {
			return;
		}
		try {
			_executor.execute(() -> {
				try {
					task.run();
				} catch (RuntimeException ex) {
					Logger.error("The transport to " + _device + " failed.", ex, TransportClient.class);
				}
			});
		} catch (RejectedExecutionException ex) {
			// Closed meanwhile.
		}
	}

	private void start(Request request) {
		if (_current != null) {
			request._outcome.completeExceptionally(new TransportException("A request to " + _device + " is in progress."));
			return;
		}
		_current = request;
		if (!_open) {
			try {
				_link.sendRequestWait(_device, Priority.SYSTEM, Tpdu.control(Tpdu.CONNECT));
			} catch (KNXException ex) {
				fail("No connection to " + _device + ": " + ex.getMessage(), ex);
				return;
			}
			_open = true;
			_sequence.reset();
		}
		cancelIdle();
		request._tpdu = Tpdu.connected(request._apdu, _sequence.send());
		transmit(request);
	}

	private void transmit(Request request) {
		cancel(request);
		try {
			_link.sendRequestWait(_device, Priority.LOW, request._tpdu);
		} catch (KNXException ex) {
			// Not confirmed by the link: handled like a missing acknowledgement.
			Logger.info("Sending to " + _device + " not confirmed: " + ex.getMessage(), TransportClient.class);
		}
		long generation = ++_generation;
		request._timer = _executor.schedule(() -> onAckTimeout(request, generation), _timing.ackTimeout().toMillis(),
			TimeUnit.MILLISECONDS);
	}

	private void onFrame(CEMILData frame) {
		KNXAddress destination = frame.getDestination();
		if (!(destination instanceof IndividualAddress)) {
			return;
		}
		IndividualAddress own = _link.getKNXMedium().getDeviceAddress();
		if (own.getRawAddress() != 0 && !destination.equals(own)) {
			return;
		}
		byte[] tpdu = frame.getPayload();
		if (tpdu.length == 0) {
			return;
		}
		if (Tpdu.is(tpdu, Tpdu.DISCONNECT)) {
			onDisconnect();
		} else if (Tpdu.isAcknowledgement(tpdu, Tpdu.ACK)) {
			onAck(Tpdu.sequence(tpdu));
		} else if (Tpdu.isAcknowledgement(tpdu, Tpdu.NAK)) {
			onNak(Tpdu.sequence(tpdu));
		} else if (Tpdu.isConnectedData(tpdu)) {
			onData(tpdu);
		}
	}

	private void onDisconnect() {
		if (!_open) {
			return;
		}
		_open = false;
		cancelIdle();
		Request request = _current;
		if (request != null) {
			if (request._final) {
				complete(End.DEVICE);
			} else {
				fail("The device " + _device + " ended the connection.");
			}
		}
	}

	private void onAck(int sequence) {
		if (!_open) {
			return;
		}
		Request request = _current;
		boolean awaiting = request != null && request._tpdu != null && !request._acknowledged;
		switch (_sequence.acknowledged(sequence, awaiting)) {
			case CURRENT:
				cancel(request);
				request._acknowledged = true;
				if (request._final) {
					close(true);
					complete(End.CLIENT);
				} else if (request._answer == null) {
					complete(null);
					restartIdle();
				} else if (request._response != null) {
					complete(request._response);
					restartIdle();
				} else {
					long generation = ++_generation;
					request._timer = _executor.schedule(() -> onResponseTimeout(request, generation),
						_timing.responseTimeout().toMillis(), TimeUnit.MILLISECONDS);
				}
				break;
			case STALE:
				_staleAcks++;
				Logger.info("Acknowledgement " + sequence + " of " + _device + " received again, ignored.",
					TransportClient.class);
				break;
			case OUT_OF_SEQUENCE:
				close(true);
				fail("The device " + _device + " acknowledged " + sequence + ", expected " + _sequence.send() + ".");
				break;
		}
	}

	private void onNak(int sequence) {
		if (!_open) {
			return;
		}
		Request request = _current;
		if (request != null && !request._acknowledged && sequence == _sequence.send()
			&& request._repeat < _timing.repetitions()) {
			request._repeat++;
			transmit(request);
		} else {
			close(true);
			fail("The device " + _device + " refused the data unit " + sequence + " (T_NAK).");
		}
	}

	private void onData(byte[] tpdu) {
		if (!_open) {
			// Data outside a connection: the device is told that there is none.
			send(Tpdu.control(Tpdu.DISCONNECT));
			return;
		}
		int sequence = Tpdu.sequence(tpdu);
		switch (_sequence.received(sequence)) {
			case NEW: {
				send(Tpdu.acknowledgement(Tpdu.ACK, sequence));
				Request request = _current;
				if (request != null && request._answer != null && request._response == null
					&& request._answer.test(tpdu)) {
					request._response = tpdu;
					if (request._acknowledged) {
						cancel(request);
						complete(tpdu);
					}
				} else {
					Logger.info("Data of " + _device + " that answers no request, ignored.", TransportClient.class);
				}
				if (_current == null) {
					restartIdle();
				}
				break;
			}
			case REPEAT:
				send(Tpdu.acknowledgement(Tpdu.ACK, sequence));
				break;
			case OUT_OF_SEQUENCE:
				send(Tpdu.acknowledgement(Tpdu.NAK, sequence));
				break;
		}
	}

	private void onAckTimeout(Request request, long generation) {
		if (request != _current || generation != _generation || request._acknowledged) {
			return;
		}
		if (request._repeat < _timing.repetitions()) {
			request._repeat++;
			transmit(request);
			return;
		}
		close(true);
		if (request._final) {
			complete(End.UNACKNOWLEDGED);
			return;
		}
		fail("The device " + _device + " did not acknowledge (" + (_timing.repetitions() + 1) + " times sent).");
	}

	private void onResponseTimeout(Request request, long generation) {
		if (request != _current || generation != _generation) {
			return;
		}
		fail("The device " + _device + " did not answer within " + _timing.responseTimeout().toMillis() + " ms.");
		restartIdle();
	}

	private void restartIdle() {
		cancelIdle();
		if (!_open) {
			return;
		}
		long generation = _generation;
		_idleTimer = _executor.schedule(() -> {
			if (_open && _current == null && generation == _generation) {
				close(true);
			}
		}, _timing.idleTimeout().toMillis(), TimeUnit.MILLISECONDS);
	}

	private void cancelIdle() {
		if (_idleTimer != null) {
			_idleTimer.cancel(false);
			_idleTimer = null;
		}
	}

	private void cancel(Request request) {
		if (request != null && request._timer != null) {
			request._timer.cancel(false);
			request._timer = null;
		}
		_generation++;
	}

	private void close(boolean sendDisconnect) {
		if (_open && sendDisconnect) {
			send(Tpdu.control(Tpdu.DISCONNECT));
		}
		_open = false;
		cancelIdle();
	}

	private void send(byte[] tpdu) {
		try {
			_link.sendRequest(_device, Priority.SYSTEM, tpdu);
		} catch (KNXException ex) {
			Logger.info("Sending a control unit to " + _device + " failed: " + ex.getMessage(), TransportClient.class);
		}
	}

	private void complete(Object outcome) {
		Request request = _current;
		_current = null;
		if (request != null) {
			cancel(request);
			request._outcome.complete(outcome);
		}
	}

	private void fail(String message) {
		fail(message, null);
	}

	private void fail(String message, Throwable cause) {
		Request request = _current;
		_current = null;
		if (request != null) {
			cancel(request);
			request._outcome.completeExceptionally(new TransportException(message, cause));
		}
	}

}
