/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ProgramResources;

/**
 * Reads the icons and help texts of the application programs of a program file (haumacher/ehome
 * #156, #157) and unpacks the archives of the manufacturer's baggage they come from.
 *
 * <p>
 * A program names the archive of its icons (<code>ApplicationProgram@IconFile</code>) and of its
 * help texts in its default language (<code>@ContextHelpFile</code>), and in further languages by
 * translations of that attribute (<code>Languages/Language/TranslationUnit/TranslationElement</code>
 * of the program, <code>Translation@AttributeName="ContextHelpFile"</code>). Its elements name
 * their icon (<code>Icon</code> of an <code>Enumeration</code>, <code>Channel</code>,
 * <code>ParameterBlock</code>, and of a <code>ParameterRefRef</code> for the parameter at that
 * place) and their help topic (<code>HelpContext</code> of a <code>ParameterBlock</code> and of a
 * <code>ParameterRefRef</code>).
 * </p>
 *
 * <p>
 * Read in Java, not by the mapping of <code>etsImporter.xml</code>, because the translations name
 * a file per language rather than a text, and because the same reading completes a known program
 * an earlier import read without them (haumacher/ehome #156 came after programs with icons were
 * imported): the import scans each program file ({@link #read(byte[])}) and, once the references
 * of the import are resolved, applies what it found to the programs, created or known
 * ({@link #apply(JoiningModelBinding, BufferingI18NLog)}). A program is marked as read
 * (<code>resourcesRead</code>); a known program so marked is left as it is, so that a second
 * import adds nothing.
 * </p>
 *
 * <p>
 * The archives a program names are unpacked into their files
 * (<code>ehome.catalog:Baggage#entries</code>, {@link #unpack(TLObject, BufferingI18NLog)}); a
 * newer version of such a file unpacks it again (see {@link BaggageImport}). The import warns once
 * per program about the icons its archive does not hold (naming the first and their number) and
 * about the help topics without a text in its default language (their number).
 * </p>
 */
final class ProgramResourcesImport {

	/** The type of the help texts of a program in one language. */
	private static final String HELP_FILE_TYPE = "ehome.operational:HelpFile";

	/** The type of a file inside an archive of the baggage. */
	private static final String ENTRY_TYPE = "ehome.catalog:ArchiveEntry";

	private static final String PROGRAM_TYPE = "ehome.operational:Program";

	private static final String EXT_ID = "extId";

	private static final String RESOURCES_READ = "resourcesRead";

	private static final String ICON_FILE = "iconFile";

	private static final String HELP_FILES = "helpFiles";

	private static final String ENTRIES = "entries";

	private static final String UNPACKED = "unpacked";

	private static final String CONTENT = "content";

	private static final String CONTEXT_HELP_FILE = "ContextHelpFile";

	private static final String ICON = "Icon";

	private static final String HELP_CONTEXT = "HelpContext";

	/** The beginnings of the attributes the reading looks for; a file without any is not scanned. */
	private static final byte[][] MARKERS = {
		" Icon=\"".getBytes(StandardCharsets.US_ASCII),
		"IconFile=\"".getBytes(StandardCharsets.US_ASCII),
		"HelpContext=\"".getBytes(StandardCharsets.US_ASCII),
		"ContextHelpFile=\"".getBytes(StandardCharsets.US_ASCII),
	};

	/**
	 * The largest number of bytes of one file of an archive that is unpacked: the size limit of a
	 * file of the baggage itself ({@link BaggageImport#DEFAULT_SIZE_LIMIT}). The icons and help
	 * texts surveyed have a few hundred bytes each.
	 */
	static final long ENTRY_SIZE_LIMIT = BaggageImport.DEFAULT_SIZE_LIMIT;

	/** The largest number of files of an archive that are unpacked. */
	static final int ENTRY_COUNT_LIMIT = 10000;

	/** What the program files read name, by the IDs of the programs, in their order. */
	private final Map<String, Scan> _scans = new LinkedHashMap<>();

	/**
	 * What one application program names: its archives and the icons and help topics of its
	 * elements.
	 */
	static final class Scan {

