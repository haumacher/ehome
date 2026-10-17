/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.server;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.Priority;
import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.NetworkLinkListener;

import com.top_logic.basic.Logger;

import de.haumacher.ehome.virtual.device.SimulatedDevice;
import de.haumacher.ehome.virtual.trace.TraceEntry;
import de.haumacher.ehome.virtual.trace.TraceEntry.Direction;
import de.haumacher.ehome.virtual.trace.TraceRecorder;

/**
 * The transport and application layer of the simulated device on its link to the virtual subnet:
 * the connection-oriented transport layer of KNX (connect, disconnect, sequence numbers,
 * acknowledgements, repetitions, idle timeout) and the management services, answered by the
 * {@link SimulatedDevice}.
 *
 * <p>
 * Calimero's own device stack (<code>BaseKnxDevice</code> with its <code>TransportLayerImpl</code>)
 * is not used: its timing is fixed to the values of a bus (3 s, 3 repetitions, 6 s idle), and frames
 * that arrive between the end of its wait for an acknowledgement and the end of the send are left in
 * its queue until the next send (a race of <code>TransportLayerImpl</code> 2.6, seen under load as a
 * request answered only after the tool's repetition, followed by a disconnect for an acknowledgement
 * out of sequence). Here every frame and every timer is handled on one thread, in order.
 * </p>
 *
 * <p>
 * Deviations from the strict state machine, each recorded as an event of the trace: an
 * acknowledgement of the previous sequence number arriving again (a tool that received a service
 * twice acknowledges it twice) is ignored instead of ending the connection; the timing is that of
 * {@link TransportTiming}. A connect of a second partner while a connection is open is refused with
 * a disconnect.
 * </p>
 *
 * <p>
 * Services answered: device descriptor (type 0), memory read and write (with the answer in verify
 * mode), property value read and write, property description (always "no such property"), restart
 * (no answer for a basic restart; the confirmation for a master reset), authorize (access level 0:
 * no keys are modeled). Every other service is recorded and not answered.
 * </p>
 */
public final class DeviceStack implements NetworkLinkListener, AutoCloseable {

	private static final int T_CONNECT = 0x80;

	private static final int T_DISCONNECT = 0x81;

	private static final int T_ACK = 0xC2;

	private static final int T_NAK = 0xC3;

	private static final int DATA_CONNECTED = 0x40;

	private static final int MEMORY_READ = 0x0200;

	private static final int MEMORY_RESPONSE = 0x0240;

	private static final int MEMORY_WRITE = 0x0280;

	private static final int DESCRIPTOR_READ = 0x0300;

	private static final int DESCRIPTOR_RESPONSE = 0x0340;

	private static final int RESTART = 0x0380;

	private static final int AUTHORIZE_REQUEST = 0x03D1;

	private static final int AUTHORIZE_RESPONSE = 0x03D2;

	private static final int PROPERTY_READ = 0x03D5;

	private static final int PROPERTY_RESPONSE = 0x03D6;

	private static final int PROPERTY_WRITE = 0x03D7;

	private static final int PROPERTY_DESCRIPTION_READ = 0x03D8;

	private static final int PROPERTY_DESCRIPTION_RESPONSE = 0x03D9;

	private static final int NO_PARTNER = -1;

	private final SimulatedDevice _device;

	private final KNXNetworkLink _link;

	private final TraceRecorder _recorder;

	private final TransportTiming _timing;

	private final ScheduledExecutorService _executor;

	/** The partner of the open connection, {@link #NO_PARTNER} if none is open. */
	private int _partner = NO_PARTNER;

	private int _sequenceSend;

	private int _sequenceReceive;

	/** The service sent in the connection and not acknowledged yet, <code>null</code> if none. */
	private byte[] _pending;

	private int _repeat;

	/** The services waiting until the pending one is acknowledged. */
	private final Deque<byte[]> _queue = new ArrayDeque<>();

	private ScheduledFuture<?> _ackTimer;

	private ScheduledFuture<?> _connectionTimer;

	/** Counts the timers, so that one cancelled too late does nothing. */
	private long _timerGeneration;

