/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.DeviceReader;
import de.haumacher.ehome.programming.download.Gateways;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.KNXNetworkLinkIP;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.mgmt.Destination;
import tuwien.auto.calimero.mgmt.LocalDeviceManagementIp;
import tuwien.auto.calimero.mgmt.ManagementClientImpl;
import tuwien.auto.calimero.mgmt.ManagementProcedures;
import tuwien.auto.calimero.mgmt.ManagementProceduresImpl;

/**
 * {@link ScanBus} reaching the bus through a KNXnet/IP gateway with Calimero: one tunnelling link,
 * on which the scans of addresses run with Calimero's {@link ManagementProcedures} and the reads of
 * a device with a management client, one after the other.
 *
 * <p>
 * Reads only: connections, device descriptor, property and memory reads, and the broadcast read of
 * the individual address of devices in programming mode. Writing is the business of
 * <code>CalimeroWriter</code>, the one class sending anything that changes a device.
 * </p>
 */
public class CalimeroScanBus implements ScanBus {

	/**
	 * The time Calimero's scan of 256 addresses takes: a connect every 115 ms, then 7.1 s for the
	 * last answers.
	 */
	private static final long SCAN_MILLIS = 256 * 115 + 7100;

	private final InetSocketAddress _gateway;

	private final InetSocketAddress _local;

	private final KNXNetworkLink _link;

	/**
	 * Opens a tunnelling connection to the gateway.
	 *
	 * @param gateway
	 *        The gateway, see {@link Gateways#parse(String)}.
	 */
	public static CalimeroScanBus open(InetSocketAddress gateway) throws BusException {
		return new CalimeroScanBus(gateway);
	}

