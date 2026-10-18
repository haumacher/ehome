/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.knx.transport;

import java.util.function.Consumer;

import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXAddress;
import tuwien.auto.calimero.Priority;
import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.link.AbstractLink;
import tuwien.auto.calimero.link.medium.TPSettings;

/**
 * A KNX network link in memory for the tests of the transport layer: what the client sends goes to
 * a handler (the scripted device), what the device answers is delivered to the client's listeners
 * on the calling thread, at the moment the test chooses.
 */
@SuppressWarnings("javadoc")
public class TestLink extends AbstractLink<AutoCloseable> {

	private final int _address;

	private volatile Consumer<byte[]> _device = tpdu -> {
		// Nothing listens.
	};

	public TestLink(int address) {
		super("test link " + new IndividualAddress(address), new TPSettings(new IndividualAddress(address)));
		_address = address;
	}

	/**
	 * Sets the handler of what the client sends (called on the client's sending thread).
	 */
	public void device(Consumer<byte[]> device) {
		_device = device;
	}

	/**
	 * Delivers a unit of the device to the client.
	 */
	public void deliver(int source, byte[] tpdu) {
		CEMILData frame = new CEMILData(CEMILData.MC_LDATA_IND, new IndividualAddress(source),
			new IndividualAddress(_address), tpdu, Priority.LOW);
		FrameEvent event = new FrameEvent(this, frame);
		notifier.getListeners().fire(l -> l.indication(event));
	}

	@Override
	protected void onSend(KNXAddress dst, byte[] msg, boolean waitForCon) {
		// Only cEMI frames.
	}

	@Override
	protected void onSend(CEMILData msg, boolean waitForCon) {
		_device.accept(msg.getPayload());
	}

}
