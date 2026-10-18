/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.tooling.trace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.haumacher.ehome.programming.download.LoadControls;
import de.haumacher.ehome.programming.download.Operation;

/**
 * Reads a trace as the {@link Operation}s of a download, and compares them with a plan.
 *
 * <p>
 * The operations are reconstructed from the services the device received, with the device's answers
 * as the values a check expects:
 * </p>
 * <ul>
 * <li><code>T_Connect</code>: {@link Operation.Connect}; <code>T_Disconnect</code> (from the
 * tool): {@link Operation.Disconnect}.</li>
 * <li><code>A_DeviceDescriptor_Read</code>: {@link Operation.CheckMask} with the mask the device
 * answered.</li>
 * <li><code>A_PropertyValue_Write</code> of 10 bytes into property 5: {@link Operation.LoadControl}
 * (the load state machine is the object, the expected state the one answered); the reads of
 * property 5 of the same object right after it (polls of the state) belong to it, the last state
 * read is the one expected. Any other property write: {@link Operation.WriteProperty}.</li>
 * <li><code>A_PropertyValue_Read</code>: {@link Operation.CompareProperty} with the value
 * answered.</li>
 * <li><code>A_Memory_Write</code>: {@link Operation.WriteMemory}, verified if a read of the same
 * range follows; any other <code>A_Memory_Read</code>: {@link Operation.CompareMemory} with the
 * bytes answered.</li>
 * <li><code>A_Restart</code>: {@link Operation.Restart}, together with the tool's
 * <code>T_Disconnect</code> right after it (the restart ends the connection);
 * <code>A_IndividualAddress_Write</code>:
 * {@link Operation.WriteIndividualAddress}.</li>
 * <li>Any other service the device received: {@link Operation.Skip} naming it, so that nothing is
 * left out.</li>
 * </ul>
 * <p>
 * Acknowledgements (<code>T_Ack</code>, <code>T_Nak</code>), repetitions (a service in a connection
 * with the sequence number of the one before it) and the events of the device itself are part of the
 * transport, not operations. The step labels and the names of the memory blocks are not on the wire: the reconstructed
 * operations have empty ones, and {@link #signature(Operation)} leaves them out.
 * </p>
 */
public class TraceOperations {

	/**
	 * The operations of a trace.
	 */
	public static List<Operation> operations(List<TraceEntry> entries) {
		List<Operation> result = new ArrayList<>();
		List<TraceEntry> requests = new ArrayList<>();
		Map<Integer, Integer> lastSequence = new HashMap<>();
		for (TraceEntry entry : entries) {
			if (!entry.incoming() || acknowledgement(entry)) {
				continue;
			}
			if (TraceEntry.T_CONNECT.equals(entry.service()) || TraceEntry.T_DISCONNECT.equals(entry.service())) {
				lastSequence.remove(entry.source());
			} else if (entry.sequence() != null) {
				// A repetition (the tool got no acknowledgement in time) carries the sequence number of
				// the service before it: one service on the wire twice.
				if (entry.sequence().equals(lastSequence.put(entry.source(), entry.sequence()))) {
					continue;
				}
			}
			requests.add(entry);
		}
		int n = 0;
		while (n < requests.size()) {
			TraceEntry request = requests.get(n);
			TraceEntry answer = answer(entries, request);
			switch (request.service()) {
				case TraceEntry.T_CONNECT:
					result.add(new Operation.Connect(""));
					break;
				case TraceEntry.T_DISCONNECT:
					result.add(new Operation.Disconnect(""));
					break;
				case TraceEntry.DESCRIPTOR_READ: {
					byte[] mask = answer == null ? new byte[0] : answer.data();
					int value = mask.length == 2 ? (mask[0] & 0xFF) << 8 | mask[1] & 0xFF : -1;
					result.add(new Operation.CheckMask("", new int[] { value }));
					break;
				}
				case TraceEntry.PROPERTY_WRITE: {
					int object = request.number(TraceEntry.OBJECT);
					int property = request.number(TraceEntry.PROPERTY);
					byte[] data = request.data();
					if (property == LoadControls.PID_LOAD_STATE_CONTROL && data.length == LoadControls.LENGTH) {
						int state = state(answer);
						while (n + 1 < requests.size() && isStateRead(requests.get(n + 1), object)) {
							n++;
							state = state(answer(entries, requests.get(n)));
						}
						result.add(new Operation.LoadControl("", object, object, data, state));
					} else {
						result.add(new Operation.WriteProperty("", "", object, property, request.number(TraceEntry.START),
							request.number(TraceEntry.COUNT), data));
					}
					break;
				}
				case TraceEntry.PROPERTY_READ:
					result.add(new Operation.CompareProperty("", request.number(TraceEntry.OBJECT),
						request.number(TraceEntry.PROPERTY), request.number(TraceEntry.START), request.number(TraceEntry.COUNT),
						answer == null ? new byte[0] : answer.data()));
					break;
				case TraceEntry.MEMORY_WRITE: {
					int address = request.number(TraceEntry.ADDRESS);
					byte[] data = request.data();
					boolean verify = n + 1 < requests.size() && isMemoryRead(requests.get(n + 1), address, data.length);
					if (verify) {
						n++;
					}
					result.add(new Operation.WriteMemory("", "", address, data, verify));
					break;
				}
				case TraceEntry.MEMORY_READ:
					result.add(new Operation.CompareMemory("", request.number(TraceEntry.ADDRESS),
						answer == null ? new byte[0] : answer.data()));
					break;
				case TraceEntry.RESTART:
					result.add(new Operation.Restart(""));
					// The restart ends the connection; the tool's disconnect right after it belongs to it.
					if (n + 1 < requests.size() && TraceEntry.T_DISCONNECT.equals(requests.get(n + 1).service())) {
						n++;
					}
					break;
				case TraceEntry.INDIVIDUAL_ADDRESS_WRITE:
					result.add(new Operation.WriteIndividualAddress("",
						TraceFormat.parseIndividual(request.field(TraceEntry.INDIVIDUAL))));
					break;
				default:
					result.add(new Operation.Skip("", "the service " + request.service() + " " + request.fields()));
			}
			n++;
		}
		return result;
	}

