/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.xml.XMLStreamUtil;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.xio.importer.binding.ImportContext;

/**
 * The files a manufacturer ships with its product data (ETS: baggage), read from a project file or
 * product file and kept with the manufacturer in the catalog (haumacher/ehome #154).
 *
 * <p>
 * ETS writes the manufacturer's index <code>M-xxxx/Baggages.xml</code> (ID, name, target path and
 * time of each file) and the files below <code>M-xxxx/Baggages/&lt;target path&gt;/&lt;name&gt;</code>,
 * in any order with the other entries. The import collects both while it reads the file
 * ({@link #readIndex(InputStream)}, {@link #readFile(String, InputStream)}) and creates or updates
 * the files at its end ({@link #apply(ImportContext, JoiningModelBinding, BufferingI18NLog)}),
 * before the references to them are resolved: those of the application programs it creates and of the products
 * (<code>etsImporter.xml</code>), and those of the programs an earlier import left without them
 * ({@link #readProgramReferences(byte[])}).
 * </p>
 *
 * <p>
 * A file is reused by its ID, like the other shared data: a file of the same or an older time leaves
 * the known one as it is, a newer time replaces its content and the import reports it. A file is
 * kept as it is, whatever its kind; nothing is interpreted or run. A file above the size limit (see
 * {@link #DEFAULT_SIZE_LIMIT}) is refused alone with a report line: the catalog lists it without its
 * content, and a later import of a file holding it completes it.
 * </p>
 */
final class BaggageImport {

	/**
	 * The size limit of one file of the baggage by default: 16 MB.
	 *
	 * <p>
	 * The largest files in the product data surveyed (<code>doc/manufacturers.md</code>: 103 files of
	 * MDT, Theben's complete database) have about 2 MB (pictures of MDT); a driver or plug-in
	 * installer about 1 MB. The limit leaves room for eight times that and keeps an installer of a
	 * whole tool suite out of the database.
	 * </p>
	 */
	static final long DEFAULT_SIZE_LIMIT = 16L * 1024 * 1024;

	/**
	 * Qualified name of the type of a file of the baggage.
	 */
	static final String BAGGAGE_TYPE = "ehome.catalog:Baggage";

	private static final String MANUFACTURER_TYPE = "ehome.catalog:Manufacturer";

	private static final String PROGRAM_TYPE = "ehome.operational:Program";

	private static final String EXT_ID = "extId";

	private static final String BAGGAGE = "baggage";

	/** The beginning of a reference to a file of the baggage in a program file. */
	private static final byte[] BAGGAGE_MARKER = "<Baggage ".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

	private static final String CONTENT_TYPE_DEFAULT = "application/octet-stream";

	private final long _sizeLimit;

	/** The files listed in the indexes read, by the ID of their manufacturer. */
	private final Map<String, List<Listed>> _listed = new LinkedHashMap<>();

	/** The contents of the files read, by their paths below the folder of the baggage of their manufacturer. */
	private final Map<String, byte[]> _contents = new HashMap<>();

	/** The files read that were above the size limit, by their paths as in {@link #_contents}. */
	private final Set<String> _tooLarge = new LinkedHashSet<>();

	/** The IDs of the files the application programs of the file name, by the IDs of the programs. */
	private final Map<String, List<String>> _programReferences = new LinkedHashMap<>();

	/** The known files whose content was replaced by a newer version. */
	private final List<TLObject> _replaced = new ArrayList<>();

	/**
	 * Creates a {@link BaggageImport}.
	 *
	 * @param sizeLimit
	 *        The largest number of bytes of a file whose content is imported.
	 */
	BaggageImport(long sizeLimit) {
		_sizeLimit = sizeLimit;
	}

	/**
	 * A file of the index of a manufacturer's baggage.
	 */
	private record Listed(String id, String name, String path, String timeText, Instant time) {

