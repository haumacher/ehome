/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime.binary;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.connection.Signatures;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.binary.ImageCompiler;
import de.haumacher.ehome.runtime.binary.ImageDecoder;

/**
 * The compiler of the binary runtime image against the worked example of homecontrol
 * <code>spec/runtime-image.md</code> 13: <code>minimal.json</code> compiles to the 112 bytes spelled
 * out there with the CRC <code>0x59772c42</code>, and signed with the test installation key of
 * <code>spec/fixtures/signatures/vectors.json</code> (TEST KEY ONLY) to the 176 bytes with the
 * signature given there; the decoder reads both back into the source.
 */
@SuppressWarnings("javadoc")
public class TestImageCompiler extends TestCase {

	private static final File FIXTURES = new File("src/test/fixtures/homecontrol");

	/** The whole file of <code>spec/runtime-image.md</code> 13. */
	static final String MINIMAL = "48 43 49 4d 01 00 00 00 70 00 00 00 42 2c 77 59"
		+ " 00 00 00 00 20 00 02 00 00 00 00 00 00 00 00 00"
		+ " 01 00 00 00 40 00 00 00 20 00 00 00 20 00 00 00"
		+ " 02 00 00 00 60 00 00 00 0d 00 00 00 00 00 00 00"
		+ " 05 00 00 00 00 00 00 00 01 00 00 00 00 00 00 00"
		+ " 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"
		+ " 00 31 2e 30 00 6d 69 6e 69 6d 61 6c 00 00 00 00";

	/** The signed file of <code>spec/runtime-image.md</code> 13. */
	static final String MINIMAL_SIGNED = "48 43 49 4d 01 00 00 00 b0 00 00 00 87 ff f6 f6"
		+ " 01 00 00 00 20 00 02 00 00 00 00 00 00 00 00 00"
		+ " 01 00 00 00 40 00 00 00 20 00 00 00 20 00 00 00"
		+ " 02 00 00 00 60 00 00 00 0d 00 00 00 00 00 00 00"
		+ " 05 00 00 00 00 00 00 00 01 00 00 00 00 00 00 00"
		+ " 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"
		+ " 00 31 2e 30 00 6d 69 6e 69 6d 61 6c 00 00 00 00"
		+ " d8 39 e1 4f 50 d3 4b d3 97 ef 9d 57 2d d7 af 3e"
		+ " a8 3f d8 ce 24 a8 4e bb 28 ee ed 99 b4 1e 22 2d"
		+ " 90 35 04 e9 07 2e 01 ac 4f 86 5c 39 3c b3 42 83"
		+ " fd 39 a2 ef 6e 21 aa 0f d6 7b e4 bf 92 0f 28 06";

	static byte[] bytes(String hex) {
		return HexFormat.of().parseHex(hex.replace(" ", ""));
	}

	static Map<?, ?> minimal() throws Exception {
		return (Map<?, ?>) Json.parse(Files.readString(new File(FIXTURES, "images/minimal.json").toPath()), 48);
	}

	static String installationSeed() throws Exception {
		Map<?, ?> vectors = (Map<?, ?>) Json.parse(Files.readString(new File(FIXTURES, "signatures/vectors.json").toPath()), 8);
		return (String) ((Map<?, ?>) ((Map<?, ?>) vectors.get("keys")).get("installation")).get("private_seed_TEST_ONLY");
	}

	public void testMinimal() throws Exception {
		byte[] image = ImageCompiler.compile(minimal());
		assertEquals(112, image.length);
		assertEquals(0x59772c42, ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).getInt(12));
		assertEquals(MINIMAL, HexFormat.ofDelimiter(" ").formatHex(image));
		assertTrue(Arrays.equals(bytes(MINIMAL), image));
	}

	public void testMinimalSigned() throws Exception {
		byte[] image = ImageCompiler.compile(minimal(), ImageCompiler.Options.signed(installationSeed()));
		assertEquals(176, image.length);
		assertEquals("c5e66fdbb811651835cd1b864e30e99b4b3a6070f51e8949d39c35807d3a79d8",
			HexFormat.of().formatHex(Arrays.copyOfRange(Signatures.message(Arrays.copyOf(image, 112)), 20, 52)));
		assertEquals("686f6d65636f6e74726f6c2d696d6167652d7632",
			HexFormat.of().formatHex(Arrays.copyOf(Signatures.message(Arrays.copyOf(image, 112)), 20)));
		assertEquals(MINIMAL_SIGNED, HexFormat.ofDelimiter(" ").formatHex(image));
		assertTrue(Signatures.verify("13833fbfa81080cdb4c1210131a5bb3e98f17675236e66124aed84cc490bccb1",
			Signatures.message(Arrays.copyOf(image, 112)), Arrays.copyOfRange(image, 112, 176)));
	}

	public void testDecodeMinimal() throws Exception {
		assertEquals(Json.write(minimal()), Json.write(ImageDecoder.decode(bytes(MINIMAL))));
		assertEquals(Json.write(minimal()), Json.write(ImageDecoder.decode(bytes(MINIMAL_SIGNED))));
		ImageDecoder.Header header = ImageDecoder.header(bytes(MINIMAL_SIGNED));
		assertEquals("1.0", header.format());
		assertTrue(header.signed());
		assertEquals(176, header.length());
		assertEquals("f6f6ff87", header.crcText());
	}

	public static Test suite() {
		return new TestSuite(TestImageCompiler.class);
	}

}
