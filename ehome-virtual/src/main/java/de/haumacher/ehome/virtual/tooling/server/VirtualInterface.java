/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.tooling.server;

import java.io.File;
import java.util.List;

import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.server.Launcher;
import tuwien.auto.calimero.server.VirtualLink;

import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.server.KnxServer;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice;
import de.haumacher.ehome.virtual.tooling.I18NConstants;
import de.haumacher.ehome.virtual.tooling.trace.TraceFormat;
import de.haumacher.ehome.virtual.tooling.trace.TraceRecorder;

/**
 * A KNXnet/IP interface whose KNX subnet holds exactly one {@link SimulatedDevice} and nothing else:
 * search and description, tunnelling (no KNX Secure, no routing), on one local address.
 *
 * <p>
 * Built on calimero-server ({@link KnxServer}): its {@link Launcher} sets up a
 * {@link tuwien.auto.calimero.server.knxnetip.KNXnetIPServer} with one service container whose
 * subnet is a {@link VirtualLink} - a subnet in memory without a connection to any bus or gateway,
 * so nothing a client sends is forwarded anywhere. The only device on it is the
 * {@link SimulatedDevice} behind a transport and application layer of E-Home ({@link DeviceStack},
 * with the timing of {@link TransportTiming}). Every frame the device receives or sends is recorded
 * by the {@link DeviceStack} ({@link FrameDecoder}, {@link TraceRecorder}).
 * </p>
 */
public final class VirtualInterface implements AutoCloseable {

	/** The prefix of the name the interface announces. */
	public static final String NAME_PREFIX = "E-Home virtual ";

	private final Endpoint _endpoint;

	private final SimulatedDevice _device;

	private final TraceRecorder _recorder;

	private final KnxServer _server;

	private final int _serverAddress;

	/** The name announced, with the address the device had at the start. */
	private final String _name;

	private KNXNetworkLink _deviceLink;

	private DeviceStack _stack;

	private final TransportTiming _timing;

	private VirtualInterface(Endpoint endpoint, SimulatedDevice device, TraceRecorder recorder, TransportTiming timing,
			KnxServer server, int serverAddress, String name) {
		_endpoint = endpoint;
		_timing = timing;
		_device = device;
		_recorder = recorder;
		_server = server;
		_serverAddress = serverAddress;
		_name = name;
	}

	/**
	 * Starts a virtual interface.
	 *
	 * @param workDir
	 *        The directory for the server's configuration and the interface objects it saves.
	 * @throws TopLogicException
	 *         If the interface cannot be started, with the reason.
	 */
	public static VirtualInterface start(Endpoint endpoint, SimulatedDevice device, TraceRecorder recorder,
			TransportTiming timing, File workDir) {
		int serverAddress = serverAddress(device.address());
		String name = NAME_PREFIX + TraceFormat.individual(device.address());
		KnxServer server = KnxServer.start(endpoint, name, serverAddress,
			List.of(tunnelAddress(serverAddress, device.address())), workDir);
		VirtualInterface result = new VirtualInterface(endpoint, device, recorder, timing, server, serverAddress, name);
		try {
			result.connect();
		} catch (RuntimeException ex) {
			result.close();
			throw ex;
		}
		return result;
	}

	private void connect() {
		int address = _device.address();
		_deviceLink = _server.subnet().createDeviceLink(new IndividualAddress(address));
		_stack = new DeviceStack(_device, _deviceLink, _recorder, _timing, _serverAddress);
	}

	/**
	 * The name the interface announces (at most 30 characters, as KNXnet/IP allows).
	 */
	public String name() {
		return _name;
	}

	/**
	 * Presses the programming button of the device (see {@link DeviceStack#pressProgrammingButton()}).
	 *
	 * @return Whether the device is in programming mode now.
	 */
	public boolean pressProgrammingButton() {
		DeviceStack stack = _stack;
		if (stack == null) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_RUNNING);
		}
		return stack.pressProgrammingButton();
	}

	/**
	 * Whether the interface routes frames to the device at its current address (its line, or its
	 * area for an area coupler): a device that took an address on another line cannot be reached.
	 */
	public boolean reachable() {
		return DeviceStack.routes(_serverAddress, _device.address());
	}

	/**
	 * Where the interface listens.
	 */
	public Endpoint endpoint() {
		return _endpoint;
	}

	/**
	 * The simulated device.
	 */
	public SimulatedDevice device() {
		return _device;
	}

	/**
	 * The timing of the device's transport layer.
	 */
	public TransportTiming timing() {
		return _timing;
	}

	/**
	 * The recorder of the trace.
	 */
	public TraceRecorder recorder() {
		return _recorder;
	}

	/**
	 * The individual address of the interface itself (the server, the coupler of the device's
	 * line); the tunnelling connection gets area.line.255.
	 */
	public int serverAddress() {
		return _serverAddress;
	}

	/**
	 * Stops the interface and waits until its port is free.
	 */
	@Override
	public void close() {
		if (_stack != null) {
			_stack.close();
			_stack = null;
		}
		if (_deviceLink != null) {
			_deviceLink.close();
			_deviceLink = null;
		}
		_server.close();
	}

	/**
	 * Whether the interface is up.
	 */
	public boolean isRunning() {
		return _server.isRunning();
	}

	/**
	 * Whether the port of the endpoint can be bound for UDP and for TCP (no server listens there; the
	 * KNXnet/IP server uses both).
	 */
	public static boolean portFree(Endpoint endpoint) {
		return KnxServer.portFree(endpoint);
	}

	/**
	 * The address of the server: the coupler of the device's line (area.line.0), so that Calimero's
	 * gateway routes the frames of the tunnelling connection into the subnet of the device; the
	 * area coupler (area.0.0) for a device numbered 0.
	 *
	 * @throws TopLogicException
	 *         For a device at area.0.0, an area coupler.
	 */
	private static int serverAddress(int device) {
		if ((device & 0xFF) != 0) {
			return device & 0xFF00;
		}
		if ((device & 0x0F00) != 0) {
			return device & 0xF000;
		}
		throw new TopLogicException(I18NConstants.ERROR_COUPLER_ADDRESS__ADDRESS.fill(TraceFormat.individual(device)));
	}

	/**
	 * The address of the tunnelling connection: area.line.255 of the server's line, or .254 if the
	 * device has .255.
	 */
	private static int tunnelAddress(int server, int device) {
		int result = server | 0xFF;
		return result == device ? result - 1 : result;
	}

}
