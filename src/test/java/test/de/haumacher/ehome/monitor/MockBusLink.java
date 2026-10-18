/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.monitor;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import de.haumacher.ehome.monitor.BusLink;
import de.haumacher.ehome.monitor.BusTelegram;

/**
 * {@link BusLink} of the tests: telegrams are injected as if received, and what is sent is kept.
 */
public class MockBusLink implements BusLink {

	/** The individual address of the link, 1.1.250. */
	public static final int LOCAL_ADDRESS = 0x11FA;

	private Listener _listener;

	private final List<String> _sent = new ArrayList<>();

	private boolean _closed;

	@Override
	public void setListener(Listener listener) {
		_listener = listener;
	}

	@Override
	public int localAddress() {
		return LOCAL_ADDRESS;
	}

	@Override
	public String description() {
		return "mock:3671";
	}

	@Override
	public void send(int destination, byte[] apdu) {
		_sent.add(destination + ": " + HexFormat.ofDelimiter(" ").withUpperCase().formatHex(apdu));
	}

	@Override
	public void close() {
		_closed = true;
	}

	/**
	 * Delivers a group write as if received from the bus.
	 *
	 * @param source
	 *        The individual address of the sender.
	 * @param destination
	 *        The group address.
	 * @param data
	 *        The data (the ASDU).
	 */
	public void write(int source, int destination, int... data) {
		byte[] bytes = new byte[data.length];
		for (int n = 0; n < data.length; n++) {
			bytes[n] = (byte) data[n];
		}
		_listener.received(new BusTelegram(System.currentTimeMillis(), source, destination,
			BusTelegram.Service.WRITE, BusTelegram.PRIORITY_LOW, false, BusTelegram.DEFAULT_HOP_COUNT, bytes, false));
	}

	/**
	 * Delivers a group read as if received from the bus.
	 *
	 * @param source
	 *        The individual address of the sender.
	 * @param destination
	 *        The group address.
	 */
	public void read(int source, int destination) {
		_listener.received(new BusTelegram(System.currentTimeMillis(), source, destination,
			BusTelegram.Service.READ, BusTelegram.PRIORITY_LOW, false, BusTelegram.DEFAULT_HOP_COUNT, new byte[0], false));
	}

	/**
	 * Delivers a group write from a thread of its own, without the context of the test, as the
	 * receiver thread of a real link does.
	 *
	 * @see #write(int, int, int...)
	 */
	public void writeFromLinkThread(int source, int destination, int... data) throws InterruptedException {
		Thread thread = new Thread(() -> write(source, destination, data), "mock link");
		thread.start();
		thread.join();
	}

	/**
	 * What was sent, one entry per telegram: the group address as number and the APDU as
	 * hexadecimal bytes, e.g. <code>34: 00 81</code>.
	 */
	public List<String> getSent() {
		return _sent;
	}

	/**
	 * Whether the link was closed.
	 */
	public boolean isClosed() {
		return _closed;
	}

}
