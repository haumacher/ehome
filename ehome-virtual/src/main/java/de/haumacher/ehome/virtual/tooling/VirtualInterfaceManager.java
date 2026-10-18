/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.tooling;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.top_logic.basic.Logger;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice;
import de.haumacher.ehome.virtual.tooling.server.TransportTiming;
import de.haumacher.ehome.virtual.tooling.server.VirtualInterface;
import de.haumacher.ehome.virtual.tooling.trace.TraceFormat;
import de.haumacher.ehome.virtual.tooling.trace.TraceRecorder;
import de.haumacher.ehome.virtual.tooling.trace.TraceStore;

/**
 * Runs at most one {@link VirtualInterface} at a time, each run recording a new trace into the
 * {@link TraceStore}.
 */
public class VirtualInterfaceManager {

	/** Header: the device. */
	public static final String HEADER_DEVICE = "device";

	/** Header: the mask version. */
	public static final String HEADER_MASK = "mask";

	/** Header: how the load state machines are controlled. */
	public static final String HEADER_LOAD_CONTROL = "load control";

	/** Header: the endpoint of the interface. */
	public static final String HEADER_INTERFACE = "interface";

	/** Header: the timing of the device's transport layer. */
	public static final String HEADER_TRANSPORT = "transport";

	/** Header: when the interface was started. */
	public static final String HEADER_STARTED = "started";

	private final TraceStore _traces;

	private final File _workDir;

	private VirtualInterface _running;

	/**
	 * Creates a {@link VirtualInterfaceManager}.
	 *
	 * @param traces
	 *        Where the traces go.
	 * @param workDir
	 *        The working directory of the KNXnet/IP server.
	 */
	public VirtualInterfaceManager(TraceStore traces, File workDir) {
		_traces = traces;
		_workDir = workDir;
	}

	/**
	 * The traces.
	 */
	public TraceStore traces() {
		return _traces;
	}

	/**
	 * Starts a virtual interface with the given device.
	 *
	 * @param header
	 *        Further header entries of the trace (e.g. the project).
	 * @throws TopLogicException
	 *         If a virtual interface runs already, or the interface cannot be started.
	 */
	public VirtualInterface start(SimulatedDevice device, Endpoint endpoint, Map<String, String> header) {
		return start(device, endpoint, TransportTiming.DEFAULT, header);
	}

	/**
	 * Starts a virtual interface with the given device and the given timing of its transport layer.
	 *
	 * @see #start(SimulatedDevice, Endpoint, Map)
	 */
	public synchronized VirtualInterface start(SimulatedDevice device, Endpoint endpoint, TransportTiming timing,
			Map<String, String> header) {
		if (_running != null) {
			if (_running.isRunning()) {
				throw new TopLogicException(
					I18NConstants.ERROR_ALREADY_RUNNING__NAME_ENDPOINT.fill(_running.name(), _running.endpoint().toString()));
			}
			stop();
		}
		Map<String, String> all = new LinkedHashMap<>();
		all.put(HEADER_DEVICE, device.name());
		all.put(HEADER_MASK, String.format("%04X", device.mask()));
		all.put(HEADER_LOAD_CONTROL, device.kind() + (device.kind() == SimulatedDevice.LoadControlKind.MEMORY
			? ", states answered: " + device.rule() : ""));
		all.put(HEADER_INTERFACE, endpoint.toString());
		all.put(HEADER_TRANSPORT, timing.describe());
		all.put(HEADER_STARTED, Instant.now().toString());
		// Where the values the project does not hold come from (the substitutes).
		all.putAll(device.notes());
		all.putAll(header);
		TraceRecorder recorder;
		try {
			recorder = new TraceRecorder(_traces.newFile(TraceFormat.individual(device.address())), all);
		} catch (IOException ex) {
			throw new TopLogicException(I18NConstants.ERROR_TRACE_FILE__DETAIL.fill(ex.getMessage()), ex);
		}
		try {
			_running = VirtualInterface.start(endpoint, device, recorder, timing, _workDir);
		} catch (RuntimeException ex) {
			recorder.close();
			File file = recorder.file();
			if (file != null && !file.delete()) {
				Logger.warn("The empty trace " + file + " could not be deleted.", VirtualInterfaceManager.class);
			}
			throw ex;
		}
		Logger.info("Virtual interface " + _running.name() + " started on " + endpoint + ", trace " + recorder.file(),
			VirtualInterfaceManager.class);
		return _running;
	}

	/**
	 * Stops the running interface; nothing happens if none runs.
	 */
	public synchronized void stop() {
		if (_running == null) {
			return;
		}
		VirtualInterface stopped = _running;
		_running = null;
		try {
			stopped.close();
		} finally {
			stopped.recorder().close();
			Logger.info("Virtual interface " + stopped.name() + " stopped, " + stopped.recorder().size()
				+ " services recorded in " + stopped.recorder().file(), VirtualInterfaceManager.class);
		}
	}

	/**
	 * The running interface, <code>null</code> if none runs.
	 */
	public synchronized VirtualInterface running() {
		return _running;
	}

}