	private CalimeroScanBus(InetSocketAddress gateway) throws BusException {
		_gateway = gateway;
		InetSocketAddress remote = resolve(gateway);
		try {
			_local = new InetSocketAddress(localAddressTowards(remote), 0);
			_link = KNXNetworkLinkIP.newTunnelingLink(_local, remote, false, new TPSettings());
		} catch (KNXException | IOException ex) {
			throw new BusException("No connection to the gateway " + Gateways.format(gateway) + ": " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new BusException("Interrupted while connecting to " + Gateways.format(gateway) + ".", ex);
		}
	}

	@Override
	public String gateway() {
		return Gateways.format(_gateway);
	}

	@Override
	public List<Integer> scanLine(int area, int line, Progress progress) throws BusException {
		return scan(procedures -> {
			List<Integer> found = Collections.synchronizedList(new ArrayList<>());
			procedures.scanNetworkDevices(area, line, address -> {
				found.add(address.getRawAddress());
				progress.found(address.getRawAddress());
			}, (address, descriptor) -> {
				// The descriptor is read again with the device.
			});
			return found;
		}, progress, "Scanning line " + area + "." + line);
	}

	@Override
	public List<Integer> scanRouters(Progress progress) throws BusException {
		return scan(procedures -> {
			List<Integer> found = new ArrayList<>();
			for (IndividualAddress address : procedures.scanNetworkRouters()) {
				found.add(address.getRawAddress());
				progress.found(address.getRawAddress());
			}
			return found;
		}, progress, "Scanning for routers");
	}

	@Override
	public List<Integer> programmingMode() throws BusException {
		try (ManagementProceduresImpl procedures = new ManagementProceduresImpl(_link)) {
			List<Integer> result = new ArrayList<>();
			for (IndividualAddress address : procedures.readAddress()) {
				result.add(address.getRawAddress());
			}
			Collections.sort(result);
			return result;
		} catch (KNXException ex) {
			throw new BusException("Reading the address of devices in programming mode through " + gateway() + " failed: "
				+ ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new BusException("Interrupted.", ex);
		}
	}

	private interface Scan {
		List<Integer> run(ManagementProcedures procedures) throws Exception;
	}

	/**
	 * Runs a scan of Calimero in a thread of its own and reports its progress by the time it takes
	 * (Calimero tells no progress); a cancelled job interrupts it.
	 */
	private List<Integer> scan(Scan scan, Progress progress, String what) throws BusException {
		ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "ehome-scan"));
		try {
			ManagementProceduresImpl procedures = new ManagementProceduresImpl(_link);
			try {
				Future<List<Integer>> result = executor.submit(() -> scan.run(procedures));
				long start = System.currentTimeMillis();
				while (true) {
					try {
						List<Integer> found = new ArrayList<>(result.get(300, TimeUnit.MILLISECONDS));
						Collections.sort(found);
						progress.progress(256, 256);
						return found;
					} catch (TimeoutException ex) {
						long elapsed = System.currentTimeMillis() - start;
						try {
							progress.progress((int) Math.min(255, elapsed * 256 / SCAN_MILLIS), 256);
						} catch (RuntimeException cancelled) {
							result.cancel(true);
							throw cancelled;
						}
					}
				}
			} finally {
				procedures.detach();
			}
		} catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			throw new BusException(what + " through " + gateway() + " failed: " + cause.getMessage(), cause);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new BusException("Interrupted.", ex);
		} catch (KNXException ex) {
			throw new BusException(what + " through " + gateway() + " failed: " + ex.getMessage(), ex);
		} finally {
			executor.shutdownNow();
		}
	}

	@Override
	public DeviceReader device(int address) throws BusException {
		try {
			return new LinkReader(new ManagementClientImpl(_link), address);
		} catch (KNXException ex) {
			throw new BusException("No management connection through " + gateway() + ": " + ex.getMessage(), ex);
		}
	}

	@Override
	public LocalProperties gatewayProperties() throws BusException {
		LocalDeviceManagementIp adapter;
		try {
			adapter = LocalDeviceManagementIp.newAdapter(_local, resolve(_gateway), false, false, event -> {
				// Closed with the bus.
			});
		} catch (KNXException ex) {
			throw new BusException("No local device management of " + gateway() + ": " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new BusException("Interrupted.", ex);
		}
		return new LocalProperties() {
			@Override
			public byte[] readProperty(int objectType, int propertyId, int start, int count) throws BusException {
				int index = adapter.interfaceObjects().indexOf(objectType);
				if (index < 0) {
					throw new BusException("The gateway has no interface object of type " + objectType + ".");
				}
				try {
					return adapter.getProperty(index, propertyId, start, count);
				} catch (KNXException ex) {
					throw new BusException("Reading object type " + objectType + " property " + propertyId + " of "
						+ gateway() + " failed: " + ex.getMessage(), ex);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new BusException("Interrupted.", ex);
				}
			}

			@Override
			public void close() {
				adapter.close();
			}
		};
	}

	@Override
	public void close() {
		_link.close();
	}

	/**
	 * Reads one device through the link of the bus; releasing it leaves the link open.
	 */
	private final class LinkReader implements DeviceReader {

		private final ManagementClientImpl _management;

		private final IndividualAddress _device;

		private Destination _destination;

		LinkReader(ManagementClientImpl management, int address) {
			_management = management;
			_management.responseTimeout(CalimeroReader.TIMEOUT);
			_device = new IndividualAddress(address);
		}

		private Destination destination() {
			if (_destination == null || _destination.getState() == Destination.State.Destroyed) {
				_destination = _management.createDestination(_device, true);
			}
			return _destination;
		}

		@Override
		public byte[] readDescriptor() throws BusException {
			try {
				return _management.readDeviceDesc(destination(), 0);
			} catch (KNXException ex) {
				throw failure("Reading the device descriptor", ex);
			} catch (InterruptedException ex) {
				throw interrupted(ex);
			}
		}

		@Override
		public byte[] readProperty(int objectIndex, int propertyId, int start, int count) throws BusException {
			try {
				return _management.readProperty(destination(), objectIndex, propertyId, start, count);
			} catch (KNXException ex) {
				throw failure("Reading object " + objectIndex + " property " + propertyId, ex);
			} catch (InterruptedException ex) {
				throw interrupted(ex);
			}
		}

		@Override
		public byte[] readMemory(int address, int length) throws BusException {
			try {
				return _management.readMemory(destination(), address, length);
			} catch (KNXException ex) {
				throw failure(String.format("Reading memory %04X (%d bytes)", address, length), ex);
			} catch (InterruptedException ex) {
				throw interrupted(ex);
			}
		}

		@Override
		public void disconnect() {
			if (_destination != null) {
				_destination.destroy();
				_destination = null;
			}
		}

		@Override
		public void close() {
			disconnect();
			_management.detach();
		}

		private BusException failure(String what, KNXException ex) {
			return new BusException(what + " of " + _device + " through " + gateway() + " failed: " + ex.getMessage()
				+ " (" + ex.getClass().getSimpleName() + ")", ex);
		}
	}

	private static BusException interrupted(InterruptedException ex) {
		Thread.currentThread().interrupt();
		return new BusException("Interrupted.", ex);
	}

	private static InetSocketAddress resolve(InetSocketAddress gateway) {
		return gateway.isUnresolved() ? new InetSocketAddress(gateway.getHostString(), gateway.getPort()) : gateway;
	}

	private static InetAddress localAddressTowards(InetSocketAddress remote) throws IOException {
		if (remote.isUnresolved()) {
			throw new IOException("unknown host " + remote.getHostString());
		}
		try (DatagramSocket socket = new DatagramSocket()) {
			socket.connect(remote.getAddress(), remote.getPort());
			return socket.getLocalAddress();
		}
	}

}
