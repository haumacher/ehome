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
 * the signature of <code>images/example.json</code> with the test installation key equals
 * <code>images/example.json.sig</code> byte for byte (Ed25519 is deterministic), and the one with the
 * rotated key the vector's; a generated key pair signs and verifies, and its seed is never printed.
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

	public void testExampleImage() throws Exception {
		byte[] image = Files.readAllBytes(new File(FIXTURES, "images/example.json").toPath());
		String sig = Files.readString(new File(FIXTURES, "images/example.json.sig").toPath(), StandardCharsets.US_ASCII);
		Map<?, ?> vector = (Map<?, ?>) _vectors.get("image");
		assertEquals(vector.get("context"), Signatures.CONTEXT);
		assertEquals(vector.get("message"), HexFormat.of().formatHex(Signatures.message(image)));

		String installation = (String) key("installation").get("private_seed_TEST_ONLY");
		String signature = Signatures.signImage(installation, image);
		assertEquals("The detached signature byte for byte (with its line end).", sig, signature + "\n");
		assertEquals(vector.get("signature"), signature);
		assertTrue(Signatures.verifyImage((String) key("installation").get("public_key"), image, signature));
		assertTrue("Upper case is read.", Signatures.verifyImage((String) key("installation").get("public_key"), image,
			signature.toUpperCase()));

		String rotated = Signatures.signImage((String) key("rotated").get("private_seed_TEST_ONLY"), image);
		assertEquals(vector.get("signature_rotated_key"), rotated);
		assertFalse("Not under the other key.", Signatures.verifyImage((String) key("installation").get("public_key"),
			image, rotated));

		byte[] changed = image.clone();
		changed[changed.length - 2] ^= 1;
		assertFalse(Signatures.verifyImage((String) key("installation").get("public_key"), changed, signature));
		assertFalse(Signatures.verifyImage((String) key("installation").get("public_key"), image, "00"));
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
