/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.server;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.GroupAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.Priority;
import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.NetworkLinkListener;

import com.top_logic.basic.Logger;

import de.haumacher.ehome.monitor.BusTelegram.Service;
import de.haumacher.ehome.virtual.device.GroupDevice;
import de.haumacher.ehome.virtual.device.GroupDevice.Outgoing;

/**
 * The group communication of a {@link GroupDevice} on its link to a virtual subnet: the group
 * telegrams of the subnet go to the device, the telegrams it answers with go to the subnet.
 *
 * <p>
 * The telegrams are sent on the executor given (one thread for all devices of a subnet): never
 * from within the notification of the link, which delivers a frame to the other devices of the
 * subnet synchronously, and in the order they were made.
 * </p>
 */
public final class GroupStack implements NetworkLinkListener, AutoCloseable {

	private final GroupDevice _device;

	private final KNXNetworkLink _link;

	private final ExecutorService _sender;

	/**
	 * Creates a {@link GroupStack} serving the device on the given link.
	 *
	 * @param sender
	 *        The executor sending the device's telegrams.
	 */
	public GroupStack(GroupDevice device, KNXNetworkLink link, ExecutorService sender) {
		_device = device;
		_link = link;
		_sender = sender;
		link.addLinkListener(this);
	}

	/**
	 * The device.
	 */
	public GroupDevice device() {
		return _device;
	}

	@Override
	public void indication(FrameEvent e) {
		if (!(e.getFrame() instanceof CEMILData frame) || !(frame.getDestination() instanceof GroupAddress destination)) {
			return;
		}
		if (frame.getSource().getRawAddress() == _device.address()) {
			// The device's own frame, which the virtual subnet indicates to its sender too.
			return;
		}
		byte[] apdu = frame.getPayload();
		if (apdu.length < 2) {
			return;
		}
		Service service = Service.ofCode(DataUnitBuilder.getAPDUService(apdu));
		if (service == null) {
			return;
		}
		byte[] data = service == Service.READ ? new byte[0] : DataUnitBuilder.extractASDU(apdu);
		List<Outgoing> answers = _device.receive(frame.getSource().getRawAddress(), destination.getRawAddress(), service, data);
		for (Outgoing answer : answers) {
			send(answer);
		}
	}

	@Override
	public void confirmation(FrameEvent e) {
		// Nothing to do.
	}

	@Override
	public void linkClosed(tuwien.auto.calimero.CloseEvent e) {
		// Closed by the installation.
	}

	/**
	 * Sends a telegram of the device (on the sender thread).
	 *
	 * @return The completion of the send; <code>null</code> when the stack is closed.
	 */
	public Future<?> send(Outgoing telegram) {
		try {
			return _sender.submit(() -> {
				try {
					_link.sendRequestWait(new GroupAddress(telegram.destination()), Priority.LOW, telegram.apdu());
				} catch (KNXException ex) {
					Logger.error("The virtual device " + _device + " failed to send " + telegram + ".", ex, GroupStack.class);
				}
			});
		} catch (RejectedExecutionException ex) {
			return null;
		}
	}

	@Override
	public void close() {
		_link.removeLinkListener(this);
		_link.close();
	}

}
