/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.simulation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.haumacher.ehome.monitor.BusTelegram.Service;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.rules.DatapointModel;
import de.haumacher.ehome.rules.DatapointModel.Datapoint;
import de.haumacher.ehome.rules.DatapointModel.Quality;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.simulation.KnxValues.CodingException;

/**
 * The KNX technology of a {@link DatapointModel}: the datapoints bound to group addresses (the
 * <code>knx</code> member of a datapoint of the runtime image), with the semantics of the
 * controller's KNX adapter (homecontrol <code>core/src/knx/adapter.cpp</code>).
 *
 * <ul>
 * <li>A write or response on the sending address or a listening address of a datapoint is a report
 * of the decoded value (a value the datapoint type cannot decode, or that does not fit the
 * datapoint's type, is logged and changes nothing).</li>
 * <li>A command on a datapoint that transmits is a write of the encoded value to its sending
 * address; one without a sending address, or a value the type cannot encode, is refused. A
 * datapoint that does not transmit takes the command without a telegram. The telegram sent also
 * reaches the other datapoints of the address, as it reaches the other devices on the bus: after
 * the cascade that sent it ({@link #deliverSent()}).</li>
 * <li>A read on the sending address of a readable datapoint with a good value is answered.</li>
 * <li>{@link #startReads()}: the addresses to read for the datapoints read on start; the loss of
 * the link makes every datapoint stale ({@link #linkLost()}).</li>
 * </ul>
 *
 * <p>
 * The binding is not thread-safe: it is called where the model is changed, the telegrams the
 * driver received included ({@link #received(int, int, Service, byte[])}). The telegrams it sends
 * go to its {@link Bus}.
 * </p>
 */
public final class KnxBinding implements DatapointModel.Technology {

	/**
	 * Where the binding sends its telegrams.
	 */
	public interface Bus {

		/**
		 * Sends a group telegram.
		 *
		 * @param apdu
		 *        The application protocol data unit: service and data.
		 */
		void send(int destination, byte[] apdu);
	}

	/**
	 * Receives what the binding has to say about the telegrams.
	 */
	public interface Log {

		/**
		 * A telegram changed nothing, or a command was refused, for the reason given.
		 */
		void problem(String text);
	}

	/**
	 * The binding of one datapoint.
	 *
	 * @param datapoint
	 *        The id of the datapoint.
	 * @param dpt
	 *        The datapoint type of its addresses.
	 * @param send
	 *        The sending address, -1 for none.
	 * @param listen
	 *        The further addresses it listens to.
	 * @param transmit
	 *        Whether a command is sent to the bus.
	 * @param readable
	 *        Whether the binding answers a read on the sending address.
	 * @param readOnStart
	 *        Whether the value is read after a start.
	 */
	public record Mapping(String datapoint, Dpt dpt, int send, List<Integer> listen, boolean transmit, boolean readable,
			boolean readOnStart) {

		/**
		 * The addresses whose telegrams update the datapoint: the sending one first.
		 */
		public List<Integer> addresses() {
			List<Integer> result = new ArrayList<>();
			if (send >= 0) {
				result.add(Integer.valueOf(send));
			}
			for (Integer address : listen) {
				if (!result.contains(address)) {
					result.add(address);
				}
			}
			return result;
		}
	}

	private record Sent(int address, Service service, byte[] data, String sender) {
		// A telegram sent, to be delivered to the other datapoints of its address.
	}

	private final DatapointModel _model;

	private final Bus _bus;

	private final Log _log;

	private final Map<String, Mapping> _mappings = new LinkedHashMap<>();

	private final Deque<Sent> _sent = new ArrayDeque<>();

	/**
	 * Creates a {@link KnxBinding} without datapoints.
	 */
	public KnxBinding(DatapointModel model, Bus bus, Log log) {
		_model = model;
		_bus = bus;
		_log = log;
	}

	/**
	 * Binds a datapoint of the model (added with this binding as its technology).
	 */
	public void bind(Mapping mapping) {
		Datapoint datapoint = _model.get(mapping.datapoint());
		if (datapoint == null || datapoint.technology() != this) {
			throw new IllegalArgumentException("The datapoint '" + mapping.datapoint() + "' is not one of the binding.");
		}
		_mappings.put(mapping.datapoint(), mapping);
	}

	/**
	 * The binding of the datapoint, <code>null</code> if it has none.
	 */
	public Mapping mapping(String datapoint) {
		return _mappings.get(datapoint);
	}

	/**
	 * The bindings in the order they were made.
	 */
	public List<Mapping> mappings() {
		return List.copyOf(_mappings.values());
	}

	/**
	 * Reads the <code>knx</code> member of a datapoint of a runtime image.
	 *
	 * @param id
	 *        The id of the datapoint.
	 * @param dp
	 *        The datapoint of the image.
	 */
	public static Mapping mapping(String id, Map<?, ?> dp) {
		Map<?, ?> knx = (Map<?, ?>) dp.get("knx");
		int send = knx.get("send") instanceof String text ? groupAddress(text) : -1;
		List<Integer> listen = new ArrayList<>();
		if (knx.get("listen") instanceof List<?> list) {
			for (Object address : list) {
				listen.add(Integer.valueOf(groupAddress((String) address)));
			}
		}
		return new Mapping(id, Dpt.parse((String) knx.get("dpt")), send, listen, flag(dp, "transmit"),
			flag(dp, "readable"), flag(dp, "readOnStart"));
	}

	private static boolean flag(Map<?, ?> dp, String name) {
		return Boolean.TRUE.equals(dp.get(name));
	}

