/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.blocks;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.runtime.Json;
import test.de.haumacher.ehome.rules.TestRuleCorpus;

/**
 * The block conformance corpus of HomeControl (<code>spec/fixtures/blocks/</code>, copied to
 * <code>src/test/fixtures/homecontrol/blocks/</code>, format in its <code>README.md</code>) run
 * against E-Home's block runtime (<code>de.haumacher.ehome.blocks</code>) inside the reference rule
 * interpreter, by the runner of the rules corpus ({@link TestRuleCorpus.CaseRun}), as the
 * controller's <code>core/test/rules_corpus_test.cpp</code> runs both corpora: one test per case,
 * reporting every difference of a case at once, and a test that the copy holds exactly the cases of
 * the corpus' own index.
 */
public class TestBlockCorpus extends TestCase {

	/** The directory of the corpus copy. */
	public static final File CORPUS = new File("src/test/fixtures/homecontrol/blocks");

	private final File _file;

	/**
	 * Creates a {@link TestBlockCorpus} for a case, or for the index check with <code>null</code>.
	 */
	public TestBlockCorpus(File file) {
		super(file == null ? "index" : file.getName());
		_file = file;
	}

	@Override
	protected void runTest() throws Throwable {
		if (_file == null) {
			checkIndex();
			return;
		}
		Map<?, ?> json = (Map<?, ?>) Json.parse(Files.readString(_file.toPath(), StandardCharsets.UTF_8), 100);
		TestRuleCorpus.CaseRun run = new TestRuleCorpus.CaseRun(json);
		run.run();
		if (!run.failures().isEmpty()) {
			fail(_file.getName() + ": " + json.get("title") + "\n" + String.join("\n", run.failures()));
		}
	}

	private static void checkIndex() throws IOException {
		String readme = Files.readString(new File(CORPUS, "README.md").toPath(), StandardCharsets.UTF_8);
		Set<String> indexed = new TreeSet<>();
		Matcher matcher = Pattern.compile("^\\| `(\\d\\d-[a-z0-9-]+)` \\|", Pattern.MULTILINE).matcher(readme);
		while (matcher.find()) {
			indexed.add(matcher.group(1) + ".json");
		}
		Set<String> present = new TreeSet<>(Arrays.asList(CORPUS.list((dir, name) -> name.endsWith(".json"))));
		assertEquals("The index lists the 61 cases of homecontrol #60 to #64.", 61, indexed.size());
		assertEquals("The copy holds exactly the cases of the corpus index.", indexed, present);
	}

	/**
	 * The index check and one test per case.
	 */
	public static Test suite() throws IOException {
		File[] files = CORPUS.listFiles((dir, name) -> name.endsWith(".json"));
		if (files == null || files.length == 0) {
			throw new IOException("No corpus in " + CORPUS.getAbsolutePath());
		}
		Arrays.sort(files);
		TestSuite suite = new TestSuite(TestBlockCorpus.class.getName());
		suite.addTest(new TestBlockCorpus(null));
		for (File file : files) {
			suite.addTest(new TestBlockCorpus(file));
		}
		return suite;
	}

}
