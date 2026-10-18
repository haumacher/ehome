/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.tooling.trace;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.Logger;

/**
 * Records the services of the virtual device: keeps them in memory and appends each as a line to
 * the trace file (see {@link TraceFormat}), so that a trace survives an abort of the application.
 *
 * <p>
 * Called from the threads of the KNXnet/IP server; all methods are synchronized.
 * </p>
 */
public class TraceRecorder implements AutoCloseable {

	private final File _file;

	private final List<TraceEntry> _entries = new ArrayList<>();

	private BufferedWriter _out;

	/**
	 * Creates a {@link TraceRecorder} writing a new trace file with the given header.
	 *
	 * @param file
	 *        The trace file, <code>null</code> to keep the trace in memory only.
	 */
	public TraceRecorder(File file, Map<String, String> header) throws IOException {
		_file = file;
		if (file != null) {
			_out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
			_out.append(TraceFormat.TITLE).append('\n');
			for (Map.Entry<String, String> entry : header.entrySet()) {
				_out.append(TraceFormat.header(entry.getKey(), entry.getValue())).append('\n');
			}
			_out.flush();
		}
	}

	/**
	 * The trace file, <code>null</code> if the trace is kept in memory only.
	 */
	public File file() {
		return _file;
	}

	/**
	 * Records a service.
	 */
	public synchronized void record(TraceEntry entry) {
		_entries.add(entry);
		if (_out != null) {
			try {
				_out.append(TraceFormat.format(entry)).append('\n');
				_out.flush();
			} catch (IOException ex) {
				Logger.error("Writing the trace " + _file + " failed, the rest is kept in memory only.", ex,
					TraceRecorder.class);
				closeFile();
			}
		}
	}

	/**
	 * The services recorded so far.
	 */
	public synchronized List<TraceEntry> entries() {
		return new ArrayList<>(_entries);
	}

	/**
	 * The number of services recorded so far.
	 */
	public synchronized int size() {
		return _entries.size();
	}

	@Override
	public synchronized void close() {
		closeFile();
	}

	private void closeFile() {
		if (_out != null) {
			try {
				_out.close();
			} catch (IOException ex) {
				Logger.error("Closing the trace " + _file + " failed.", ex, TraceRecorder.class);
			}
			_out = null;
		}
	}

}
