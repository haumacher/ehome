/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.io.IOException;
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
import java.util.function.Function;

import com.top_logic.basic.util.ResKey;

import de.haumacher.ehome.job.Uninterruptible;
import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.DeviceReader;
import de.haumacher.ehome.programming.download.Gateways;
import de.haumacher.ehome.settings.OperatorSettings;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.mgmt.Destination;
import tuwien.auto.calimero.mgmt.LocalDeviceManagementIp;
import tuwien.auto.calimero.mgmt.ManagementClientImpl;
import tuwien.auto.calimero.mgmt.ManagementProcedures;
import tuwien.auto.calimero.mgmt.ManagementProceduresImpl;

/**
 * {@link ScanBus} reaching the bus with Calimero on the {@link BusPath} of the project: one link (a
 * tunnelling connection to the gateway, or the bus session of the project's controller,
 * haumacher/ehome #148), on which the scans of addresses run with Calimero's
 * {@link ManagementProcedures} and the reads of a device with a management client, one after the
 * other.
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

	private final BusPath _path;

	private final KNXNetworkLink _link;

	/**
	 * Opens a tunnelling connection to the gateway.
	 *
	 * @param gateway
	 *        The gateway, see {@link Gateways#parse(String)}.
	 * @throws com.top_logic.util.error.TopLogicException
	 *         If this installation does not reach local networks
	 *         ({@link OperatorSettings#checkLocalNetwork()}, haumacher/ehome #150).
	 */
	public static CalimeroScanBus open(InetSocketAddress gateway) throws BusException {
		return open(BusPath.gateway(gateway));
	}

	/**
	 * Opens a link on the given path.
	 *
	 * @throws com.top_logic.util.error.TopLogicException
	 *         If the path is refused ({@link BusPath#open()}).
	 */
	public static CalimeroScanBus open(BusPath path) throws BusException {
		return new CalimeroScanBus(path);
	}

	private CalimeroScanBus(BusPath path) throws BusException {
		_path = path;
		_link = path.open();
	}

	@Override
	public String gateway() {
		return _path.text();
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
		}, progress, reason -> I18NConstants.SCAN_LINE_FAILED__LINE_PATH_REASON.fill(area + "." + line, gateway(), reason));
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
		}, progress, reason -> I18NConstants.SCAN_ROUTERS_FAILED__PATH_REASON.fill(gateway(), reason));
	}

	@Override
	public List<Integer> programmingMode() throws BusException {
		try (Uninterruptible.Section section = Uninterruptible.interruptible();
				ManagementProceduresImpl procedures = new ManagementProceduresImpl(_link)) {
			List<Integer> result = new ArrayList<>();
			for (IndividualAddress address : procedures.readAddress()) {
				result.add(address.getRawAddress());
			}
			Collections.sort(result);
			return result;
		} catch (KNXException ex) {
			throw new BusException(I18NConstants.SCAN_PROGRAMMING_MODE_FAILED__PATH_REASON.fill(gateway(), ex.getMessage()),
				ex);
		} catch (InterruptedException ex) {
			throw interrupted(ex);
		}
	}

	private interface Scan {
		List<Integer> run(ManagementProcedures procedures) throws Exception;
	}

	/**
	 * Runs a scan of Calimero in a thread of its own and reports its progress by the time it takes
	 * (Calimero tells no progress), as unknown once it takes longer than {@link #SCAN_MILLIS}; a
	 * cancelled job interrupts it.
	 */
	private List<Integer> scan(Scan scan, Progress progress, Function<Object, ResKey> failure) throws BusException {
		ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "ehome-scan"));
		try (Uninterruptible.Section section = Uninterruptible.interruptible()) {
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
							if (elapsed < SCAN_MILLIS) {
								progress.progress((int) (elapsed * 256 / SCAN_MILLIS), 256);
							} else {
								// Slower than estimated (e.g. through a controller): how much is left is not known.
								progress.indeterminate();
							}
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
			throw new BusException(failure.apply(cause.getMessage()), cause);
		} catch (InterruptedException ex) {
			throw interrupted(ex);
		} catch (KNXException ex) {
			throw new BusException(failure.apply(ex.getMessage()), ex);
		} finally {
			executor.shutdownNow();
		}
	}

	@Override
	public DeviceReader device(int address) throws BusException {
		try {
			return new LinkReader(new ManagementClientImpl(_link), address);
		} catch (KNXException ex) {
			throw new BusException(I18NConstants.SCAN_NO_MANAGEMENT__PATH_REASON.fill(gateway(), ex.getMessage()), ex);
		}
	}

	@Override
	public LocalProperties gatewayProperties() throws BusException {
		if (!(_path instanceof BusPath.Gateway gateway)) {
			throw new BusException(I18NConstants.SCAN_GATEWAY_NOT_LOCAL__PATH.fill(gateway()));
		}
		LocalDeviceManagementIp adapter;
		try (Uninterruptible.Section section = Uninterruptible.interruptible()) {
			InetSocketAddress remote = resolve(gateway.address());
			InetSocketAddress local = new InetSocketAddress(BusPath.Gateway.localAddressTowards(remote), 0);
			adapter = LocalDeviceManagementIp.newAdapter(local, remote, false, false, event -> {
				// Closed with the bus.
			});
		} catch (KNXException | IOException ex) {
			throw new BusException(I18NConstants.SCAN_NO_LOCAL_MANAGEMENT__PATH_REASON.fill(gateway(), ex.getMessage()), ex);
		} catch (InterruptedException ex) {
			throw interrupted(ex);
		}
		return new LocalProperties() {
			@Override
			public byte[] readProperty(int objectType, int propertyId, int start, int count) throws BusException {
				int index = adapter.interfaceObjects().indexOf(objectType);
				if (index < 0) {
					throw new BusException(I18NConstants.SCAN_GATEWAY_NO_OBJECT__TYPE.fill(objectType));
				}
				try (Uninterruptible.Section section = Uninterruptible.interruptible()) {
					return adapter.getProperty(index, propertyId, start, count);
				} catch (KNXException ex) {
					throw new BusException(I18NConstants.SCAN_LOCAL_PROPERTY_FAILED__TYPE_PROPERTY_PATH_REASON.fill(objectType,
						propertyId, gateway(), ex.getMessage()), ex);
				} catch (InterruptedException ex) {
					throw interrupted(ex);
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
			try (Uninterruptible.Section section = Uninterruptible.interruptible()) {
				return _management.readDeviceDesc(destination(), 0);
			} catch (KNXException ex) {
				throw failure(reason -> de.haumacher.ehome.programming.download.I18NConstants.ACCESS_DESCRIPTOR_FAILED__DEVICE_PATH_REASON.fill(device(), gateway(),
					reason), ex);
			} catch (InterruptedException ex) {
				throw interrupted(ex);
			}
		}

		@Override
		public byte[] readProperty(int objectIndex, int propertyId, int start, int count) throws BusException {
			try (Uninterruptible.Section section = Uninterruptible.interruptible()) {
				return _management.readProperty(destination(), objectIndex, propertyId, start, count);
			} catch (KNXException ex) {
				throw failure(reason -> de.haumacher.ehome.programming.download.I18NConstants.ACCESS_READ_PROPERTY_FAILED__OBJECT_PROPERTY_DEVICE_PATH_REASON
					.fill(objectIndex, propertyId, device(), gateway(), reason), ex);
			} catch (InterruptedException ex) {
				throw interrupted(ex);
			}
		}

		@Override
		public byte[] readMemory(int address, int length) throws BusException {
			try (Uninterruptible.Section section = Uninterruptible.interruptible()) {
				return _management.readMemory(destination(), address, length);
			} catch (KNXException ex) {
				throw failure(reason -> de.haumacher.ehome.programming.download.I18NConstants.ACCESS_READ_MEMORY_FAILED__ADDRESS_LENGTH_DEVICE_PATH_REASON
					.fill(String.format("%04X", address), length, device(), gateway(), reason), ex);
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

		private String device() {
			return _device.toString();
		}

		private BusException failure(Function<Object, ResKey> failure, KNXException ex) {
			return new BusException(failure.apply(ex.getMessage() + " (" + ex.getClass().getSimpleName() + ")"), ex);
		}
	}

	private static BusException interrupted(InterruptedException ex) {
		Thread.currentThread().interrupt();
		return new BusException(de.haumacher.ehome.programming.download.I18NConstants.ACCESS_INTERRUPTED, ex);
	}

	private static InetSocketAddress resolve(InetSocketAddress gateway) {
		return gateway.isUnresolved() ? new InetSocketAddress(gateway.getHostString(), gateway.getPort()) : gateway;
	}

}
