/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.monitor;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import com.top_logic.basic.json.JSON;

import de.haumacher.ehome.monitor.DatapointCodec;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.monitor.DatapointValues;

/**
 * The DPT conformance corpus shared with HomeControl (haumacher/ehome #33), a copy in
 * <code>src/test/fixtures/dpt-corpus/</code>: every entry of every file decoded and encoded by
 * {@link DatapointCodec}, refusals included. One test per file of the corpus; a test reports all
 * failing entries of its file at once.
 */
@SuppressWarnings("javadoc")
public class TestDptCorpus extends TestCase {

	private static final File CORPUS = new File("src/test/fixtures/dpt-corpus");

	/** The main types the corpus must have a file for (<code>dpt-corpus.md</code>, "Present"). */
	private static final List<Integer> MAIN_TYPES =
		List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 18, 19, 20, 232, 251);

	private static final double TOLERANCE = 1e-9;

	private final File _file;

	public TestDptCorpus(File file) {
		super(file.getName());
		_file = file;
	}

	@Override
	protected void runTest() throws Throwable {
		Map<?, ?> corpus;
		try (Reader in = Files.newBufferedReader(_file.toPath(), StandardCharsets.UTF_8)) {
			corpus = (Map<?, ?>) JSON.read(in);
		}
		int main = ((Number) corpus.get("main")).intValue();
		assertEquals(_file.getName(), String.format(Locale.ROOT, "dpt-%03d.json", main));
		List<?> entries = (List<?>) corpus.get("entries");
		assertFalse("No entries.", entries.isEmpty());
		List<String> failures = new ArrayList<>();
		for (Object entry : entries) {
			try {
				check(main, (Map<?, ?>) entry);
			} catch (AssertionError | RuntimeException ex) {
				failures.add(JSON.toString(entry) + ": " + ex);
			}
		}
		if (!failures.isEmpty()) {
			fail(failures.size() + " of " + entries.size() + " entries fail:\n" + String.join("\n", failures));
		}
	}

	private static void check(int main, Map<?, ?> entry) {
		Dpt dpt = Dpt.parse((String) entry.get("dpt"));
		assertEquals("Entry of another main type.", main, dpt.main());
		assertTrue("Not coded strictly: " + dpt, DatapointValues.supports(dpt));

		String direction = (String) entry.get("direction");
		boolean decode = direction == null || direction.equals("decode");
		boolean encode = direction == null || direction.equals("encode");
		byte[] bytes = entry.containsKey("bytes") ? bytes((String) entry.get("bytes")) : null;
		Object value = entry.get("value");
		String error = (String) entry.get("error");

		if (error != null) {
			DatapointValues.Status expected = DatapointValues.Status.valueOf(error.toUpperCase(Locale.ROOT));
			if (bytes != null) {
				assertRefused("decode", expected, () -> DatapointCodec.decodeValue(dpt, bytes));
			}
			if (value != null) {
				assertRefused("encode", expected, () -> DatapointCodec.encodeValue(dpt, value));
			}
			return;
		}
		if (decode) {
			Object decoded = DatapointCodec.decodeValue(dpt, bytes);
			assertValue("decode", normalize(main, value), normalize(main, decoded));
		}
		if (encode) {
			byte[] encoded = DatapointCodec.encodeValue(dpt, value);
			assertEquals("encode", hex(bytes), hex(encoded));
		}
	}

	private static void assertRefused(String operation, DatapointValues.Status expected, Runnable action) {
		try {
			action.run();
		} catch (DatapointValues.Refusal ex) {
			assertEquals(operation + " refused", expected, ex.status());
			return;
		}
		fail(operation + " must be refused with " + expected);
	}

	/**
	 * The value with the defaults of the corpus made explicit: the flags of a date with time
	 * (DPT 19) are <code>false</code> when absent, all four channels of DPT 251 valid.
	 */
	private static Object normalize(int main, Object value) {
		if (!(value instanceof Map<?, ?> map)) {
			return value;
		}
		Map<Object, Object> result = new LinkedHashMap<>(map);
		if (main == 19) {
			for (String flag : DatapointValues.DATE_TIME_FLAGS) {
				result.putIfAbsent(flag, Boolean.FALSE);
			}
		}
		if (main == 251) {
			result.putIfAbsent(DatapointValues.VALID, List.of("red", "green", "blue", "white"));
		}
		return result;
	}

	private static void assertValue(String message, Object expected, Object actual) {
		if (expected instanceof Number number) {
			assertTrue(message + ": expected " + expected + ", but was " + actual, actual instanceof Number);
			double e = number.doubleValue();
			double a = ((Number) actual).doubleValue();
			assertTrue(message + ": expected " + expected + ", but was " + actual,
				e == a || Math.abs(e - a) <= TOLERANCE * Math.max(Math.abs(e), Math.abs(a)));
		} else if (expected instanceof Map<?, ?> map) {
			assertTrue(message + ": expected " + expected + ", but was " + actual, actual instanceof Map);
			Map<?, ?> other = (Map<?, ?>) actual;
			assertEquals(message + ": fields", map.keySet(), other.keySet());
			for (Map.Entry<?, ?> field : map.entrySet()) {
				assertValue(message + "." + field.getKey(), field.getValue(), other.get(field.getKey()));
			}
		} else if (expected instanceof List<?> list) {
			assertTrue(message + ": expected " + expected + ", but was " + actual, actual instanceof List);
			List<?> other = (List<?>) actual;
			assertEquals(message + ": size", list.size(), other.size());
			for (int n = 0; n < list.size(); n++) {
				assertValue(message + "[" + n + "]", list.get(n), other.get(n));
			}
		} else {
			assertEquals(message, expected, actual);
		}
	}

	private static byte[] bytes(String hex) {
		return HexFormat.of().parseHex(hex.replace(" ", ""));
	}

	private static String hex(byte[] data) {
		return HexFormat.ofDelimiter(" ").withUpperCase().formatHex(data);
	}

	public static Test suite() throws IOException {
		File[] files = CORPUS.listFiles((dir, name) -> name.matches("dpt-\\d{3}\\.json"));
		if (files == null) {
			throw new IOException("No corpus in " + CORPUS.getAbsolutePath());
		}
		Arrays.sort(files);
		List<String> expected = MAIN_TYPES.stream().map(main -> String.format(Locale.ROOT, "dpt-%03d.json", main)).toList();
		List<String> present = Arrays.stream(files).map(File::getName).toList();
		if (!present.containsAll(expected)) {
			throw new IOException("Corpus files missing in " + CORPUS + ": " + present);
		}
		TestSuite suite = new TestSuite(TestDptCorpus.class.getName());
		for (File file : files) {
			suite.addTest(new TestDptCorpus(file));
		}
		return suite;
	}

}
