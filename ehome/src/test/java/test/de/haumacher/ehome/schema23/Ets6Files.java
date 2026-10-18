/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.schema23;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.logging.Level;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.importer.ProtectedProject;

import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;

/**
 * Builds variants of ETS project files in memory and imports them, for the tests of project schema
 * 23 (ETS 6).
 */
final class Ets6Files {

	/**
	 * The artificial ETS 6 project file.
	 */
	static final String FIXTURE = "src/test/fixtures/TestEts6.knxproj";

	/**
	 * The folder of the project part of {@link #FIXTURE}.
	 */
	static final String PROJECT = "P-01A0";

	/**
	 * How the project part is stored in a variant of a project file.
	 */
	enum Protection {
		/** As an archive of its own without a password. */
		PLAIN,
		/** As an archive encrypted with AES and the password ETS 6 derives from the project password. */
		AES_DERIVED,
		/** As an archive encrypted with the traditional ZIP encryption and the project password itself (ETS 4/5). */
		ZIP_CRYPTO
	}

	private Ets6Files() {
		// Static helpers.
	}

	/**
	 * The entries of the given archive by their names, in archive order.
	 */
	static Map<String, byte[]> entries(byte[] archive) throws IOException {
		Map<String, byte[]> result = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				if (!entry.isDirectory()) {
					result.put(entry.getName(), in.readAllBytes());
				}
			}
		}
		return result;
	}

	/**
	 * An archive of the given entries.
	 */
	static byte[] archive(Map<String, byte[]> entries) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (ZipOutputStream out = new ZipOutputStream(buffer)) {
			for (Entry<String, byte[]> entry : entries.entrySet()) {
				out.putNextEntry(new ZipEntry(entry.getKey()));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
		return buffer.toByteArray();
	}

	/**
	 * The fixture with its project part stored as ETS 5/6 store it: an archive <code>P-01A0.zip</code>
	 * placed before the manufacturer data, protected as given.
	 *
	 * @param password
	 *        The project password; ignored for {@link Protection#PLAIN}.
	 */
	static byte[] withProjectArchive(Protection protection, String password) throws IOException {
		Map<String, byte[]> fixture = entries(Files.readAllBytes(Path.of(FIXTURE)));
		Map<String, byte[]> project = new LinkedHashMap<>();
		Map<String, byte[]> rest = new LinkedHashMap<>();
		for (Entry<String, byte[]> entry : fixture.entrySet()) {
			if (entry.getKey().startsWith(PROJECT + "/")) {
				project.put(entry.getKey().substring(PROJECT.length() + 1), entry.getValue());
			} else {
				rest.put(entry.getKey(), entry.getValue());
			}
		}
		Map<String, byte[]> result = new LinkedHashMap<>();
		// ETS 6 writes the project archive before the folders of the manufacturers.
		result.put("knx_master.xml", rest.remove("knx_master.xml"));
		result.put(PROJECT + ".zip", projectArchive(project, protection, password));
		result.putAll(rest);
		return archive(result);
	}

	private static byte[] projectArchive(Map<String, byte[]> entries, Protection protection, String password)
			throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		String zipPassword = switch (protection) {
			case PLAIN -> null;
			case AES_DERIVED -> ProtectedProject.derivedPassword(password);
			case ZIP_CRYPTO -> password;
		};
		try (net.lingala.zip4j.io.outputstream.ZipOutputStream out = new net.lingala.zip4j.io.outputstream.ZipOutputStream(
			buffer, zipPassword == null ? null : zipPassword.toCharArray())) {
			for (Entry<String, byte[]> entry : entries.entrySet()) {
				ZipParameters parameters = new ZipParameters();
				parameters.setFileNameInZip(entry.getKey());
				parameters.setCompressionMethod(CompressionMethod.DEFLATE);
				if (protection != Protection.PLAIN) {
					parameters.setEncryptFiles(true);
					if (protection == Protection.AES_DERIVED) {
						parameters.setEncryptionMethod(EncryptionMethod.AES);
						parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
					} else {
						parameters.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
					}
				}
				out.putNextEntry(parameters);
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
		return buffer.toByteArray();
	}

	/**
	 * Imports the given project file in a transaction of its own.
	 *
	 * @return The importer, with the messages of the import.
	 */
	static Importer importFile(byte[] file, String password) throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		ApplicationModelBinding binding = new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false);
		Importer importer = new Importer(binding);
		try (InputStream in = new ByteArrayInputStream(file)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importStream(in, password);
				tx.commit();
			}
		}
		return importer;
	}

	/**
	 * The errors among the given messages.
	 */
	static List<String> errors(List<BufferingI18NLog.Entry> messages) {
		List<String> result = new ArrayList<>();
		for (BufferingI18NLog.Entry entry : messages) {
			if (entry.getLevel() == Level.ERROR
				|| entry.getLevel() == Level.FATAL) {
				result.add(String.valueOf(entry.getMessage()));
			}
		}
		return result;
	}

}
