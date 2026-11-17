/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.connection;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.Priority;
import tuwien.auto.calimero.cemi.CEMILData;

import de.haumacher.ehome.knx.transport.Tpdu;

/**
 * A device behind the tunnel of a {@link FakeBusController}: answers in a transport connection the
 * reads a comparison makes, the device descriptor, memory (from its memory, zero where nothing was
 * set) and properties (refused, without elements), each with the acknowledgement of the data unit
 * and the answer.
 *
 * <p>
 * A test chooses which memory reads the device leaves without any answer, not even the
 * acknowledgement ({@link #setSilent(IntPredicate)}): the device that never answers, or the read during
 * which a test cancels.
 * </p>
 */
@SuppressWarnings("javadoc")
public class MemoryDevice implements FakeBusController.Bus {

	private final IndividualAddress _address;

	private final byte[] _descriptor;

	private final byte[] _memory = new byte[0x10000];

	private final AtomicInteger _memoryReads = new AtomicInteger();

	private volatile IntPredicate _silent = read -> false;

	private int _sequence;

	public MemoryDevice(IndividualAddress address, byte[] descriptor) {
		_address = address;
		_descriptor = descriptor;
	}

	/**
	 * Sets which memory reads (counted from 1) the device does not answer; a read of the descriptor
	 * or of a property counts as the read 0.
	 */
	public void setSilent(IntPredicate silent) {
		_silent = silent;
	}

	/**
	 * The memory reads the device received (each repetition counts).
	 */
	public int memoryReads() {
		return _memoryReads.get();
	}

	@Override
	public synchronized String send(FakeBusController controller, byte[] frame) throws Exception {
		CEMILData request = new CEMILData(frame, 0, frame.length);
		if (!_address.equals(request.getDestination())) {
			return "confirmed";
		}
		byte[] tpdu = request.getPayload();
		if (Tpdu.is(tpdu, Tpdu.CONNECT)) {
			_sequence = 0;
			return "confirmed";
		}
		if (!Tpdu.isConnectedData(tpdu)) {
			// Acknowledgements and the disconnect.
			return "confirmed";
		}
		int service = Tpdu.service(tpdu);
		byte[] asdu = Tpdu.asdu(tpdu);
		byte[] answer;
		int read = 0;
		if (service == Tpdu.DESCRIPTOR_READ) {
			answer = new byte[2 + _descriptor.length];
			answer[0] = (byte) (Tpdu.DESCRIPTOR_RESPONSE >>> 8);
			answer[1] = (byte) Tpdu.DESCRIPTOR_RESPONSE;
			System.arraycopy(_descriptor, 0, answer, 2, _descriptor.length);
		} else if (service == Tpdu.MEMORY_READ) {
			read = _memoryReads.incrementAndGet();
			int count = asdu[0] & 0x3F;
			int address = (asdu[1] & 0xFF) << 8 | asdu[2] & 0xFF;
			byte[] data = new byte[3 + count];
			data[0] = (byte) count;
			data[1] = asdu[1];
			data[2] = asdu[2];
			System.arraycopy(_memory, address, data, 3, count);
			answer = DataUnitBuilder.createLengthOptimizedAPDU(Tpdu.MEMORY_RESPONSE, data);
		} else if (service == Tpdu.PROPERTY_READ) {
			byte[] refused = { asdu[0], asdu[1], (byte) (asdu[2] & 0x0F), asdu[3] };
			answer = DataUnitBuilder.createAPDU(Tpdu.PROPERTY_RESPONSE, refused);
		} else {
			return "confirmed";
		}
		if (_silent.test(read)) {
			return "confirmed";
		}
		controller.frames(reply(request, Tpdu.acknowledgement(Tpdu.ACK, Tpdu.sequence(tpdu))),
			reply(request, Tpdu.connected(answer, _sequence)));
		_sequence = (_sequence + 1) & 0x0F;
		return "confirmed";
	}

	private byte[] reply(CEMILData request, byte[] tpdu) {
		return new CEMILData(CEMILData.MC_LDATA_IND, _address, request.getSource(), tpdu, Priority.SYSTEM).toByteArray();
	}

}