	/**
	 * The operations of a plan that are visible on the wire: without delays, steps not executed and
	 * disconnects while no connection is open (after a restart, which ends the connection, see
	 * {@link de.haumacher.ehome.programming.download.DeviceAccess#restart()}).
	 */
	public static List<Operation> visible(List<Operation> plan) {
		List<Operation> result = new ArrayList<>();
		boolean connected = false;
		for (Operation operation : plan) {
			if (operation instanceof Operation.Delay || operation instanceof Operation.Skip) {
				continue;
			}
			if (operation instanceof Operation.Disconnect) {
				if (!connected) {
					continue;
				}
				connected = false;
			} else if (operation instanceof Operation.Restart) {
				connected = false;
			} else if (!(operation instanceof Operation.WriteIndividualAddress)) {
				connected = true;
			}
			result.add(operation);
		}
		return result;
	}

	/**
	 * Compares the operations of a plan with those recorded.
	 *
	 * @return The differences for a person, empty if the trace performs the plan operation by
	 *         operation.
	 */
	public static List<String> compare(List<Operation> plan, List<Operation> recorded) {
		List<Operation> expected = visible(plan);
		List<String> result = new ArrayList<>();
		int count = Math.max(expected.size(), recorded.size());
		for (int n = 0; n < count; n++) {
			Operation planned = n < expected.size() ? expected.get(n) : null;
			Operation actual = n < recorded.size() ? recorded.get(n) : null;
			if (planned == null || actual == null || !matches(planned, actual)) {
				result.add((n + 1) + ". planned: " + (planned == null ? "-" : signature(planned)) + ", recorded: "
					+ (actual == null ? "-" : signature(actual)));
			}
		}
		return result;
	}

	/**
	 * Whether a recorded operation performs the planned one: the same signature; a mask check
	 * answered with one of the planned masks; a property comparison whose answer starts with the
	 * expected value (the rest of the longer one zero, as the executor compares).
	 */
	public static boolean matches(Operation planned, Operation recorded) {
		if (planned instanceof Operation.CheckMask check && recorded instanceof Operation.CheckMask read) {
			return Arrays.stream(check.masks()).anyMatch(m -> m == read.masks()[0]);
		}
		if (planned instanceof Operation.CompareProperty compare && recorded instanceof Operation.CompareProperty read) {
			return compare.objectIndex() == read.objectIndex() && compare.propertyId() == read.propertyId()
				&& compare.start() == read.start() && compare.count() == read.count()
				&& startsWith(read.expected(), compare.expected());
		}
		return signature(planned).equals(signature(recorded));
	}

