/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.haumacher.ehome.monitor.BusTelegram.Service;

/**
 * The process communication of a device on a virtual bus: its communication objects
 * ({@link GroupObject}) with the values they hold, answering the group telegrams by the flags of
 * the objects as the application layer of a KNX device does.
 *
 * <ul>
 * <li>A write to an address sets the value of every object that has the address and the write
 * flag; a response sets it for the objects with the update flag.</li>
 * <li>A read on an address is answered (a response with the value) by the first object whose
 * sending address it is, if that object has the read flag and a value.</li>
 * <li>{@link #set(int, byte[])}: the device's own application sets an object (a push button is
 * pressed, a sensor measures); an object with the transmit flag sends the value on its sending
 * address.</li>
 * <li>{@link #follow(int, int)}: an object takes every value another object of the device receives
 * from the bus (the status of a switching channel follows its switching object) and sends it like
 * its own; {@link #follow(int, int, boolean)} inverted, for an object of one bit (an inverted
 * status).</li>
 * </ul>
 *
 * <p>
 * Every object must have the communication flag to take part at all. The class knows nothing of
 * the bus: the telegrams it answers with are returned to the caller, who sends them
 * (<code>de.haumacher.ehome.virtual.server.GroupStack</code>).
 * </p>
 */
public class GroupDevice {

	/**
	 * A group telegram the device sends.
	 *
	 * @param destination
	 *        The group address.
	 * @param service
	 *        Write or response.
	 * @param data
	 *        The value: in the short form the one octet with the bits of the value.
	 * @param shortForm
	 *        Whether the value travels in the octet of the service.
	 */
	public record Outgoing(int destination, Service service, byte[] data, boolean shortForm) {

		/**
		 * The application protocol data unit of the telegram.
		 */
		public byte[] apdu() {
			if (shortForm) {
				return new byte[] { 0, (byte) (service.code() | (data[0] & 0x3F)) };
			}
			byte[] result = new byte[2 + data.length];
			result[1] = (byte) service.code();
			System.arraycopy(data, 0, result, 2, data.length);
			return result;
		}

		@Override
		public String toString() {
			return service.classifier() + " " + destination + " " + HexFormat.of().formatHex(data);
		}
	}

	/**
	 * A group telegram the device received.
	 *
	 * @param source
	 *        The individual address of the sender.
	 * @param destination
	 *        The group address.
	 * @param service
	 *        The service.
	 * @param data
	 *        The data, empty for a read.
	 */
	public record Received(int source, int destination, Service service, byte[] data) {

		@Override
		public String toString() {
			return service.classifier() + " " + destination + " " + HexFormat.of().formatHex(data);
		}
	}

	private final String _name;

	private final int _address;

	private final Map<Integer, GroupObject> _objects = new LinkedHashMap<>();

	private final Map<Integer, byte[]> _values = new LinkedHashMap<>();

	/** The objects following another one: source number to its followers. */
	private final Map<Integer, List<Follower>> _followers = new LinkedHashMap<>();

	private record Follower(int number, boolean inverted) {
		// A value.
	}

	private final List<Received> _received = new ArrayList<>();

	/**
	 * Creates a {@link GroupDevice} without objects.
	 *
	 * @param name
	 *        The name, for a person.
	 * @param address
	 *        The individual address the device sends from.
	 */
	public GroupDevice(String name, int address) {
		_name = name;
		_address = address;
	}

	/**
	 * The name.
	 */
	public String name() {
		return _name;
	}

	/**
	 * The individual address.
	 */
	public int address() {
		return _address;
	}

	/**
	 * Adds an object.
	 *
	 * @return This device, for chaining.
	 */
	public synchronized GroupDevice add(GroupObject object) {
		if (_objects.putIfAbsent(Integer.valueOf(object.number()), object) != null) {
			throw new IllegalArgumentException("Object " + object.number() + " exists already in " + _name + ".");
		}
		return this;
	}

	/**
	 * Lets the object <code>follower</code> take every value the object <code>source</code>
	 * receives from the bus.
	 *
	 * @return This device, for chaining.
	 */
	public synchronized GroupDevice follow(int follower, int source) {
		return follow(follower, source, false);
	}

	/**
	 * Lets the object <code>follower</code> take every value the object <code>source</code>
	 * receives from the bus, inverted if <code>inverted</code>.
	 *
	 * @return This device, for chaining.
	 * @throws IllegalArgumentException
	 *         If an object is missing, or an inverted follower is not an object of one bit.
	 */
	public synchronized GroupDevice follow(int follower, int source, boolean inverted) {
		GroupObject target = object(follower);
		object(source);
		if (inverted && target.bits() != 1) {
			throw new IllegalArgumentException("Only an object of one bit follows inverted: " + target + " of " + _name + ".");
		}
		_followers.computeIfAbsent(Integer.valueOf(source), x -> new ArrayList<>()).add(new Follower(follower, inverted));
		return this;
	}

