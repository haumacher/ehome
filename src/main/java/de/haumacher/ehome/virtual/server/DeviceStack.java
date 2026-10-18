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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.GroupAddress;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.Priority;
import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.NetworkLinkListener;

import com.top_logic.basic.Logger;

import de.haumacher.ehome.knx.transport.ConnectionSequence;
import de.haumacher.ehome.knx.transport.Tpdu;
import de.haumacher.ehome.virtual.device.SimulatedDevice;
import de.haumacher.ehome.virtual.trace.TraceEntry;
import de.haumacher.ehome.virtual.trace.TraceEntry.Direction;
import de.haumacher.ehome.virtual.trace.TraceFormat;
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
 * out of sequence). Here every frame and every timer is handled on one thread, in order. The rules of
 * the sequence numbers and the units are those of E-Home's client side
 * ({@link de.haumacher.ehome.knx.transport.ConnectionSequence}, {@link Tpdu}).
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
 * (no answer for a basic restart; the confirmation for a master reset; a restart ends the
 * programming mode), authorize (access level 0: no keys are modeled). In programming mode (the
 * button, {@link #pressProgrammingButton()}): the broadcasts individual address read (answered with
 * the device's address) and write (the device takes the address, see
 * {@link SimulatedDevice#changeAddress(int)}); outside of it the device ignores them, as real
 * hardware does. Every other service is recorded and not answered.
 * </p>
 */
public final class DeviceStack implements NetworkLinkListener, AutoCloseable {

	private static final int NO_PARTNER = -1;

	private final SimulatedDevice _device;

	private final KNXNetworkLink _link;

	private final TraceRecorder _recorder;

	private final TransportTiming _timing;

	private final ScheduledExecutorService _executor;

	/**
	 * The address of the coupler that routes frames to the device (area.line.0, area.0.0 for an
	 * area), -1 if every address is reachable.
	 */
	private final int _coupler;

	/** The partner of the open connection, {@link #NO_PARTNER} if none is open. */
	private int _partner = NO_PARTNER;

	private final ConnectionSequence _sequence = new ConnectionSequence();

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
		this(device, link, recorder, timing, -1);
	}

	/**
	 * Creates a {@link DeviceStack} behind a coupler that routes only the frames of its line (area);
	 * a new address outside of it is recorded as not reachable.
	 *
	 * @param coupler
	 *        The coupler's address (area.line.0, area.0.0 for an area coupler), -1 for none.
	 */
	public DeviceStack(SimulatedDevice device, KNXNetworkLink link, TraceRecorder recorder, TransportTiming timing,
			int coupler) {
		_coupler = coupler;
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
			if (frame.getSource().getRawAddress() == _device.address()) {
				// The device's own frame (a broadcast the virtual subnet indicates to every device link,
				// the sender's included): a real device does not receive what it sends.
				return;
			}
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
		try {
			_executor.execute(() -> {
				try {
					task.run();
				} catch (RuntimeException ex) {
					Logger.error("The virtual device " + _device.name() + " failed.", ex, DeviceStack.class);
				}
			});
		} catch (RejectedExecutionException ex) {
			// Closed meanwhile (a timer firing while the interface stops).
		}
	}

	private void handle(CEMILData frame) {
		byte[] tpdu = frame.getPayload();
		if (tpdu.length == 0) {
			return;
		}
		int source = frame.getSource().getRawAddress();
		KNXAddress destination = frame.getDestination();
		if (!(destination instanceof IndividualAddress)) {
			if (destination.getRawAddress() == 0 && !Tpdu.isControl(tpdu)) {
				onBroadcast(tpdu);
			}
			// A group telegram: no process communication.
			return;
		}
		if (destination.getRawAddress() != _device.address()) {
			return;
		}
		if (Tpdu.isControl(tpdu)) {
			int sequence = Tpdu.sequence(tpdu);
			if (Tpdu.is(tpdu, Tpdu.CONNECT)) {
				onConnect(source);
			} else if (Tpdu.is(tpdu, Tpdu.DISCONNECT)) {
				if (source == _partner) {
					closeConnection(false);
				}
			} else if (Tpdu.isAcknowledgement(tpdu, Tpdu.ACK)) {
				onAck(source, sequence);
			} else if (Tpdu.isAcknowledgement(tpdu, Tpdu.NAK)) {
				onNak(source, sequence);
			}
			return;
		}
		if (Tpdu.isConnectedData(tpdu)) {
			int sequence = Tpdu.sequence(tpdu);
			if (source != _partner) {
				event(source, TraceEntry.TL_PROTOCOL_ERROR, sequence, "data outside a connection");
				sendControl(source, Tpdu.control(Tpdu.DISCONNECT));
				return;
			}
			restartConnectionTimer();
			switch (_sequence.received(sequence)) {
				case NEW: {
					sendControl(source, Tpdu.acknowledgement(Tpdu.ACK, sequence));
					byte[] answer = answer(tpdu);
					if (answer != null) {
						sendConnected(answer);
					}
					if (restart(tpdu) && _timing.disconnectOnRestart()) {
						closeConnection(true);
					}
					break;
				}
				case REPEAT:
					// A repetition of the service just received: acknowledged again, not executed again.
					sendControl(source, Tpdu.acknowledgement(Tpdu.ACK, sequence));
					break;
				case OUT_OF_SEQUENCE:
					sendControl(source, Tpdu.acknowledgement(Tpdu.NAK, sequence));
					break;
			}
			return;
		}
		// Connectionless to the device: answered connectionless.
		byte[] answer = answer(tpdu);
		if (answer != null) {
			send(source, Priority.LOW, answer);
		}
	}

	/**
	 * A broadcast: the services of programming the individual address, served in programming mode
	 * only.
	 */
	private void onBroadcast(byte[] tpdu) {
		if (tpdu.length < 2 || !_device.programmingMode()) {
			return;
		}
		int service = Tpdu.service(tpdu);
		if (service == Tpdu.INDIVIDUAL_ADDRESS_READ) {
			sendBroadcast(DataUnitBuilder.createLengthOptimizedAPDU(Tpdu.INDIVIDUAL_ADDRESS_RESPONSE));
		} else if (service == Tpdu.INDIVIDUAL_ADDRESS_WRITE) {
			byte[] asdu = Tpdu.asdu(tpdu);
			if (asdu.length < 2) {
				return;
			}
			int previous = _device.address();
			int address = (asdu[0] & 0xFF) << 8 | asdu[1] & 0xFF;
			if (address == previous || !_device.changeAddress(address)) {
				return;
			}
			// The frames to the new address reach the device from now on, and it sends from it.
			_link.getKNXMedium().setDeviceAddress(new IndividualAddress(address));
			Map<String, String> fields = new LinkedHashMap<>();
			fields.put(TraceEntry.INDIVIDUAL, TraceFormat.individual(address));
			fields.put(TraceEntry.PREVIOUS, TraceFormat.individual(previous));
			fields.put(TraceEntry.REASON, (reachable(address) ? "the project keeps " + TraceFormat.individual(_device.initialAddress())
				: "not reachable through the interface, which routes only " + TraceFormat.individual(_coupler)
					+ ", the project keeps " + TraceFormat.individual(_device.initialAddress())).replace(' ', '_'));
			_recorder.record(new TraceEntry(Instant.now(), Direction.EVENT, address, 0, TraceEntry.DEV_ADDRESS_CHANGED, null,
				fields, null));
		}
	}

	private boolean reachable(int address) {
		return _coupler < 0 || routes(_coupler, address);
	}

	/**
	 * Whether a coupler routes the frames to the given address: an address of its line, or of its
	 * area for an area coupler (area.0.0), as Calimero's server gateway decides.
	 */
	public static boolean routes(int coupler, int address) {
		if ((coupler & 0x0F00) == 0) {
			return (address & 0xF000) == (coupler & 0xF000);
		}
		return (address & 0xFF00) == (coupler & 0xFF00);
	}

	/**
	 * Presses the programming button of the device: programming mode on, or off if it is on; recorded
	 * as an event.
	 *
	 * @return Whether the device is in programming mode now.
	 */
	public boolean pressProgrammingButton() {
		boolean on = _device.pressProgrammingButton();
		programmingModeEvent(on, "button");
		return on;
	}

	private void programmingModeEvent(boolean on, String reason) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put(TraceEntry.MODE, on ? "on" : "off");
		fields.put(TraceEntry.REASON, reason);
		_recorder.record(new TraceEntry(Instant.now(), Direction.EVENT, _device.address(), 0,
			TraceEntry.DEV_PROGRAMMING_MODE, null, fields, null));
	}

	private void onConnect(int source) {
		if (_partner != NO_PARTNER && _partner != source) {
			event(source, TraceEntry.TL_REJECTED, null, "connected to " + new IndividualAddress(_partner));
			sendControl(source, Tpdu.control(Tpdu.DISCONNECT));
			return;
		}
		// A new connection, or the partner connects again: sequence numbers start over.
		_partner = source;
		_sequence.reset();
		_pending = null;
		_queue.clear();
		cancelAckTimer();
		restartConnectionTimer();
	}

	private void onAck(int source, int sequence) {
		if (source != _partner) {
			sendControl(source, Tpdu.control(Tpdu.DISCONNECT));
			return;
		}
		switch (_sequence.acknowledged(sequence, _pending != null)) {
			case CURRENT:
				cancelAckTimer();
				_pending = null;
				restartConnectionTimer();
				sendNext();
				break;
			case STALE:
				event(source, TraceEntry.TL_STALE_ACK, sequence, "acknowledged before, ignored");
				break;
			case OUT_OF_SEQUENCE:
				event(source, TraceEntry.TL_PROTOCOL_ERROR, sequence, "T_Ack out of sequence, expected " + _sequence.send());
				closeConnection(true);
				break;
		}
	}

	private void onNak(int source, int sequence) {
		if (source != _partner) {
			sendControl(source, Tpdu.control(Tpdu.DISCONNECT));
			return;
		}
		if (_pending != null && sequence == _sequence.send() && _repeat < _timing.repetitions()) {
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
			long delay = _timing.responseDelay().toMillis();
			if (delay > 0) {
				_executor.schedule(() -> execute(this::sendNextIfIdle), delay, TimeUnit.MILLISECONDS);
			} else {
				sendNext();
			}
		}
	}

	private void sendNextIfIdle() {
		if (_pending == null) {
			sendNext();
		}
	}

	private void sendNext() {
		byte[] apdu = _queue.poll();
		if (apdu == null || _partner == NO_PARTNER) {
			return;
		}
		_pending = Tpdu.connected(apdu, _sequence.send());
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
			event(_partner, TraceEntry.TL_ACK_TIMEOUT, _sequence.send(), null);
			transmitPending();
		} else {
			event(_partner, TraceEntry.TL_REPETITIONS_EXHAUSTED, _sequence.send(), null);
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
			sendControl(_partner, Tpdu.control(Tpdu.DISCONNECT));
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

	private void sendBroadcast(byte[] tpdu) {
		GroupAddress broadcast = GroupAddress.Broadcast;
		try {
			record(new CEMILData(CEMILData.MC_LDATA_REQ, new IndividualAddress(_device.address()), broadcast, tpdu,
				Priority.SYSTEM), false);
			_link.sendRequest(broadcast, Priority.SYSTEM, tpdu);
		} catch (KNXException ex) {
			Logger.error("The virtual device " + _device.name() + " could not send a broadcast.", ex, DeviceStack.class);
		}
	}

	private void sendControl(int destination, byte[] tpdu) {
		send(destination, Priority.SYSTEM, tpdu);
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
		return tpdu.length >= 2 && (Tpdu.service(tpdu) & 0x3C0) == Tpdu.RESTART && (tpdu[1] & 0x01) == 0;
	}

	/**
	 * The answer of the device to a service, <code>null</code> for none.
	 */
	private byte[] answer(byte[] tpdu) {
		if (tpdu.length < 2) {
			return null;
		}
		int service = Tpdu.service(tpdu);
		byte[] asdu = Tpdu.asdu(tpdu);
		if ((service & 0x3C0) == Tpdu.DESCRIPTOR_READ) {
			if (asdu.length < 1 || (asdu[0] & 0x3F) != 0) {
				return null;
			}
			return DataUnitBuilder.createAPDU(Tpdu.DESCRIPTOR_RESPONSE, _device.descriptor());
		}
		if ((service & 0x3C0) == Tpdu.RESTART) {
			if (_device.reset()) {
				programmingModeEvent(false, "restart");
			}
			if ((tpdu[1] & 0x01) == 0) {
				return null;
			}
			// Master reset: confirmed with no error and no process time.
			return DataUnitBuilder.createLengthOptimizedAPDU(Tpdu.RESTART, (byte) 0x21, (byte) 0, (byte) 0, (byte) 0);
		}
		switch (service) {
			case Tpdu.MEMORY_READ: {
				if (asdu.length != 3) {
					return null;
				}
				int count = asdu[0] & 0x3F;
				byte[] data = _device.memory(address(asdu), count);
				return memoryResponse(data == null ? 0 : count, asdu, data);
			}
			case Tpdu.MEMORY_WRITE: {
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
			case Tpdu.PROPERTY_READ: {
				if (asdu.length != 4) {
					return null;
				}
				int count = (asdu[2] & 0xF0) >>> 4;
				int start = (asdu[2] & 0x0F) << 8 | asdu[3] & 0xFF;
				byte[] value = _device.property(asdu[0] & 0xFF, asdu[1] & 0xFF, start, count);
				return propertyResponse(asdu, value == null ? 0 : start == 0 ? 1 : count, value);
			}
			case Tpdu.PROPERTY_WRITE: {
				if (asdu.length < 5) {
					return null;
				}
				int count = (asdu[2] & 0xF0) >>> 4;
				int start = (asdu[2] & 0x0F) << 8 | asdu[3] & 0xFF;
				byte[] answer = _device.putProperty(asdu[0] & 0xFF, asdu[1] & 0xFF, start, count,
					Arrays.copyOfRange(asdu, 4, asdu.length));
				return propertyResponse(asdu, answer == null ? 0 : count, answer);
			}
			case Tpdu.PROPERTY_DESCRIPTION_READ:
				if (asdu.length != 3) {
					return null;
				}
				// No such property: type 0, no elements, no access.
				return DataUnitBuilder.createAPDU(Tpdu.PROPERTY_DESCRIPTION_RESPONSE, asdu[0], asdu[1], asdu[2], (byte) 0,
					(byte) 0, (byte) 0, (byte) 0);
			case Tpdu.AUTHORIZE_REQUEST:
				// No keys are modeled: every key gives access level 0.
				return DataUnitBuilder.createAPDU(Tpdu.AUTHORIZE_RESPONSE, (byte) 0);
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
		return DataUnitBuilder.createLengthOptimizedAPDU(Tpdu.MEMORY_RESPONSE, asdu);
	}

	private static byte[] propertyResponse(byte[] request, int count, byte[] value) {
		byte[] data = count == 0 || value == null ? new byte[0] : value;
		byte[] asdu = new byte[4 + data.length];
		asdu[0] = request[0];
		asdu[1] = request[1];
		asdu[2] = (byte) (count << 4 | request[2] & 0x0F);
		asdu[3] = request[3];
		System.arraycopy(data, 0, asdu, 4, data.length);
		return DataUnitBuilder.createAPDU(Tpdu.PROPERTY_RESPONSE, asdu);
	}

}