	/**
	 * A group address in three levels (<code>1/2/3</code>) as a number.
	 *
	 * @throws IllegalArgumentException
	 *         For a text that is no such address.
	 */
	public static int groupAddress(String text) {
		String[] parts = text.split("/");
		if (parts.length != 3) {
			throw new IllegalArgumentException("Not a group address in three levels: " + text);
		}
		int main = Integer.parseInt(parts[0]);
		int middle = Integer.parseInt(parts[1]);
		int sub = Integer.parseInt(parts[2]);
		if (main < 0 || main > 31 || middle < 0 || middle > 7 || sub < 0 || sub > 255) {
			throw new IllegalArgumentException("Not a group address in three levels: " + text);
		}
		return main << 11 | middle << 8 | sub;
	}

	/**
	 * The application protocol data unit of a group telegram with the data.
	 */
	public static byte[] apdu(Service service, Dpt dpt, byte[] data) {
		if (service == Service.READ) {
			return new byte[] { 0, (byte) Service.READ.code() };
		}
		if (dpt.main() <= 3) {
			return new byte[] { 0, (byte) (service.code() | (data[0] & 0x3F)) };
		}
		byte[] result = new byte[2 + data.length];
		result[1] = (byte) service.code();
		System.arraycopy(data, 0, result, 2, data.length);
		return result;
	}

	@Override
	public boolean command(Datapoint datapoint, Val value) {
		Mapping mapping = _mappings.get(datapoint.name());
		if (mapping == null) {
			_log.problem("refusing the command for '" + datapoint.name() + "': not bound to a group address");
			return false;
		}
		if (!mapping.transmit()) {
			return true;
		}
		if (mapping.send() < 0) {
			_log.problem("refusing the command for '" + datapoint.name() + "': not mapped to a sending group address");
			return false;
		}
		byte[] data;
		try {
			data = KnxValues.encode(mapping.dpt(), value);
		} catch (CodingException ex) {
			_log.problem("refusing the command " + Values.format(value) + " for '" + datapoint.name() + "': "
				+ ex.getMessage());
			return false;
		}
		_bus.send(mapping.send(), apdu(Service.WRITE, mapping.dpt(), data));
		_sent.add(new Sent(mapping.send(), Service.WRITE, data, datapoint.name()));
		return true;
	}

	/**
	 * Whether telegrams sent wait for {@link #deliverSent()}.
	 */
	public boolean hasSent() {
		return !_sent.isEmpty();
	}

	/**
	 * Delivers the telegrams sent to the other datapoints of their addresses.
	 */
	public void deliverSent() {
		while (!_sent.isEmpty()) {
			Sent sent = _sent.poll();
			deliver(sent.address(), sent.data(), sent.sender());
		}
	}

	/**
	 * A group telegram was received from the bus.
	 *
	 * @param data
	 *        The data: for a value of up to 6 bits the octet holding them, empty for a read.
	 */
	public void received(int source, int destination, Service service, byte[] data) {
		if (service == Service.READ) {
			answerRead(destination);
			return;
		}
		deliver(destination, data, null);
	}

	private void deliver(int address, byte[] data, String except) {
		for (Mapping mapping : _mappings.values()) {
			if (mapping.datapoint().equals(except) || !mapping.addresses().contains(Integer.valueOf(address))) {
				continue;
			}
			Datapoint datapoint = _model.get(mapping.datapoint());
			Val value;
			try {
				value = KnxValues.decode(mapping.dpt(), data);
			} catch (CodingException ex) {
				_log.problem(RuntimeImage.threeLevel(address) + ": " + ex.getMessage() + "; '" + mapping.datapoint()
					+ "' unchanged");
				continue;
			}
			Val converted = value.type() == datapoint.type().expressionType() ? value
				: Values.convertForDatapoint(value, datapoint.type());
			if (converted == null) {
				_log.problem(RuntimeImage.threeLevel(address) + ": " + Values.format(value) + " is no "
					+ datapoint.type().externalName() + " value; '" + mapping.datapoint() + "' unchanged");
				continue;
			}
			_model.report(mapping.datapoint(), converted);
		}
	}

	private void answerRead(int address) {
		for (Mapping mapping : _mappings.values()) {
			if (mapping.send() != address) {
				continue;
			}
			Datapoint datapoint = _model.get(mapping.datapoint());
			if (!mapping.readable() || datapoint.quality() != Quality.GOOD) {
				continue;
			}
			try {
				byte[] data = KnxValues.encode(mapping.dpt(), datapoint.value());
				_bus.send(address, apdu(Service.RESPONSE, mapping.dpt(), data));
			} catch (CodingException ex) {
				_log.problem("cannot answer the read of " + RuntimeImage.threeLevel(address) + " with '"
					+ mapping.datapoint() + "': " + ex.getMessage());
			}
			return;
		}
	}

	/**
	 * Sends the reads for the datapoints read on start: one per datapoint, to its sending address,
	 * else to its first listening address.
	 *
	 * @return The addresses read, in the order of the datapoints.
	 */
	public List<Integer> startReads() {
		List<Integer> result = new ArrayList<>();
		for (Mapping mapping : _mappings.values()) {
			if (mapping.readOnStart() && !mapping.addresses().isEmpty()) {
				int address = mapping.addresses().get(0).intValue();
				_bus.send(address, apdu(Service.READ, mapping.dpt(), null));
				result.add(Integer.valueOf(address));
			}
		}
		return result;
	}

	/**
	 * The link to the bus was lost: every datapoint of the binding is stale.
	 */
	public void linkLost() {
		for (Mapping mapping : _mappings.values()) {
			_model.reportStale(mapping.datapoint());
		}
	}

}