		/**
		 * The path of the file below the folder of its manufacturer's baggage.
		 */
		String file(String manufacturer) {
			return manufacturer + "/" + (path.isEmpty() ? name : path + "/" + name);
		}
	}

	/**
	 * Whether the entry with the given path is the index of a manufacturer's baggage
	 * (<code>M-xxxx/Baggages.xml</code>).
	 */
	static boolean isIndex(String[] path) {
		return path.length == 2 && path[0].startsWith(Importer.MANUFACTURER_PREFIX)
			&& Importer.BAGGAGES_INDEX.equals(path[1]);
	}

	/**
	 * Whether the entry with the given path is a file of a manufacturer's baggage (below
	 * <code>M-xxxx/Baggages/</code>).
	 */
	static boolean isFile(String[] path) {
		return path.length > 2 && path[0].startsWith(Importer.MANUFACTURER_PREFIX) && Importer.BAGGAGES.equals(path[1]);
	}

	/**
	 * Reads the index of a manufacturer's baggage (<code>M-xxxx/Baggages.xml</code>).
	 */
	void readIndex(InputStream in) throws XMLStreamException {
		XMLStreamReader reader = XMLStreamUtil.getDefaultInputFactory().createXMLStreamReader(in);
		try {
			String manufacturer = null;
			Listed baggage = null;
			while (reader.hasNext()) {
				int event = reader.next();
				if (event != XMLStreamConstants.START_ELEMENT) {
					continue;
				}
				switch (reader.getLocalName()) {
					case "Manufacturer":
						manufacturer = reader.getAttributeValue(null, "RefId");
						break;
					case "Baggage":
						if (manufacturer == null) {
							break;
						}
						String path = nonNull(reader.getAttributeValue(null, "TargetPath")).replace('\\', '/');
						while (path.endsWith("/")) {
							path = path.substring(0, path.length() - 1);
						}
						baggage = new Listed(reader.getAttributeValue(null, "Id"),
							nonNull(reader.getAttributeValue(null, "Name")), path, null, null);
						_listed.computeIfAbsent(manufacturer, m -> new ArrayList<>()).add(baggage);
						break;
					case "FileInfo":
						if (baggage == null) {
							break;
						}
						String timeText = reader.getAttributeValue(null, "TimeInfo");
						List<Listed> listed = _listed.get(manufacturer);
						listed.set(listed.size() - 1,
							new Listed(baggage.id(), baggage.name(), baggage.path(), timeText, parseTime(timeText)));
						break;
					default:
						break;
				}
			}
		} finally {
			reader.close();
		}
	}

