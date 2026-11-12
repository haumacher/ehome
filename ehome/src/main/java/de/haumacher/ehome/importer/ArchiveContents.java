/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.xml.XMLStreamUtil;
import com.top_logic.util.error.TopLogicException;

/**
 * What an ETS project file or product file holds, read in a quick first pass before its import
 * (haumacher/ehome #84): the number of XML entries the import reads, the number of application
 * programs among them, and the opened project archives of ETS 5/6 (<code>P-xxxx.zip</code>).
 *
 * <p>
 * The pass reads the archive without parsing its entries (only the root element of the entries
 * before a project archive, for the version of the project schema). It opens a project archive
 * protected by a password, so that a missing or wrong password is refused here, before anything is
 * imported; the import then uses the opened archive instead of decrypting it again.
 * </p>
 *
 * @see Importer#importStream(InputStream, String, ArchiveContents, ImportProgress)
 * @see Importer#importProductData(InputStream, ArchiveContents, ImportProgress)
 */
public final class ArchiveContents {

	private final int _entries;

	private final int _programs;

	private final Map<String, Map<String, byte[]>> _projectArchives;

	private ArchiveContents(int entries, int programs, Map<String, Map<String, byte[]>> projectArchives) {
		_entries = entries;
		_programs = programs;
		_projectArchives = Collections.unmodifiableMap(projectArchives);
	}

	/**
	 * The number of XML entries the import reads, those of the project archives included. Not counted
	 * is the baggage of a manufacturer (its index <code>Baggages.xml</code> and its files), which the
	 * import keeps as files of the manufacturer without reading it with the mapping (see
	 * {@link BaggageImport}).
	 */
	public int getEntries() {
		return _entries;
	}

	/**
	 * The number of application programs (entries <code>M-xxxx/M-xxxx_A-….xml</code>) of the file.
	 */
	public int getPrograms() {
		return _programs;
	}

	/**
	 * The entries of the project archives of the file by their names, each archive by its name in
	 * the file (<code>P-xxxx.zip</code>).
	 */
	Map<String, Map<String, byte[]>> getProjectArchives() {
		return _projectArchives;
	}

	/**
	 * Reads the contents of the project file or product file in the given stream.
	 *
	 * @param in
	 *        The file.
	 * @param password
	 *        The project password, <code>null</code> or empty if none was given.
	 * @param productData
	 *        Whether the file is a product file, whose project archives (refused by its import) are
	 *        not opened.
	 * @throws PasswordRequiredException
	 *         If the project is protected and no password was given.
	 * @throws TopLogicException
	 *         If the file is no archive, holds an encrypted entry, or the password does not open its
	 *         project.
	 */
	public static ArchiveContents read(InputStream in, String password, boolean productData)
			throws IOException, XMLStreamException {
		ZipInputStream zip = new ZipInputStream(in);
		FilterInputStream entryIn = new FilterInputStream(zip) {
			@Override
			public void close() {
				// The entry is left by the next one.
			}
		};
		int entries = 0;
		int programs = 0;
		int schema = 0;
		Map<String, Map<String, byte[]>> projectArchives = new LinkedHashMap<>();
		String last = null;
		ZipEntry entry;
		while ((entry = Importer.nextEntry(zip, last)) != null) {
			String name = entry.getName();
			last = name;
			String[] path = name.split("/");
			if (entry.isDirectory() || Importer.isBaggage(path)) {
				continue;
			}
			if (name.endsWith(".xml")) {
				entries++;
				if (Importer.isProgram(path)) {
					programs++;
				}
				if (!productData && projectArchives.isEmpty()) {
					schema = Math.max(schema, schemaVersion(entryIn));
				}
			} else if (!productData && Importer.isProjectArchive(name, path)) {
				Map<String, byte[]> archive = ProtectedProject.read(name, zip.readAllBytes(), schema, password);
				projectArchives.put(name, archive);
				for (String archiveEntry : archive.keySet()) {
					if (archiveEntry.endsWith(".xml")) {
						entries++;
					}
				}
			}
		}
		return new ArchiveContents(entries, programs, projectArchives);
	}

	/**
	 * The version of the project schema of the XML document in the given stream, read from the
	 * namespace of its root element; 0 if it is not known.
	 */
	private static int schemaVersion(InputStream in) throws XMLStreamException {
		XMLStreamReader reader = XMLStreamUtil.getDefaultInputFactory().createXMLStreamReader(in);
		try {
			while (reader.hasNext()) {
				if (reader.next() == XMLStreamConstants.START_ELEMENT) {
					return Importer.schemaVersion(reader.getNamespaceURI());
				}
			}
			return 0;
		} finally {
			reader.close();
		}
	}

}
