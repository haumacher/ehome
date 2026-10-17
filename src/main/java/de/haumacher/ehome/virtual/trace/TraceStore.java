/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.trace;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The directory holding the traces of the virtual device, one file per run of a virtual interface
 * (<code>trace-&lt;date&gt;-&lt;time&gt;-&lt;device address&gt;.txt</code>, {@link TraceFormat}).
 */
public class TraceStore {

	/** The suffix of a trace file. */
	public static final String SUFFIX = ".txt";

	/** The prefix of a trace file. */
	public static final String PREFIX = "trace-";

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private final File _directory;

	/**
	 * A trace file with what its header says.
	 *
	 * @param file
	 *        The file.
	 * @param header
	 *        Its header entries.
	 * @param services
	 *        The number of services it holds.
	 */
	public record TraceInfo(File file, Map<String, String> header, int services) {

		/**
		 * The name of the file.
		 */
		public String name() {
			return file.getName();
		}
	}

	/**
	 * Creates a {@link TraceStore} in the given directory.
	 */
	public TraceStore(File directory) {
		_directory = directory;
	}

	/**
	 * The directory.
	 */
	public File directory() {
		return _directory;
	}

	/**
	 * A new trace file for the device of the given address (not created yet).
	 */
	public File newFile(String deviceAddress) throws IOException {
		Files.createDirectories(_directory.toPath());
		String base = PREFIX + LocalDateTime.now().format(STAMP) + "-" + deviceAddress;
		File result = new File(_directory, base + SUFFIX);
		int n = 2;
		while (result.exists()) {
			result = new File(_directory, base + "-" + n++ + SUFFIX);
		}
		return result;
	}

	/**
	 * The traces, the newest first.
	 */
	public List<TraceInfo> list() {
		File[] files = _directory.listFiles((dir, name) -> name.startsWith(PREFIX) && name.endsWith(SUFFIX));
		List<TraceInfo> result = new ArrayList<>();
		if (files == null) {
			return result;
		}
		for (File file : files) {
			try (Reader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
				TraceFormat.Trace trace = TraceFormat.read(in);
				result.add(new TraceInfo(file, trace.header(), trace.entries().size()));
			} catch (IOException ex) {
				result.add(new TraceInfo(file, Map.of("error", String.valueOf(ex.getMessage())), 0));
			}
		}
		result.sort(Comparator.comparing(TraceInfo::name).reversed());
		return result;
	}

	/**
	 * The trace file of the given name, <code>null</code> if there is none.
	 */
	public File file(String name) {
		if (name == null || name.contains("/") || name.contains("\\") || !name.startsWith(PREFIX)) {
			return null;
		}
		File result = new File(_directory, name);
		return result.isFile() ? result : null;
	}

}
