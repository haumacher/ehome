/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime.binary;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * The compiled corpora of homecontrol <code>spec/runtime-image.md</code> 13.1 (haumacher/ehome #176):
 * E-Home's compiler makes every binary image of the corpora, which are committed in the homecontrol
 * repository; this test compiles every source again and compares byte for byte.
 *
 * <p>
 * The sources are those of this module's copy (<code>src/test/fixtures/homecontrol</code>: every
 * compiled image decodes into a source that compiles to the same bytes) and of the homecontrol checkout
 * beside this repository
 * (<code>../../homecontrol/spec/fixtures</code> from the module, or the system property
 * {@value #DIR_PROPERTY}). Without the checkout the comparison is skipped with a message.
 * </p>
 *
 * <p>
 * To write the files into the checkout after a change of the format or the compiler (they are
 * committed there in one commit with the change), run from the root of this repository:
 * </p>
 *
 * <pre>
 * ./mvnw -pl ehome test -DskipTests=false -Dtest=TestImageCorpus -Dhomecontrol.regenerate=true
 * </pre>
 */
@SuppressWarnings("javadoc")
public class TestImageCorpus extends TestCase {

	/** The system property naming the homecontrol checkout. */
	public static final String DIR_PROPERTY = "homecontrol.dir";

	/** The system property that writes the files into the checkout instead of comparing them. */
	public static final String REGENERATE_PROPERTY = "homecontrol.regenerate";

	/** The copy of the sources in this module. */
	private static final File COPY = new File("src/test/fixtures/homecontrol");

	/** The directories of the checkout that hold compiled files. */
	private static final List<String> DIRS = List.of("images", "images/cases", "images/binary", "rules", "blocks");

	/** The <code>spec/fixtures</code> of the homecontrol checkout, <code>null</code> if there is none. */
	static File checkout() {
		String dir = System.getProperty(DIR_PROPERTY);
		File root = dir != null ? new File(dir) : new File("../../homecontrol");
		File fixtures = new File(root, "spec/fixtures");
		return fixtures.isDirectory() ? fixtures : null;
	}

	public void testRoundTripOfCopy() throws Exception {
		List<String> roundTrip = new ArrayList<>();
		Map<String, byte[]> files = ImageCorpus.generate(COPY, roundTrip);
		assertTrue(files.containsKey("images/minimal.bin"));
		assertTrue(String.join("\n", roundTrip), roundTrip.isEmpty());
		assertTrue(Arrays.equals(TestImageCompiler.bytes(TestImageCompiler.MINIMAL), files.get("images/minimal.bin")));
		assertTrue(Arrays.equals(TestImageCompiler.bytes(TestImageCompiler.MINIMAL_SIGNED),
			files.get("images/minimal.signed.bin")));
	}

	public void testCommitted() throws Exception {
		File fixtures = checkout();
		if (fixtures == null) {
			System.out.println("TestImageCorpus: no homecontrol checkout at ../../homecontrol (or -D" + DIR_PROPERTY
				+ "): the committed binary images are not compared.");
			return;
		}
		List<String> roundTrip = new ArrayList<>();
		Map<String, byte[]> files = ImageCorpus.generate(fixtures, roundTrip);
		assertTrue(String.join("\n", roundTrip), roundTrip.isEmpty());
		if (Boolean.getBoolean(REGENERATE_PROPERTY)) {
			for (String dir : DIRS) {
				File[] stale = new File(fixtures, dir).listFiles((d, name) -> name.endsWith(".bin")
					|| dir.equals("images/binary") && name.endsWith(".json"));
				for (File file : stale == null ? new File[0] : stale) {
					Files.delete(file.toPath());
				}
			}
			for (Map.Entry<String, byte[]> entry : files.entrySet()) {
				File file = new File(fixtures, entry.getKey());
				file.getParentFile().mkdirs();
				Files.write(file.toPath(), entry.getValue());
			}
			System.out.println("TestImageCorpus: wrote " + files.size() + " files into " + fixtures.getCanonicalPath());
			return;
		}
		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, byte[]> entry : files.entrySet()) {
			File file = new File(fixtures, entry.getKey());
			if (!file.isFile()) {
				problems.add(entry.getKey() + " is missing");
			} else if (!Arrays.equals(Files.readAllBytes(file.toPath()), entry.getValue())) {
				problems.add(entry.getKey() + " differs");
			}
		}
		Set<String> committed = new TreeSet<>();
		for (String dir : DIRS) {
			String[] names = new File(fixtures, dir).list((d, name) -> name.endsWith(".bin")
				|| dir.equals("images/binary") && name.endsWith(".json"));
			for (String name : names == null ? new String[0] : names) {
				committed.add(dir + "/" + name);
			}
		}
		committed.removeAll(files.keySet());
		for (String stale : committed) {
			problems.add(stale + " is not made by the compiler");
		}
		assertTrue("The compiled corpora (regenerate with -D" + REGENERATE_PROPERTY + "=true):\n"
			+ String.join("\n", problems), problems.isEmpty());
	}

	public static Test suite() {
		return new TestSuite(TestImageCorpus.class);
	}

}
