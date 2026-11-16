/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.connection;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HexFormat;
import java.util.Map;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.connection.Signatures;
import de.haumacher.ehome.connection.Signatures.Keys;
import de.haumacher.ehome.runtime.Json;

/**
 * E-Home's Ed25519 signatures of runtime images (homecontrol issue #35, <code>spec/api.md</code>,
 * "Signed runtime images") against the controller's test vectors
 * (<code>src/test/fixtures/homecontrol/signatures/vectors.json</code>, TEST KEYS ONLY): RFC 8032 section
 * 7.1 test 1 verifies with the JDK and is reproduced from its seed; the fingerprints of the test keys;
 * the context of the binary image's signature; a generated key pair signs and verifies, and its seed
 * is never printed.
 */
@SuppressWarnings("javadoc")
public class TestImageSignatures extends TestCase {

	private static final File FIXTURES = new File("src/test/fixtures/homecontrol");

	private Map<?, ?> _vectors;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_vectors = (Map<?, ?>) Json.parse(Files.readString(new File(FIXTURES, "signatures/vectors.json").toPath()), 8);
	}

	public void testRfc8032Test1() {
		Map<?, ?> test = (Map<?, ?>) _vectors.get("rfc8032_test1");
		byte[] message = HexFormat.of().parseHex((String) test.get("message"));
		byte[] signature = HexFormat.of().parseHex((String) test.get("signature"));
		assertTrue(Signatures.verify((String) test.get("public_key"), message, signature));
		assertEquals(test.get("signature"), HexFormat.of().formatHex(Signatures.sign((String) test.get("private_seed"), message)));
		signature[0] ^= 1;
		assertFalse(Signatures.verify((String) test.get("public_key"), message, signature));
	}

	public void testFingerprints() {
		for (String name : new String[] { "installation", "rotated", "release" }) {
			Map<?, ?> key = key(name);
			assertEquals(name, key.get("fingerprint"), Signatures.fingerprint((String) key.get("public_key")));
			assertEquals(name, key.get("fingerprint"), new Keys((String) key.get("private_seed_TEST_ONLY"),
				(String) key.get("public_key")).fingerprint());
		}
		assertEquals("ce7d 4a41 a476 9ee3", Signatures.fingerprint(
			"13833FBFA81080CDB4C1210131A5BB3E98F17675236E66124AED84CC490BCCB1"));
	}

	/**
	 * The context of the binary image (homecontrol <code>spec/runtime-image.md</code> 9.17); the
	 * vectors' entry of the JSON image signed with <code>v1</code> is history. The signed worked example
	 * is reproduced by {@link test.de.haumacher.ehome.runtime.binary.TestImageCompiler}.
	 */
	public void testContext() throws Exception {
		assertEquals("homecontrol-image-v2", Signatures.CONTEXT);
		assertEquals("homecontrol-image-v1", ((Map<?, ?>) _vectors.get("image")).get("context"));
		byte[] signed = Files.readAllBytes(new File(FIXTURES, "images/minimal.json").toPath());
		byte[] message = Signatures.message(signed);
		assertEquals(52, message.length);
		assertEquals("homecontrol-image-v2", new String(message, 0, 20, StandardCharsets.US_ASCII));
	}

	public void testGeneratedKeys() {
		Keys keys = Signatures.generate();
		assertTrue(Signatures.isKey(keys.seed()));
		assertTrue(Signatures.isKey(keys.publicKey()));
		assertFalse(keys.seed().equals(keys.publicKey()));
		byte[] image = "{\"image\": {}}\n".getBytes(StandardCharsets.UTF_8);
		String signature = Signatures.signImage(keys.seed(), image);
		assertTrue(signature.matches("[0-9a-f]{128}"));
		assertTrue(Signatures.verifyImage(keys.publicKey(), image, signature));
		assertEquals("Deterministic.", signature, Signatures.signImage(keys.seed(), image));
		assertFalse("A new pair each time.", keys.publicKey().equals(Signatures.generate().publicKey()));
		assertFalse("The seed is never printed.", keys.toString().contains(keys.seed()));
	}

	private Map<?, ?> key(String name) {
		return (Map<?, ?>) ((Map<?, ?>) _vectors.get("keys")).get(name);
	}

	public static Test suite() {
		return new TestSuite(TestImageSignatures.class);
	}

}
