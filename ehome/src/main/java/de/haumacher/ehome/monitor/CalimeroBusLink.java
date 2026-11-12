/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import tuwien.auto.calimero.CloseEvent;
import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.GroupAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.Priority;
import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.KNXNetworkLinkIP;
import tuwien.auto.calimero.link.NetworkLinkListener;
import tuwien.auto.calimero.link.medium.TPSettings;

import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.Gateways;
import de.haumacher.ehome.settings.OperatorSettings;

/**
 * {@link BusLink} through a KNXnet/IP gateway with Calimero: a tunnelling connection in link layer
 * mode, which delivers the group telegrams of the line the gateway is in (and those its coupler
 * passes) and sends group telegrams.
 */
public class CalimeroBusLink implements BusLink {

	private final InetSocketAddress _gateway;

	private final KNXNetworkLink _link;

	private volatile Listener _listener;

	private volatile boolean _closing;

	/**
	 * The individual address the gateway assigned to the tunnel, as the confirmations of the own
	 * telegrams carry it; 0 while unknown.
	 */
	private volatile int _localAddress;

	/**
	 * Opens a tunnelling connection to the given gateway.
	 *
	 * @param gateway
	 *        The gateway, see {@link Gateways#parse(String)}.
	 * @throws com.top_logic.util.error.TopLogicException
	 *         If this installation does not reach local networks
	 *         ({@link OperatorSettings#checkLocalNetwork()}, haumacher/ehome #150).
	 */
	public static CalimeroBusLink open(InetSocketAddress gateway) throws BusException {
		OperatorSettings.checkLocalNetwork();
		return new CalimeroBusLink(gateway);
	}

	/**
	 * Opens a tunnelling connection to the KNXnet/IP server of a virtual installation of this
	 * application (the simulation of the rules): a server E-Home runs itself, so it is reached
	 * whether or not this installation reaches local networks.
	 *
	 * @param server
	 *        The endpoint of the server.
	 */
	public static CalimeroBusLink openVirtual(InetSocketAddress server) throws BusException {
		return new CalimeroBusLink(server);
	}

	private CalimeroBusLink(InetSocketAddress gateway) throws BusException {
		_gateway = gateway;
		KNXNetworkLink link = null;
		try {
			InetSocketAddress remote = gateway.isUnresolved()
				? new InetSocketAddress(gateway.getHostString(), gateway.getPort()) : gateway;
			if (remote.isUnresolved()) {
				throw new IOException("unknown host " + remote.getHostString());
			}
			InetSocketAddress local = new InetSocketAddress(localAddressTowards(remote), 0);
			link = KNXNetworkLinkIP.newTunnelingLink(local, remote, false, new TPSettings());
			link.addLinkListener(new NetworkLinkListener() {
				@Override
				public void indication(FrameEvent e) {
					handle(e);
				}

				@Override
				public void confirmation(FrameEvent e) {
					if (e.getFrame() instanceof CEMILData frame && frame.getSource().getRawAddress() != 0) {
						_localAddress = frame.getSource().getRawAddress();
					}
				}

				@Override
				public void linkClosed(CloseEvent e) {
					Listener listener = _listener;
					if (listener != null && !_closing) {
						listener.closed(e.getReason());
					}
				}
			});
		} catch (KNXException | IOException ex) {
			if (link != null) {
				link.close();
			}
			throw new BusException("No connection to the gateway " + Gateways.format(gateway) + ": " + ex.getMessage(),
				ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			if (link != null) {
				link.close();
			}
			throw new BusException("Interrupted while connecting to " + Gateways.format(gateway) + ".", ex);
		}
		_link = link;
	}

	private void handle(FrameEvent e) {
		Listener listener = _listener;
		if (listener == null || !(e.getFrame() instanceof CEMILData frame)
			|| !(frame.getDestination() instanceof GroupAddress destination)) {
			return;
		}
		byte[] apdu = frame.getPayload();
		if (apdu.length < 2) {
			return;
		}
		BusTelegram.Service service = BusTelegram.Service.ofCode(DataUnitBuilder.getAPDUService(apdu));
		if (service == null) {
			return;
		}
		byte[] data = service == BusTelegram.Service.READ ? new byte[0] : DataUnitBuilder.extractASDU(apdu);
		listener.received(new BusTelegram(System.currentTimeMillis(), frame.getSource().getRawAddress(),
			destination.getRawAddress(), service, frame.getPriority().value, frame.isRepetition(),
			frame.getHopCount(), data, false));
	}

	@Override
	public void setListener(Listener listener) {
		_listener = listener;
	}

	@Override
	public int localAddress() {
		int result = _localAddress;
		return result != 0 ? result : _link.getKNXMedium().getDeviceAddress().getRawAddress();
	}

	@Override
	public String description() {
		return Gateways.format(_gateway);
	}

	@Override
	public void send(int destination, byte[] apdu) throws BusException {
		try {
			_link.sendRequestWait(new GroupAddress(destination), Priority.LOW, apdu);
		} catch (KNXException ex) {
			throw new BusException("Sending to " + new GroupAddress(destination) + " through "
				+ Gateways.format(_gateway) + " failed: " + ex.getMessage(), ex);
		}
	}

	@Override
	public void close() {
		_closing = true;
		_listener = null;
		_link.close();
	}

	private static InetAddress localAddressTowards(InetSocketAddress remote) throws IOException {
		try (DatagramSocket socket = new DatagramSocket()) {
			socket.connect(remote.getAddress(), remote.getPort());
			return socket.getLocalAddress();
		}
	}

}