	/**
	 * The numbers of the objects following the given one.
	 */
	public synchronized List<Integer> followers(int source) {
		List<Integer> result = new ArrayList<>();
		for (Follower follower : _followers.getOrDefault(Integer.valueOf(source), List.of())) {
			result.add(Integer.valueOf(follower.number()));
		}
		return result;
	}

	/**
	 * The objects, by their number.
	 */
	public synchronized Collection<GroupObject> objects() {
		return List.copyOf(_objects.values());
	}

	/**
	 * The object with the given number.
	 *
	 * @throws IllegalArgumentException
	 *         If the device has no such object.
	 */
	public synchronized GroupObject object(int number) {
		GroupObject result = _objects.get(Integer.valueOf(number));
		if (result == null) {
			throw new IllegalArgumentException("No object " + number + " in " + _name + ".");
		}
		return result;
	}

	/**
	 * The value of the object, <code>null</code> while it has none.
	 */
	public synchronized byte[] value(int number) {
		byte[] value = _values.get(Integer.valueOf(number));
		return value == null ? null : value.clone();
	}

	/**
	 * The group telegrams the device received, in the order they arrived.
	 */
	public synchronized List<Received> received() {
		return Collections.unmodifiableList(new ArrayList<>(_received));
	}

	/**
	 * The device's application sets the value of an object.
	 *
	 * @return The write telegram the object sends (with the transmit flag and a sending address),
	 *         <code>null</code> if it sends none.
	 * @throws IllegalArgumentException
	 *         If the data does not have the object's size.
	 */
	public synchronized Outgoing set(int number, byte[] data) {
		GroupObject object = object(number);
		if (!object.fits(data)) {
			throw new IllegalArgumentException("The value " + HexFormat.of().formatHex(data) + " does not fit object "
				+ object + " of " + _name + ".");
		}
		store(object, data);
		return transmission(object, data);
	}

	/**
	 * Handles a group telegram received from the bus.
	 *
	 * @return The telegrams the device answers with (responses, the values of following objects), in
	 *         the order to send them.
	 */
	public synchronized List<Outgoing> receive(int source, int destination, Service service, byte[] data) {
		_received.add(new Received(source, destination, service, data.clone()));
		List<Outgoing> result = new ArrayList<>();
		if (service == Service.READ) {
			for (GroupObject object : _objects.values()) {
				if (object.send() == destination && object.has(GroupObject.COMMUNICATION)) {
					byte[] value = _values.get(Integer.valueOf(object.number()));
					if (object.has(GroupObject.READ) && value != null) {
						result.add(new Outgoing(destination, Service.RESPONSE, value.clone(), object.shortForm()));
					}
					break;
				}
			}
		} else {
			int flag = service == Service.WRITE ? GroupObject.WRITE : GroupObject.UPDATE;
			for (GroupObject object : _objects.values()) {
				if (object.listensTo(destination) && object.has(GroupObject.COMMUNICATION | flag) && object.fits(data)) {
					store(object, data);
					for (Follower follower : _followers.getOrDefault(Integer.valueOf(object.number()), List.of())) {
						GroupObject target = _objects.get(Integer.valueOf(follower.number()));
						if (target.fits(data)) {
							byte[] value = follower.inverted() ? new byte[] { (byte) (data[0] ^ 1) } : data;
							store(target, value);
							Outgoing sent = transmission(target, value);
							if (sent != null) {
								result.add(sent);
							}
						}
					}
				}
			}
		}
		notifyAll();
		return result;
	}

	/**
	 * Waits until the object holds the given value.
	 *
	 * @return Whether it does before the timeout.
	 */
	public synchronized boolean awaitValue(int number, byte[] expected, long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!Arrays.equals(_values.get(Integer.valueOf(number)), expected)) {
			long rest = deadline - System.currentTimeMillis();
			if (rest <= 0) {
				return false;
			}
			wait(rest);
		}
		return true;
	}

	/**
	 * Waits until the device has received the given number of telegrams.
	 *
	 * @return Whether it has before the timeout.
	 */
	public synchronized boolean awaitReceived(int count, long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (_received.size() < count) {
			long rest = deadline - System.currentTimeMillis();
			if (rest <= 0) {
				return false;
			}
			wait(rest);
		}
		return true;
	}

	private void store(GroupObject object, byte[] data) {
		_values.put(Integer.valueOf(object.number()), data.clone());
		notifyAll();
	}

	private static Outgoing transmission(GroupObject object, byte[] data) {
		if (object.send() < 0 || !object.has(GroupObject.COMMUNICATION | GroupObject.TRANSMIT)) {
			return null;
		}
		return new Outgoing(object.send(), Service.WRITE, data.clone(), object.shortForm());
	}

	@Override
	public String toString() {
		return _name + " (" + (_address >>> 12) + "." + ((_address >>> 8) & 0x0F) + "." + (_address & 0xFF) + ")";
	}

}
