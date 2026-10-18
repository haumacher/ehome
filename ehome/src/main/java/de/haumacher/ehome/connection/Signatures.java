/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The Ed25519 signatures of runtime images (homecontrol issue #35, <code>spec/api.md</code>, "Signed
 * runtime images"), with the JDK's Ed25519 (RFC 8032, "pure" Ed25519).
 *
 * <p>
 * A key is written as the controller reads it: the public key as the 32 bytes of RFC 8032 section
 * 5.1.5 in 64 lower-case hex digits, the private key as its 32-byte seed (RFC 8032 section 5.1.5, the
 * "private key" there) in 64 hex digits. The JDK encodes keys as X.509 <code>SubjectPublicKeyInfo</code>
 * and PKCS #8 <code>PrivateKeyInfo</code>; for Ed25519 both are a fixed prefix (the algorithm
 * identifier 1.3.101.112 and the lengths) followed by those 32 bytes, so the conversion cuts or adds
 * the prefix ({@link #X509_PREFIX}, {@link #PKCS8_PREFIX}).
 * </p>
 *
 * <p>
 * The signature of an image is Ed25519 over <code>M = "homecontrol-image-v1" || SHA-256(image)</code>
 * ({@link #message(byte[])}), the 64 bytes <code>R || S</code> in 128 lower-case hex digits. Ed25519 is
 * deterministic: the same seed and image give the same signature, which the controller's test
 * vectors pin.
 * </p>
 */
public final class Signatures {

	/** The context of the signed message, 20 ASCII bytes without terminator. */
	public static final String CONTEXT = "homecontrol-image-v1";

	/** The DER prefix of an Ed25519 public key in X.509 <code>SubjectPublicKeyInfo</code>. */
	static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

	/** The DER prefix of an Ed25519 private key (version 1, no public key) in PKCS #8. */
	static final byte[] PKCS8_PREFIX = HexFormat.of().parseHex("302e020100300506032b657004220420");

	private static final String ALGORITHM = "Ed25519";

	/**
	 * A key pair in the encodings of the controller.
	 *
	 * @param seed
	 *        The private key: its seed, 64 hex digits.
	 * @param publicKey
	 *        The public key, 64 hex digits.
	 */
	public record Keys(String seed, String publicKey) {

		/**
		 * The fingerprint of the public key ({@link Signatures#fingerprint(String)}).
		 */
		public String fingerprint() {
			return Signatures.fingerprint(publicKey);
		}

		@Override
		public String toString() {
			// Never the seed.
			return "Keys(" + fingerprint() + ")";
		}
	}

	private Signatures() {
		// Static helpers.
	}

	/**
	 * A new key pair from the JDK's generator (a <code>SecureRandom</code> of the platform).
	 */
	public static Keys generate() {
		try {
			KeyPair pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
			return new Keys(HexFormat.of().formatHex(raw(pair.getPrivate().getEncoded(), PKCS8_PREFIX)),
				HexFormat.of().formatHex(raw(pair.getPublic().getEncoded(), X509_PREFIX)));
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("The JDK has no Ed25519.", ex);
		}
	}

	/**
	 * The 32 key bytes of a JDK encoding: what follows the prefix.
	 */
	private static byte[] raw(byte[] encoded, byte[] prefix) {
		if (encoded.length != prefix.length + 32
			|| !Arrays.equals(Arrays.copyOf(encoded, prefix.length), prefix)) {
			throw new IllegalStateException("Unexpected encoding of an Ed25519 key: " + encoded.length + " bytes.");
		}
		return Arrays.copyOfRange(encoded, prefix.length, encoded.length);
	}

	private static byte[] encoded(byte[] prefix, String hex) {
		byte[] key = key(hex);
		byte[] result = Arrays.copyOf(prefix, prefix.length + key.length);
		System.arraycopy(key, 0, result, prefix.length, key.length);
		return result;
	}

	private static byte[] key(String hex) {
		if (!isKey(hex)) {
			throw new IllegalArgumentException("An Ed25519 key is 64 hex digits.");
		}
		return HexFormat.of().parseHex(hex.toLowerCase(Locale.ROOT));
	}

	/**
	 * Whether the text is a key in the controller's encoding: 64 hex digits.
	 */
	public static boolean isKey(String hex) {
		return hex != null && hex.matches("[0-9a-fA-F]{64}");
	}

	/**
	 * The fingerprint of a public key (64 hex digits) as the controller shows it: the first 8 bytes of
	 * SHA-256 over the 32 key bytes, as 16 lower-case hex digits in four groups of four separated by a
	 * space (<code>ce7d 4a41 a476 9ee3</code>).
	 */
	public static String fingerprint(String publicKey) {
		String hex = HexFormat.of().formatHex(sha256(key(publicKey)), 0, 8);
		return hex.substring(0, 4) + " " + hex.substring(4, 8) + " " + hex.substring(8, 12) + " " + hex.substring(12);
	}

	/**
	 * The message an image's signature covers: the 20 bytes of {@link #CONTEXT} followed by the 32
	 * bytes of <code>SHA-256(image)</code>.
	 */
	public static byte[] message(byte[] image) {
		byte[] context = CONTEXT.getBytes(StandardCharsets.US_ASCII);
		byte[] digest = sha256(image);
		byte[] result = Arrays.copyOf(context, context.length + digest.length);
		System.arraycopy(digest, 0, result, context.length, digest.length);
		return result;
	}

	/**
	 * The signature of the image's exact bytes with the given private key (its seed): 128 lower-case
	 * hex digits.
	 */
	public static String signImage(String seed, byte[] image) {
		return HexFormat.of().formatHex(sign(seed, message(image)));
	}

	/**
	 * The plain Ed25519 signature of the message with the given private key (its seed, 64 hex digits).
	 */
	public static byte[] sign(String seed, byte[] message) {
		try {
			PrivateKey key = KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(
				encoded(PKCS8_PREFIX, seed)));
			Signature signature = Signature.getInstance(ALGORITHM);
			signature.initSign(key);
			signature.update(message);
			return signature.sign();
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Signing with Ed25519 failed.", ex);
		}
	}

	/**
	 * Whether the signature (128 hex digits) of the image verifies under the public key (64 hex
	 * digits), as the controller checks it.
	 */
	public static boolean verifyImage(String publicKey, byte[] image, String signature) {
		if (signature == null || !signature.matches("[0-9a-fA-F]{128}")) {
			return false;
		}
		return verify(publicKey, message(image), HexFormat.of().parseHex(signature.toLowerCase(Locale.ROOT)));
	}

	/**
	 * Whether the plain Ed25519 signature of the message verifies under the public key (64 hex
	 * digits).
	 */
	public static boolean verify(String publicKey, byte[] message, byte[] signature) {
		try {
			PublicKey key = KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(
				encoded(X509_PREFIX, publicKey)));
			Signature verifier = Signature.getInstance(ALGORITHM);
			verifier.initVerify(key);
			verifier.update(message);
			return verifier.verify(signature);
		} catch (GeneralSecurityException ex) {
			return false;
		}
	}

	private static byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("The JDK has no SHA-256.", ex);
		}
	}

}
