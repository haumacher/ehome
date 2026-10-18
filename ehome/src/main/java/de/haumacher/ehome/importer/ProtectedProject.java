/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.top_logic.util.error.TopLogicException;

import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.LocalFileHeader;

/**
 * Reads the project part of a project file that ETS 5 and 6 store as an archive of its own
 * (<code>P-xxxx.zip</code>), protected by the project password or not.
 *
 * <p>
 * ETS encrypts the archive of a password-protected project: ETS 4 and 5 with the traditional ZIP
 * encryption and the password itself, ETS 6 (project schema 21 and later) with AES and a password
 * derived from the project password (see {@link #derivedPassword(String)}). The JDK reads neither,
 * zip4j reads both. As the derivation is known only from other readers of ETS files (xknxproject),
 * both passwords are tried, the derived one first for schema 21 and later.
 * </p>
 */
public class ProtectedProject {

	/**
	 * The first project schema written by ETS 6, which derives the password of the archive.
	 */
	public static final int ETS6_SCHEMA = 21;

	/**
	 * The salt of the derivation of the archive password of ETS 6.
	 */
	private static final byte[] SALT = "21.project.ets.knx.org".getBytes(StandardCharsets.US_ASCII);

	private static final int ITERATIONS = 65536;

	private static final String HMAC = "HmacSHA256";

	/**
	 * The signature of a local file header of a ZIP archive (APPNOTE.TXT 4.3.7).
	 */
	private static final long LOCAL_HEADER_SIGNATURE = 0x04034b50L;

	private static final int LOCAL_HEADER_SIZE = 30;

	/**
	 * The offset of the general purpose flag in a local file header.
	 */
	private static final int GENERAL_PURPOSE_FLAG = 6;

	/**
	 * Bit 0 of the general purpose flag: the entry is encrypted.
	 */
	private static final int ENCRYPTED_FLAG = 0x1;

	/**
	 * Reads the entries of the archive of a project part.
	 *
	 * @param name
	 *        The name of the archive in the project file (<code>P-xxxx.zip</code>), for messages.
	 * @param data
	 *        The content of the archive.
	 * @param schema
	 *        The project schema of the project file, 0 if not known.
	 * @param password
	 *        The project password, <code>null</code> or empty if none was given.
	 * @return The entries of the archive by their names, in the order of the archive.
	 * @throws TopLogicException
	 *         If the archive is protected and no password was given, the password given opens it
	 *         neither as given nor derived, or it cannot be read.
	 */
	public static Map<String, byte[]> read(String name, byte[] data, int schema, String password) {
		boolean encrypted;
		try {
			encrypted = isEncrypted(data);
		} catch (IOException ex) {
			throw new TopLogicException(I18NConstants.ERROR_INVALID_PROJECT_ARCHIVE__ENTRY_DETAIL.fill(name,
				ex.getMessage()), ex);
		}
		if (!encrypted) {
			try {
				return entries(data, null);
			} catch (IOException ex) {
				throw new TopLogicException(I18NConstants.ERROR_INVALID_PROJECT_ARCHIVE__ENTRY_DETAIL.fill(name,
					ex.getMessage()), ex);
			}
		}
		if (password == null || password.isEmpty()) {
			throw new PasswordRequiredException(I18NConstants.ERROR_PASSWORD_REQUIRED__ENTRY.fill(name));
		}
		List<String> candidates = new ArrayList<>();
		if (schema >= ETS6_SCHEMA) {
			candidates.add(derivedPassword(password));
			candidates.add(password);
		} else {
			candidates.add(password);
			candidates.add(derivedPassword(password));
		}
		for (String candidate : candidates) {
			try {
				return entries(data, candidate.toCharArray());
			} catch (IOException ex) {
				// Not this password (or a damaged archive), try the next.
			}
		}
		throw new TopLogicException(I18NConstants.ERROR_WRONG_PASSWORD__ENTRY.fill(name));
	}

	/**
	 * Whether the entries of the given archive are encrypted, read from the general purpose flag of
	 * the first local file header (ETS encrypts all entries of a protected project alike); a stream
	 * reader cannot tell it without starting to decrypt.
	 */
	private static boolean isEncrypted(byte[] data) throws IOException {
		if (data.length < LOCAL_HEADER_SIZE || littleEndian(data, 0, 4) != LOCAL_HEADER_SIGNATURE) {
			throw new IOException("No ZIP archive.");
		}
		return (littleEndian(data, GENERAL_PURPOSE_FLAG, 2) & ENCRYPTED_FLAG) != 0;
	}

	private static long littleEndian(byte[] data, int offset, int length) {
		long result = 0;
		for (int n = length - 1; n >= 0; n--) {
			result = (result << 8) | (data[offset + n] & 0xFF);
		}
		return result;
	}

	private static Map<String, byte[]> entries(byte[] data, char[] password) throws IOException {
		Map<String, byte[]> result = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data), password)) {
			LocalFileHeader header;
			while ((header = zip.getNextEntry()) != null) {
				if (header.isDirectory()) {
					continue;
				}
				result.put(header.getFileName(), readAll(zip));
			}
		}
		return result;
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		int read;
		while ((read = in.read(chunk)) >= 0) {
			buffer.write(chunk, 0, read);
		}
		return buffer.toByteArray();
	}

	/**
	 * The password of the project archive ETS 6 derives from a project password:
	 * <code>base64(PBKDF2-HMAC-SHA256(password as UTF-16LE, "21.project.ets.knx.org", 65536, 32 bytes))</code>.
	 *
	 * <p>
	 * Computed by hand, since the JDK's <code>PBKDF2WithHmacSHA256</code> encodes the password as
	 * UTF-8.
	 * </p>
	 */
	public static String derivedPassword(String password) {
		try {
			Mac mac = Mac.getInstance(HMAC);
			mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_16LE), HMAC));
			// One block: the derived key has the length of the hash (32 bytes).
			mac.update(SALT);
			byte[] u = mac.doFinal(new byte[] { 0, 0, 0, 1 });
			byte[] key = u.clone();
			for (int n = 1; n < ITERATIONS; n++) {
				u = mac.doFinal(u);
				for (int i = 0; i < key.length; i++) {
					key[i] ^= u[i];
				}
			}
			return Base64.getEncoder().encodeToString(key);
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA256 not available.", ex);
		}
	}

}