	/**
	 * Creates a {@link DeviceStack} serving the device on the given link, recording its events.
	 */
	public DeviceStack(SimulatedDevice device, KNXNetworkLink link, TraceRecorder recorder, TransportTiming timing) {
		_device = device;
		_link = link;
		_recorder = recorder;
		// The frames are recorded here, in the order the device sees and sends them.
		_timing = timing;
		_executor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread thread = new Thread(r, "E-Home virtual device " + device.name());
			thread.setDaemon(true);
			return thread;
		});
		link.addLinkListener(this);
	}

	@Override
	public void indication(FrameEvent e) {
		if (e.getFrame() instanceof CEMILData frame) {
			// Recorded on the receiving thread before the device handles it, a frame the device sends
			// before it leaves (see send()): the trace is in causal order.
			record(frame, true);
			execute(() -> handle(frame));
		}
	}

	private void record(CEMILData frame, boolean incoming) {
		if (incoming && frame.getDestination() instanceof IndividualAddress destination
			&& destination.getRawAddress() != _device.address()) {
			return;
		}
		try {
			TraceEntry entry = FrameDecoder.decode(Instant.now(), frame, incoming);
			if (entry != null) {
				_recorder.record(entry);
			}
		} catch (RuntimeException ex) {
			Logger.error("Recording a frame of the virtual device failed: " + frame, ex, DeviceStack.class);
		}
	}

	@Override
	public void confirmation(FrameEvent e) {
		// Nothing to do.
	}

	@Override
	public void linkClosed(tuwien.auto.calimero.CloseEvent e) {
		// Closed by the interface.
	}

	@Override
	public void close() {
		_link.removeLinkListener(this);
		_executor.shutdownNow();
		try {
			_executor.awaitTermination(1, TimeUnit.SECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private void execute(Runnable task) {
		if (_executor.isShutdown()) {
			return;
		}
		_executor.execute(() -> {
			try {
				task.run();
			} catch (RuntimeException ex) {
				Logger.error("The virtual device " + _device.name() + " failed.", ex, DeviceStack.class);
			}
		});
	}

	private void handle(CEMILData frame) {
		byte[] tpdu = frame.getPayload();
		if (tpdu.length == 0) {
			return;
		}
		int source = frame.getSource().getRawAddress();
		KNXAddress destination = frame.getDestination();
		if (!(destination instanceof IndividualAddress)) {
			// A broadcast (no service the device answers: it is never in programming mode) or a group
			// telegram (no process communication).
			return;
		}
		if (destination.getRawAddress() != _device.address()) {
			return;
		}
		int control = tpdu[0] & 0xFF;
		if ((control & 0x80) != 0) {
			int sequence = (control >>> 2) & 0x0F;
			if (control == T_CONNECT) {
				onConnect(source);
			} else if (control == T_DISCONNECT) {
				if (source == _partner) {
					closeConnection(false);
				}
			} else if ((control & 0xC3) == T_ACK) {
				onAck(source, sequence);
			} else if ((control & 0xC3) == T_NAK) {
				onNak(source, sequence);
			}
			return;
		}
		if ((control & DATA_CONNECTED) != 0) {
			int sequence = (control >>> 2) & 0x0F;
			if (source != _partner) {
				event(source, TraceEntry.TL_PROTOCOL_ERROR, sequence, "data outside a connection");
				sendControl(source, T_DISCONNECT);
				return;
			}
			restartConnectionTimer();
			if (sequence == _sequenceReceive) {
				sendControl(source, T_ACK | sequence << 2);
				_sequenceReceive = (sequence + 1) & 0x0F;
				byte[] answer = answer(tpdu);
				if (answer != null) {
					sendConnected(answer);
				}
				if (restart(tpdu) && _timing.disconnectOnRestart()) {
					closeConnection(true);
				}
			} else if (sequence == ((_sequenceReceive - 1) & 0x0F)) {
				// A repetition of the service just received: acknowledged again, not executed again.
				sendControl(source, T_ACK | sequence << 2);
			} else {
				sendControl(source, T_NAK | sequence << 2);
			}
			return;
		}
		// Connectionless to the device: answered connectionless.
		byte[] answer = answer(tpdu);
		if (answer != null) {
			send(source, Priority.LOW, answer);
		}
	}

	private void onConnect(int source) {
		if (_partner != NO_PARTNER && _partner != source) {
			event(source, TraceEntry.TL_REJECTED, null, "connected to " + new IndividualAddress(_partner));
			sendControl(source, T_DISCONNECT);
			return;
		}
		// A new connection, or the partner connects again: sequence numbers start over.
		_partner = source;
		_sequenceSend = 0;
		_sequenceReceive = 0;
		_pending = null;
		_queue.clear();
		cancelAckTimer();
		restartConnectionTimer();
	}

	private void onAck(int source, int sequence) {
		if (source != _partner) {
			sendControl(source, T_DISCONNECT);
			return;
		}
		if (_pending != null && sequence == _sequenceSend) {
			cancelAckTimer();
			_pending = null;
			_sequenceSend = (_sequenceSend + 1) & 0x0F;
			restartConnectionTimer();
			sendNext();
		} else if (sequence == ((_sequenceSend - 1) & 0x0F)) {
			event(source, TraceEntry.TL_STALE_ACK, sequence, "acknowledged before, ignored");
		} else {
			event(source, TraceEntry.TL_PROTOCOL_ERROR, sequence, "T_Ack out of sequence, expected " + _sequenceSend);
			closeConnection(true);
		}
	}

	private void onNak(int source, int sequence) {
		if (source != _partner) {
			sendControl(source, T_DISCONNECT);
			return;
		}
		if (_pending != null && sequence == _sequenceSend && _repeat < _timing.repetitions()) {
			_repeat++;
			transmitPending();
		} else {
			event(source, TraceEntry.TL_PROTOCOL_ERROR, sequence, "T_Nak");
			closeConnection(true);
		}
	}

	private void sendConnected(byte[] apdu) {
		_queue.add(apdu);
		if (_pending == null) {
			sendNext();
		}
	}

	private void sendNext() {
		byte[] apdu = _queue.poll();
		if (apdu == null || _partner == NO_PARTNER) {
			return;
		}
		byte[] tpdu = apdu.clone();
		tpdu[0] = (byte) (apdu[0] & 0x03 | DATA_CONNECTED | _sequenceSend << 2);
		_pending = tpdu;
		_repeat = 0;
		transmitPending();
	}

	private void transmitPending() {
		send(_partner, Priority.LOW, _pending);
		cancelAckTimer();
		long generation = ++_timerGeneration;
		_ackTimer = _executor.schedule(() -> execute(() -> onAckTimeout(generation)), _timing.ackTimeout().toMillis(),
			TimeUnit.MILLISECONDS);
	}

	private void onAckTimeout(long generation) {
		if (generation != _timerGeneration || _pending == null) {
			return;
		}
		if (_repeat < _timing.repetitions()) {
			_repeat++;
			event(_partner, TraceEntry.TL_ACK_TIMEOUT, _sequenceSend, null);
			transmitPending();
		} else {
			event(_partner, TraceEntry.TL_REPETITIONS_EXHAUSTED, _sequenceSend, null);
			closeConnection(true);
		}
	}

	private void restartConnectionTimer() {
		if (_connectionTimer != null) {
			_connectionTimer.cancel(false);
		}
		int partner = _partner;
		_connectionTimer = _executor.schedule(() -> execute(() -> onConnectionTimeout(partner)),
			_timing.connectionTimeout().toMillis(), TimeUnit.MILLISECONDS);
	}

	private void onConnectionTimeout(int partner) {
		if (_partner == NO_PARTNER || _partner != partner || _connectionTimer == null || !_connectionTimer.isDone()) {
			return;
		}
		event(_partner, TraceEntry.TL_CONNECTION_TIMEOUT, null, null);
		closeConnection(true);
	}

	private void cancelAckTimer() {
		if (_ackTimer != null) {
			_ackTimer.cancel(false);
			_ackTimer = null;
		}
		_timerGeneration++;
	}

	private void closeConnection(boolean sendDisconnect) {
		if (_partner == NO_PARTNER) {
			return;
		}
		if (sendDisconnect) {
			sendControl(_partner, T_DISCONNECT);
		}
		_partner = NO_PARTNER;
		_pending = null;
		_queue.clear();
		cancelAckTimer();
		if (_connectionTimer != null) {
			_connectionTimer.cancel(false);
			_connectionTimer = null;
		}
	}

	private void sendControl(int destination, int control) {
		send(destination, Priority.SYSTEM, new byte[] { (byte) control });
	}

	private void send(int destination, Priority priority, byte[] tpdu) {
		IndividualAddress receiver = new IndividualAddress(destination);
		try {
			record(new CEMILData(CEMILData.MC_LDATA_REQ, new IndividualAddress(_device.address()), receiver, tpdu,
				priority), false);
			_link.sendRequest(receiver, priority, tpdu);
		} catch (KNXException ex) {
			Logger.error("The virtual device " + _device.name() + " could not send to " + new IndividualAddress(destination)
				+ ".", ex, DeviceStack.class);
		}
	}

	private void event(int partner, String service, Integer sequence, String reason) {
		Map<String, String> fields = new LinkedHashMap<>();
		if (TraceEntry.TL_ACK_TIMEOUT.equals(service)) {
			fields.put(TraceEntry.REPEAT, Integer.toString(_repeat));
		}
		if (reason != null) {
			fields.put(TraceEntry.REASON, reason.replace(' ', '_'));
		}
		_recorder.record(new TraceEntry(Instant.now(), Direction.EVENT, _device.address(), partner, service, sequence,
			fields, null));
	}

	private static boolean restart(byte[] tpdu) {
		return tpdu.length >= 2 && (DataUnitBuilder.getAPDUService(tpdu) & 0x3C0) == RESTART && (tpdu[1] & 0x01) == 0;
	}

	/**
	 * The answer of the device to a service, <code>null</code> for none.
	 */
	private byte[] answer(byte[] tpdu) {
		if (tpdu.length < 2) {
			return null;
		}
		int service = DataUnitBuilder.getAPDUService(tpdu);
		byte[] asdu = DataUnitBuilder.extractASDU(tpdu);
		if ((service & 0x3C0) == DESCRIPTOR_READ) {
			if (asdu.length < 1 || (asdu[0] & 0x3F) != 0) {
				return null;
			}
			return DataUnitBuilder.createAPDU(DESCRIPTOR_RESPONSE, _device.descriptor());
		}
		if ((service & 0x3C0) == RESTART) {
			_device.reset();
			if ((tpdu[1] & 0x01) == 0) {
				return null;
			}
			// Master reset: confirmed with no error and no process time.
			return DataUnitBuilder.createLengthOptimizedAPDU(RESTART, (byte) 0x21, (byte) 0, (byte) 0, (byte) 0);
		}
		switch (service) {
			case MEMORY_READ: {
				if (asdu.length != 3) {
					return null;
				}
				int count = asdu[0] & 0x3F;
				byte[] data = _device.memory(address(asdu), count);
				return memoryResponse(data == null ? 0 : count, asdu, data);
			}
			case MEMORY_WRITE: {
				if (asdu.length < 3) {
					return null;
				}
				int count = asdu[0] & 0x3F;
				byte[] data = Arrays.copyOfRange(asdu, 3, asdu.length);
				if (data.length != count) {
					return null;
				}
				boolean accepted = _device.putMemory(address(asdu), data);
				return _device.verifyMode() ? memoryResponse(accepted ? count : 0, asdu, accepted ? data : null) : null;
			}
			case PROPERTY_READ: {
				if (asdu.length != 4) {
					return null;
				}
				int count = (asdu[2] & 0xF0) >>> 4;
				int start = (asdu[2] & 0x0F) << 8 | asdu[3] & 0xFF;
				byte[] value = _device.property(asdu[0] & 0xFF, asdu[1] & 0xFF, start, count);
				return propertyResponse(asdu, value == null ? 0 : start == 0 ? 1 : count, value);
			}
			case PROPERTY_WRITE: {
				if (asdu.length < 5) {
					return null;
				}
				int count = (asdu[2] & 0xF0) >>> 4;
				int start = (asdu[2] & 0x0F) << 8 | asdu[3] & 0xFF;
				byte[] answer = _device.putProperty(asdu[0] & 0xFF, asdu[1] & 0xFF, start, count,
					Arrays.copyOfRange(asdu, 4, asdu.length));
				return propertyResponse(asdu, answer == null ? 0 : count, answer);
			}
			case PROPERTY_DESCRIPTION_READ:
				if (asdu.length != 3) {
					return null;
				}
				// No such property: type 0, no elements, no access.
				return DataUnitBuilder.createAPDU(PROPERTY_DESCRIPTION_RESPONSE, asdu[0], asdu[1], asdu[2], (byte) 0,
					(byte) 0, (byte) 0, (byte) 0);
			case AUTHORIZE_REQUEST:
				// No keys are modeled: every key gives access level 0.
				return DataUnitBuilder.createAPDU(AUTHORIZE_RESPONSE, (byte) 0);
			default:
				return null;
		}
	}

	private static int address(byte[] asdu) {
		return (asdu[1] & 0xFF) << 8 | asdu[2] & 0xFF;
	}

	private static byte[] memoryResponse(int count, byte[] request, byte[] data) {
		byte[] asdu = new byte[3 + (count == 0 || data == null ? 0 : data.length)];
		asdu[0] = (byte) count;
		asdu[1] = request[1];
		asdu[2] = request[2];
		if (asdu.length > 3) {
			System.arraycopy(data, 0, asdu, 3, data.length);
		}
		return DataUnitBuilder.createLengthOptimizedAPDU(MEMORY_RESPONSE, asdu);
	}

	private static byte[] propertyResponse(byte[] request, int count, byte[] value) {
		byte[] data = count == 0 || value == null ? new byte[0] : value;
		byte[] asdu = new byte[4 + data.length];
		asdu[0] = request[0];
		asdu[1] = request[1];
		asdu[2] = (byte) (count << 4 | request[2] & 0x0F);
		asdu[3] = request[3];
		System.arraycopy(data, 0, asdu, 4, data.length);
		return DataUnitBuilder.createAPDU(PROPERTY_RESPONSE, asdu);
	}

}