	/**
	 * Reads a file of a manufacturer's baggage, given its path below the folder of the baggage
	 * (<code>M-xxxx/&lt;target path&gt;/&lt;name&gt;</code>); a file above the size limit is not kept.
	 */
	void readFile(String file, InputStream in) throws IOException {
		byte[] content = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, _sizeLimit + 1));
		if (content.length > _sizeLimit) {
			in.transferTo(OutputStream.nullOutputStream());
			_tooLarge.add(file);
		} else {
			_contents.put(file, content);
		}
	}

	/**
	 * Notes the files of the baggage the application programs of the given program file name
	 * (<code>Static/Extension/Baggage</code>), for the programs an earlier import left without them.
	 */
	void readProgramReferences(byte[] content) throws IOException {
		if (!Importer.mentions(content, BAGGAGE_MARKER)) {
			return;
		}
		try {
			XMLStreamReader in = XMLStreamUtil.getDefaultInputFactory()
				.createXMLStreamReader(new ByteArrayInputStream(content));
			try {
				Deque<String> elements = new ArrayDeque<>();
				String program = null;
				while (in.hasNext()) {
					int event = in.next();
					if (event == XMLStreamConstants.START_ELEMENT) {
						String name = in.getLocalName();
						if ("ApplicationProgram".equals(name)) {
							program = in.getAttributeValue(null, "Id");
						} else if ("Baggage".equals(name) && program != null && isProgramExtension(elements)) {
							_programReferences.computeIfAbsent(program, p -> new ArrayList<>())
								.add(in.getAttributeValue(null, "RefId"));
						}
						elements.push(name);
					} else if (event == XMLStreamConstants.END_ELEMENT) {
						elements.pop();
					}
				}
			} finally {
				in.close();
			}
		} catch (XMLStreamException ex) {
			throw new IOException("Cannot read the program file: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Whether the given open elements (the innermost first) are the extension of an application
	 * program (<code>ApplicationProgram/Static/Extension</code>).
	 */
	private static boolean isProgramExtension(Deque<String> elements) {
		var it = elements.iterator();
		return it.hasNext() && "Extension".equals(it.next()) && it.hasNext() && "Static".equals(it.next())
			&& it.hasNext() && "ApplicationProgram".equals(it.next());
	}

	/**
	 * Creates the files read that are not known, updates the known ones of a newer time, and gives
	 * the known application programs without files those they name; reports what it did to the given
	 * log.
	 *
	 * <p>
	 * Called by the import at its end, before the references of the import are resolved: a file it
	 * creates is known to the import under its ID.
	 * </p>
	 */
	void apply(ImportContext context, JoiningModelBinding binding, BufferingI18NLog messages) {
		for (Entry<String, List<Listed>> entry : _listed.entrySet()) {
			String manufacturerId = entry.getKey();
			TLObject manufacturer = manufacturer(binding, manufacturerId);
			if (manufacturer == null) {
				messages.log(Level.WARN, I18NConstants.WARN_BAGGAGE_NO_MANUFACTURER__MANUFACTURER.fill(manufacturerId));
				continue;
			}
			Map<String, TLObject> known = new HashMap<>();
			for (TLObject baggage : list(manufacturer, BAGGAGE)) {
				known.put((String) baggage.tValueByName(EXT_ID), baggage);
			}
			for (Listed listed : entry.getValue()) {
				apply(context, binding, messages, manufacturer, known, manufacturerId, listed);
			}
		}
		for (String file : _contents.keySet()) {
			messages.log(Level.WARN, I18NConstants.WARN_BAGGAGE_NOT_LISTED__ENTRY.fill(entryName(file)));
		}
		for (String file : _tooLarge) {
			messages.log(Level.WARN, I18NConstants.WARN_BAGGAGE_NOT_LISTED__ENTRY.fill(entryName(file)));
		}
		completePrograms(binding, messages);
	}

	private void apply(ImportContext context, JoiningModelBinding binding, BufferingI18NLog messages,
			TLObject manufacturer,
			Map<String, TLObject> known, String manufacturerId, Listed listed) {
		String file = listed.file(manufacturerId);
		byte[] content = takeContent(file);
		boolean tooLarge = _tooLarge.remove(file) || removeIgnoringCase(_tooLarge, file);
		if (listed.timeText() != null && listed.time() == null) {
			messages.log(Level.WARN, I18NConstants.WARN_BAGGAGE_INVALID_TIME__FILE_TIME.fill(file, listed.timeText()));
		}

		TLObject baggage = known.get(listed.id());
		if (baggage == null) {
			baggage = (TLObject) binding.createObject(null, null, BAGGAGE_TYPE, null);
			// Known to the import under its ID: resolves the references read before.
			context.assignId(baggage, listed.id());
			baggage.tUpdateByName(EXT_ID, listed.id());
			describe(baggage, listed);
			setContent(baggage, listed, content);
			manufacturer.tAdd(manufacturer.tType().getPartOrFail(BAGGAGE), baggage);
			known.put(listed.id(), baggage);
			reportMissing(messages, file, content, tooLarge);
			return;
		}

		Date before = (Date) baggage.tValueByName("time");
		if (isNewer(listed.time(), before)) {
			if (content == null) {
				// The newer version is not there to replace the known one, which stays as it is.
				reportMissing(messages, file, null, tooLarge);
				return;
			}
			describe(baggage, listed);
			setContent(baggage, listed, content);
			_replaced.add(baggage);
			messages.log(Level.INFO, I18NConstants.INFO_BAGGAGE_REPLACED__FILE_TIME_OLD.fill(file,
				format(listed.time()), before == null ? "-" : format(before.toInstant())));
		} else if (baggage.tValueByName("content") == null) {
			if (content != null) {
				setContent(baggage, listed, content);
				messages.log(Level.INFO, I18NConstants.INFO_BAGGAGE_COMPLETED__FILE.fill(file));
			} else {
				// Still without content: said again, since each import is reported on its own.
				reportMissing(messages, file, null, tooLarge);
			}
		}
	}

	private void reportMissing(BufferingI18NLog messages, String file, byte[] content, boolean tooLarge) {
		if (tooLarge) {
			messages.log(Level.WARN,
				I18NConstants.WARN_BAGGAGE_TOO_LARGE__FILE_LIMIT.fill(file, sizeText(_sizeLimit)));
		} else if (content == null) {
			messages.log(Level.WARN, I18NConstants.WARN_BAGGAGE_MISSING__FILE.fill(file));
		}
	}

	/**
	 * Whether a file of the given time is newer than the known one of the given time: a file without
	 * a time never is; a known file without a time is replaced by one with a time.
	 */
	private static boolean isNewer(Instant time, Date known) {
		if (time == null) {
			return false;
		}
		if (known == null) {
			return true;
		}
		// The model keeps milliseconds, ETS writes up to seven decimal places of the seconds.
		return time.truncatedTo(ChronoUnit.MILLIS).isAfter(known.toInstant());
	}

	private static void describe(TLObject baggage, Listed listed) {
		baggage.tUpdateByName("name", listed.name());
		baggage.tUpdateByName("path", listed.path().isEmpty() ? null : listed.path());
		baggage.tUpdateByName("time", listed.time() == null ? null : Date.from(listed.time()));
	}

	private static void setContent(TLObject baggage, Listed listed, byte[] content) {
		if (content == null) {
			baggage.tUpdateByName("content", null);
			baggage.tUpdateByName("size", null);
			return;
		}
		BinaryData data = BinaryDataFactory.createBinaryData(content, contentType(listed.name()), listed.name());
		baggage.tUpdateByName("content", data);
		baggage.tUpdateByName("size", Long.valueOf(content.length));
	}

	/**
	 * The media type of a file of the given name, as far as its extension tells it.
	 */
	static String contentType(String name) {
		String result = URLConnection.guessContentTypeFromName(name.toLowerCase(Locale.ROOT));
		return result == null ? CONTENT_TYPE_DEFAULT : result;
	}

	/**
	 * Takes the content of the given file read before, matching its path exactly or else ignoring
	 * case (ETS runs on Windows, where a name in the index and on disk may differ in case).
	 */
	private byte[] takeContent(String file) {
		byte[] result = _contents.remove(file);
		if (result != null) {
			return result;
		}
		for (String other : _contents.keySet()) {
			if (other.equalsIgnoreCase(file)) {
				return _contents.remove(other);
			}
		}
		return null;
	}

	private static boolean removeIgnoringCase(Set<String> files, String file) {
		return files.removeIf(other -> other.equalsIgnoreCase(file));
	}

	/**
	 * The known files whose content
	 * {@link #apply(ImportContext, JoiningModelBinding, BufferingI18NLog)} replaced by a newer version.
	 */
	List<TLObject> getReplaced() {
		return _replaced;
	}

	/**
	 * Gives the known application programs without files of the baggage those they name, see
	 * {@link #readProgramReferences(byte[])}.
	 */
	private void completePrograms(JoiningModelBinding binding, BufferingI18NLog messages) {
		if (_programReferences.isEmpty()) {
			return;
		}
		Map<String, TLObject> baggageById = new HashMap<>();
		for (TLObject baggage : MetaElementUtil.getAllDirectInstancesOf(type(BAGGAGE_TYPE), TLObject.class)) {
			baggageById.put((String) baggage.tValueByName(EXT_ID), baggage);
		}
		for (Object created : binding.getCreated()) {
			if (created instanceof TLObject obj && obj.tValid() && binding.isInstanceOf(obj, BAGGAGE_TYPE)) {
				baggageById.put((String) obj.tValueByName(EXT_ID), obj);
			}
		}
		Map<String, TLObject> programs = new HashMap<>();
		for (TLObject program : MetaElementUtil.getAllDirectInstancesOf(type(PROGRAM_TYPE), TLObject.class)) {
			programs.put((String) program.tValueByName(EXT_ID), program);
		}
		for (Entry<String, List<String>> entry : _programReferences.entrySet()) {
			TLObject program = programs.get(entry.getKey());
			// A program the import created reads its references itself (etsImporter.xml).
			if (program == null || binding.isCreated(program) || !list(program, BAGGAGE).isEmpty()) {
				continue;
			}
			List<TLObject> named = new ArrayList<>();
			for (String id : entry.getValue()) {
				TLObject baggage = baggageById.get(id);
				if (baggage == null) {
					messages.log(Level.WARN, I18NConstants.WARN_PROGRAM_BAGGAGE_UNKNOWN__PROGRAM_FILE.fill(entry.getKey(), id));
				} else if (!named.contains(baggage)) {
					named.add(baggage);
				}
			}
			if (!named.isEmpty()) {
				program.tUpdateByName(BAGGAGE, named);
				messages.log(Level.INFO,
					I18NConstants.INFO_PROGRAM_BAGGAGE_COMPLETED__PROGRAM_COUNT.fill(entry.getKey(), named.size()));
			}
		}
	}

	/**
	 * The manufacturer with the given ID, created by the import or existing; <code>null</code> if
	 * there is none.
	 */
	private static TLObject manufacturer(JoiningModelBinding binding, String id) {
		for (Object created : binding.getCreated()) {
			if (created instanceof TLObject obj && obj.tValid() && binding.isInstanceOf(obj, MANUFACTURER_TYPE)
				&& id.equals(obj.tValueByName(EXT_ID))) {
				return obj;
			}
		}
		for (TLObject obj : MetaElementUtil.getAllDirectInstancesOf(type(MANUFACTURER_TYPE), TLObject.class)) {
			if (id.equals(obj.tValueByName(EXT_ID))) {
				return obj;
			}
		}
		return null;
	}

	private static TLClass type(String name) {
		return (TLClass) TLModelUtil.findType(name);
	}

	@SuppressWarnings("unchecked")
	private static List<TLObject> list(TLObject obj, String reference) {
		Object value = obj.tValueByName(reference);
		return value == null ? List.of() : new ArrayList<>((java.util.Collection<TLObject>) value);
	}

	/**
	 * The name of the entry of the archive of the given file of the baggage.
	 */
	private static String entryName(String file) {
		int slash = file.indexOf('/');
		return file.substring(0, slash) + "/" + Importer.BAGGAGES + file.substring(slash);
	}

	private static Instant parseTime(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(text.trim());
		} catch (DateTimeParseException ex) {
			try {
				// Without a zone: ETS writes UTC.
				return Instant.parse(text.trim() + "Z");
			} catch (DateTimeParseException ex2) {
				return null;
			}
		}
	}

	/**
	 * The given number of bytes as a text: in MB where it is a whole number of them.
	 */
	private static String sizeText(long bytes) {
		long mb = 1024 * 1024;
		return bytes >= mb && bytes % mb == 0 ? (bytes / mb) + " MB" : bytes + " bytes";
	}

	private static String format(Instant time) {
		return time.truncatedTo(ChronoUnit.SECONDS).toString();
	}

	private static String nonNull(String value) {
		return value == null ? "" : value;
	}

}