		final String _program;

		String _iconFile;

		String _helpFile;

		Locale _defaultLanguage;

		/** The help files of further languages (the translations of ContextHelpFile). */
		final Map<Locale, String> _helpTranslations = new LinkedHashMap<>();

		/** The icon of an element, by the ID of the element (of a ParameterRef for a ParameterRefRef). */
		final Map<String, String> _icons = new LinkedHashMap<>();

		/** The help topic of an element, by the ID of the element (of a ParameterRef for a ParameterRefRef). */
		final Map<String, String> _topics = new LinkedHashMap<>();

		Scan(String program) {
			_program = program;
		}

		boolean isEmpty() {
			return _iconFile == null && _helpFile == null && _helpTranslations.isEmpty() && _icons.isEmpty()
				&& _topics.isEmpty();
		}
	}

	/**
	 * Whether the given program file names icons or help texts at all.
	 */
	static boolean mentionsResources(byte[] content) {
		for (byte[] marker : MARKERS) {
			if (Importer.mentions(content, marker)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Notes the icons and help texts the application programs of the given program file name.
	 */
	void read(byte[] content) throws IOException {
		if (!mentionsResources(content)) {
			return;
		}
		try {
			XMLStreamReader in = XMLStreamUtil.getDefaultInputFactory()
				.createXMLStreamReader(new ByteArrayInputStream(content));
			try {
				read(in);
			} finally {
				in.close();
			}
		} catch (XMLStreamException ex) {
			throw new IOException("Cannot read the program file: " + ex.getMessage(), ex);
		}
	}

	private void read(XMLStreamReader in) throws XMLStreamException {
		Scan program = null;
		Locale language = null;
		Scan translated = null;
		while (in.hasNext()) {
			int event = in.next();
			if (event == XMLStreamConstants.END_ELEMENT && "ApplicationProgram".equals(in.getLocalName())) {
				program = null;
				continue;
			}
			if (event != XMLStreamConstants.START_ELEMENT) {
				continue;
			}
			String name = in.getLocalName();
			switch (name) {
				case "ApplicationProgram":
					program = _scans.computeIfAbsent(in.getAttributeValue(null, "Id"), Scan::new);
					program._iconFile = nonBlank(in.getAttributeValue(null, "IconFile"));
					program._helpFile = nonBlank(in.getAttributeValue(null, CONTEXT_HELP_FILE));
					String defaultLanguage = nonBlank(in.getAttributeValue(null, "DefaultLanguage"));
					program._defaultLanguage = defaultLanguage == null ? null : I18NTexts.locale(defaultLanguage);
					break;
				case "Language":
					String identifier = nonBlank(in.getAttributeValue(null, "Identifier"));
					language = identifier == null ? null : I18NTexts.locale(identifier);
					break;
				case "TranslationElement":
					translated = _scans.get(in.getAttributeValue(null, "RefId"));
					break;
				case "Translation":
					String file = nonBlank(in.getAttributeValue(null, "Text"));
					if (translated != null && language != null && file != null
						&& CONTEXT_HELP_FILE.equals(in.getAttributeValue(null, "AttributeName"))) {
						translated._helpTranslations.put(language, file);
					}
					break;
				case "ParameterRefRef":
					if (program != null) {
						// The first place of a parameter reference: the reference is placed once (PlaceReference).
						String ref = nonBlank(in.getAttributeValue(null, "RefId"));
						putFirst(program._icons, ref, in.getAttributeValue(null, ICON));
						putFirst(program._topics, ref, in.getAttributeValue(null, HELP_CONTEXT));
					}
					break;
				default:
					if (program != null) {
						String id = nonBlank(in.getAttributeValue(null, "Id"));
						putFirst(program._icons, id, in.getAttributeValue(null, ICON));
						putFirst(program._topics, id, in.getAttributeValue(null, HELP_CONTEXT));
					}
					break;
			}
		}
		_scans.values().removeIf(Scan::isEmpty);
	}

	private static void putFirst(Map<String, String> map, String key, String value) {
		String text = nonBlank(value);
		if (key != null && text != null) {
			map.putIfAbsent(key, text);
		}
	}

	/**
	 * Applies what the program files read name to their programs, created by the import or known
	 * (and not read before), and forgets it; called once the references of the import are
	 * resolved.
	 */
	void apply(JoiningModelBinding binding, BufferingI18NLog messages) {
		if (_scans.isEmpty()) {
			return;
		}
		Map<String, TLObject> created = new HashMap<>();
		for (Object obj : binding.getCreated()) {
			if (obj instanceof TLObject program && program.tValid() && binding.isInstanceOf(program, PROGRAM_TYPE)) {
				created.put((String) program.tValueByName(EXT_ID), program);
			}
		}
		for (Scan scan : _scans.values()) {
			TLObject program = created.get(scan._program);
			boolean known = program == null;
			if (known) {
				program = existingProgram(scan._program);
			}
			if (program == null || known && Boolean.TRUE.equals(program.tValueByName(RESOURCES_READ))) {
				continue;
			}
			apply(program, scan, messages);
			if (known) {
				messages.log(Level.INFO, I18NConstants.INFO_PROGRAM_RESOURCES_COMPLETED__PROGRAM.fill(scan._program));
			}
		}
		_scans.clear();
	}

	private static void apply(TLObject program, Scan scan, BufferingI18NLog messages) {
		Map<String, TLObject> parts = parts(program);
		for (Map.Entry<String, String> icon : scan._icons.entrySet()) {
			setName(parts.get(icon.getKey()), ProgramResources.ICON, icon.getValue());
		}
		for (Map.Entry<String, String> topic : scan._topics.entrySet()) {
			setName(parts.get(topic.getKey()), ProgramResources.HELP_TOPIC, topic.getValue());
		}

		// The archives.
		Map<String, TLObject> baggage = baggage(program);
		TLObject iconFile = archive(program, scan._program, scan._iconFile, baggage, messages);
		program.tUpdateByName(ICON_FILE, iconFile);
		for (Object old : list(program, HELP_FILES)) {
			((TLObject) old).tDelete();
		}
		List<TLObject> helpFiles = new ArrayList<>();
		if (scan._helpFile != null) {
			Locale language = scan._defaultLanguage == null ? Locale.ENGLISH : scan._defaultLanguage;
			addHelpFile(helpFiles, language, true, archive(program, scan._program, scan._helpFile, baggage, messages));
		}
		for (Map.Entry<Locale, String> translation : scan._helpTranslations.entrySet()) {
			Locale language = translation.getKey();
			boolean fallback = scan._helpFile != null && language.equals(scan._defaultLanguage);
			if (fallback || !ProgramLanguages.isApplicationLanguage(language)
				|| helpFiles.stream().anyMatch(f -> language.getLanguage().equals(f.tValueByName("language")))) {
				// The default language is the attribute's own; languages the application does not offer are not modeled.
				continue;
			}
			addHelpFile(helpFiles, language, false,
				archive(program, scan._program, translation.getValue(), baggage, messages));
		}
		program.tUpdateByName(HELP_FILES, helpFiles);

		// What the program names but its archives lack.
		Set<String> icons = new LinkedHashSet<>(scan._icons.values());
		List<String> missing = new ArrayList<>();
		for (String icon : icons) {
			if (ProgramResources.entry(iconFile, icon) == null) {
				missing.add(icon);
			}
		}
		if (!missing.isEmpty()) {
			messages.log(Level.WARN, I18NConstants.WARN_PROGRAM_ICONS_MISSING__PROGRAM_COUNT_FIRST
				.fill(scan._program, Integer.valueOf(missing.size()), missing.get(0)));
		}
		TLObject fallback = helpFiles.stream().filter(f -> Boolean.TRUE.equals(f.tValueByName("fallback")))
			.findFirst().orElse(null);
		int withoutText = 0;
		for (String topic : new LinkedHashSet<>(scan._topics.values())) {
			if (fallback == null || ProgramResources.topicText(fallback, topic) == null) {
				withoutText++;
			}
		}
		if (withoutText > 0) {
			messages.log(Level.WARN,
				I18NConstants.WARN_PROGRAM_HELP_MISSING__PROGRAM_COUNT.fill(scan._program, Integer.valueOf(withoutText)));
		}
		program.tUpdateByName(RESOURCES_READ, Boolean.TRUE);
	}

	private static void setName(TLObject part, String attribute, String name) {
		if (part != null && part.tType().getPart(attribute) != null && !name.equals(part.tValueByName(attribute))) {
			part.tUpdateByName(attribute, name);
		}
	}

	private static void addHelpFile(List<TLObject> helpFiles, Locale language, boolean fallback, TLObject file) {
		TLObject helpFile = create(HELP_FILE_TYPE);
		helpFile.tUpdateByName("language", language.getLanguage());
		helpFile.tUpdateByName("fallback", Boolean.valueOf(fallback));
		helpFile.tUpdateByName("file", file);
		helpFiles.add(helpFile);
	}

	/**
	 * The file of the baggage with the given ID the given program names as an archive, unpacked;
	 * <code>null</code> (and reported) if there is none.
	 */
	private static TLObject archive(TLObject program, String programId, String id, Map<String, TLObject> baggage,
			BufferingI18NLog messages) {
		if (id == null) {
			return null;
		}
		TLObject result = baggage.get(id);
		if (result == null) {
			messages.log(Level.WARN, I18NConstants.WARN_PROGRAM_BAGGAGE_UNKNOWN__PROGRAM_FILE.fill(programId, id));
			return null;
		}
		if (!Boolean.TRUE.equals(result.tValueByName(UNPACKED))) {
			unpack(result, messages);
		}
		return result;
	}

	/**
	 * Unpacks the given file of the baggage into its entries, replacing those it has, and marks it
	 * as unpacked: its entries follow every new content (see {@link BaggageImport}). A file without
	 * content has no entries until a later import completes it; a file that is no ZIP archive has
	 * none (reported).
	 */
	static void unpack(TLObject baggage, BufferingI18NLog messages) {
		for (Object old : list(baggage, ENTRIES)) {
			((TLObject) old).tDelete();
		}
		baggage.tUpdateByName(UNPACKED, Boolean.TRUE);
		BinaryData content = (BinaryData) baggage.tValueByName(CONTENT);
		if (content == null) {
			return;
		}
		String file = (String) baggage.tValueByName("name");
		List<Unpacked> files;
		try {
			try {
				files = read(content, StandardCharsets.UTF_8, file, messages);
			} catch (IllegalArgumentException ex) {
				// A name that is no UTF-8: written without the UTF-8 flag of the ZIP format, so in the
				// format's original encoding (IBM 437), as Theben's help archives name a topic with an umlaut.
				files = read(content, Charset.forName("IBM437"), file, messages);
			}
		} catch (IOException | IllegalArgumentException ex) {
			messages.log(Level.WARN, I18NConstants.WARN_ARCHIVE_UNREADABLE__FILE_REASON.fill(file, ex.getMessage()));
			files = List.of();
		}
		List<TLObject> entries = new ArrayList<>();
		for (Unpacked unpacked : files) {
			String entryName = unpacked.name().substring(unpacked.name().lastIndexOf('/') + 1);
			TLObject result = create(ENTRY_TYPE);
			result.tUpdateByName("name", unpacked.name());
			result.tUpdateByName(CONTENT,
				BinaryDataFactory.createBinaryData(unpacked.content(), BaggageImport.contentType(entryName), entryName));
			entries.add(result);
		}
		if (entries.isEmpty() && content.getSize() > 0 && !isZip(content)) {
			messages.log(Level.WARN, I18NConstants.WARN_ARCHIVE_UNREADABLE__FILE_REASON.fill(file, "no ZIP archive"));
		}
		baggage.tUpdateByName(ENTRIES, entries);
	}

	/** A file read from an archive. */
	private record Unpacked(String name, byte[] content) {
		// Pure data.
	}

	/**
	 * The files of the given archive, their names read in the given encoding.
	 *
	 * @throws IllegalArgumentException
	 *         If a name is not in the given encoding.
	 */
	private static List<Unpacked> read(BinaryData content, Charset names, String file, BufferingI18NLog messages)
			throws IOException {
		List<Unpacked> result = new ArrayList<>();
		try (ZipInputStream zip = new ZipInputStream(content.getStream(), names)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.isDirectory()) {
					continue;
				}
				String name = entry.getName().replace('\\', '/');
				if (result.size() >= ENTRY_COUNT_LIMIT) {
					messages.log(Level.WARN, I18NConstants.WARN_ARCHIVE_TOO_MANY_FILES__FILE_LIMIT.fill(file,
						Integer.valueOf(ENTRY_COUNT_LIMIT)));
					break;
				}
				byte[] bytes = zip.readNBytes((int) ENTRY_SIZE_LIMIT + 1);
				if (bytes.length > ENTRY_SIZE_LIMIT) {
					messages.log(Level.WARN, I18NConstants.WARN_ARCHIVE_FILE_TOO_LARGE__FILE_ENTRY_LIMIT.fill(file, name,
						(ENTRY_SIZE_LIMIT / (1024 * 1024)) + " MB"));
					continue;
				}
				result.add(new Unpacked(name, bytes));
			}
		}
		return result;
	}

	private static boolean isZip(BinaryData content) {
		try (var in = content.getStream()) {
			byte[] head = in.readNBytes(2);
			return head.length == 2 && head[0] == 'P' && head[1] == 'K';
		} catch (IOException ex) {
			return false;
		}
	}

	/**
	 * The parts of the given program (everything it holds by composition) with an ID, by their
	 * IDs; a parameter reference is a part of the structure placing it or of the unplaced ones.
	 */
	private static Map<String, TLObject> parts(TLObject program) {
		Map<String, TLObject> result = new HashMap<>();
		Map<TLObject, Boolean> seen = new IdentityHashMap<>();
		List<TLObject> pending = new ArrayList<>();
		pending.add(program);
		while (!pending.isEmpty()) {
			TLObject obj = pending.remove(pending.size() - 1);
			if (seen.put(obj, Boolean.TRUE) != null) {
				continue;
			}
			if (obj.tType().getPart(EXT_ID) != null && obj.tValueByName(EXT_ID) instanceof String id) {
				result.putIfAbsent(id, obj);
			}
			for (TLStructuredTypePart part : ((TLStructuredType) obj.tType()).getAllParts()) {
				if (part instanceof TLReference reference && reference.isComposite()) {
					Object value = obj.tValue(part);
					if (value instanceof TLObject child) {
						pending.add(child);
					} else if (value instanceof Collection<?> children) {
						for (Object child : children) {
							if (child instanceof TLObject childObject) {
								pending.add(childObject);
							}
						}
					}
				}
			}
		}
		return result;
	}

	/**
	 * The files of the baggage of the manufacturer of the given program, by their IDs.
	 */
	private static Map<String, TLObject> baggage(TLObject program) {
		Map<String, TLObject> result = new HashMap<>();
		Object manufacturer = program.tValueByName("manufacturer");
		if (manufacturer instanceof TLObject owner) {
			for (Object file : list(owner, "baggage")) {
				result.put((String) ((TLObject) file).tValueByName(EXT_ID), (TLObject) file);
			}
		}
		if (result.isEmpty()) {
			// A program whose manufacturer is not resolved (yet): the files of every manufacturer.
			TLClass type = (TLClass) TLModelUtil.findType(BaggageImport.BAGGAGE_TYPE);
			for (TLObject file : MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class)) {
				result.put((String) file.tValueByName(EXT_ID), file);
			}
		}
		return result;
	}

	private static TLObject existingProgram(String id) {
		TLClass type = (TLClass) TLModelUtil.findType(PROGRAM_TYPE);
		for (TLObject program : MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class)) {
			if (id.equals(program.tValueByName(EXT_ID))) {
				return program;
			}
		}
		return null;
	}

	private static TLObject create(String typeName) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
	}

	private static List<?> list(TLObject obj, String reference) {
		Object value = obj.tValueByName(reference);
		return value == null ? List.of() : new ArrayList<>((Collection<?>) value);
	}

	private static String nonBlank(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

}