	/**
	 * An operation as it shows on the wire, without the step label and the name of the memory block.
	 */
	public static String signature(Operation operation) {
		if (operation instanceof Operation.Connect) {
			return "Connect";
		}
		if (operation instanceof Operation.Disconnect) {
			return "Disconnect";
		}
		if (operation instanceof Operation.CheckMask check) {
			StringBuilder result = new StringBuilder("CheckMask");
			for (int mask : check.masks()) {
				result.append(String.format(" %04X", mask & 0xFFFF));
			}
			return result.toString();
		}
		if (operation instanceof Operation.CompareProperty compare) {
			return "CompareProperty " + compare.objectIndex() + "/" + compare.propertyId() + " " + compare.start() + "+"
				+ compare.count() + " " + TraceFormat.hex(compare.expected());
		}
		if (operation instanceof Operation.LoadControl control) {
			return "LoadControl " + control.objectIndex() + " " + TraceFormat.hex(control.control()) + " -> "
				+ control.expectedState();
		}
		if (operation instanceof Operation.WriteMemory write) {
			return String.format("WriteMemory %04X %s%s", write.address(), TraceFormat.hex(write.data()),
				write.verify() ? " verified" : "");
		}
		if (operation instanceof Operation.CompareMemory compare) {
			return String.format("CompareMemory %04X %s", compare.address(), TraceFormat.hex(compare.expected()));
		}
		if (operation instanceof Operation.WriteProperty write) {
			return "WriteProperty " + write.objectIndex() + "/" + write.propertyId() + " " + write.start() + "+"
				+ write.count() + " " + TraceFormat.hex(write.data());
		}
		if (operation instanceof Operation.Restart) {
			return "Restart";
		}
		if (operation instanceof Operation.Delay delay) {
			return "Delay " + delay.milliseconds();
		}
		if (operation instanceof Operation.Skip skip) {
			return "Skip " + skip.reason();
		}
		if (operation instanceof Operation.WriteIndividualAddress write) {
			return "WriteIndividualAddress " + TraceFormat.individual(write.address());
		}
		return operation.describe();
	}

	/**
	 * The answer of the device to a request: the next response the device sent to the requester
	 * after it, before the requester's next request.
	 */
	private static TraceEntry answer(List<TraceEntry> entries, TraceEntry request) {
		String response = responseOf(request.service());
		if (response == null) {
			return null;
		}
		int index = entries.indexOf(request);
		for (int n = index + 1; n < entries.size(); n++) {
			TraceEntry entry = entries.get(n);
			if (entry.incoming() && entry.source() == request.source() && !entry.service().startsWith("T_")) {
				return null;
			}
			if (entry.outgoing() && entry.destination() == request.source() && response.equals(entry.service())) {
				return entry;
			}
		}
		return null;
	}

	/**
	 * Whether the entry is an acknowledgement of the transport layer, part of the connection, not an
	 * operation.
	 */
	private static boolean acknowledgement(TraceEntry entry) {
		return TraceEntry.T_ACK.equals(entry.service()) || TraceEntry.T_NAK.equals(entry.service());
	}

	private static String responseOf(String service) {
		switch (service) {
			case TraceEntry.DESCRIPTOR_READ:
				return TraceEntry.DESCRIPTOR_RESPONSE;
			case TraceEntry.PROPERTY_READ:
			case TraceEntry.PROPERTY_WRITE:
				return TraceEntry.PROPERTY_RESPONSE;
			case TraceEntry.MEMORY_READ:
				return TraceEntry.MEMORY_RESPONSE;
			default:
				return null;
		}
	}

	private static int state(TraceEntry answer) {
		if (answer == null) {
			return -1;
		}
		byte[] data = answer.data();
		return data.length == 1 ? data[0] & 0xFF : -1;
	}

	private static boolean isStateRead(TraceEntry request, int object) {
		return TraceEntry.PROPERTY_READ.equals(request.service()) && request.number(TraceEntry.OBJECT) == object
			&& request.number(TraceEntry.PROPERTY) == LoadControls.PID_LOAD_STATE_CONTROL;
	}

	private static boolean isMemoryRead(TraceEntry request, int address, int length) {
		return TraceEntry.MEMORY_READ.equals(request.service()) && request.number(TraceEntry.ADDRESS) == address
			&& request.number(TraceEntry.COUNT) == length;
	}

	private static boolean startsWith(byte[] value, byte[] expected) {
		for (int n = 0; n < Math.max(value.length, expected.length); n++) {
			int actual = n < value.length ? value[n] : 0;
			int wanted = n < expected.length ? expected[n] : 0;
			if (actual != wanted) {
				return false;
			}
		}
		return true;
	}

}
