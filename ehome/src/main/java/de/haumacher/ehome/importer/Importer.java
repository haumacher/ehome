/*
 * Copyright (c) 2018 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.util.StreamReaderDelegate;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.top_logic.basic.Log;
import com.top_logic.basic.Logger;
import com.top_logic.basic.SyserrProtocol;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.config.ConfigurationReader;
import com.top_logic.basic.config.DefaultInstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.io.Content;
import com.top_logic.basic.io.binary.ClassRelativeBinaryContent;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.basic.xml.XMLStreamUtil;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.xio.importer.binding.DefaultImportContext;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.binding.ModelBinding;
import com.top_logic.xio.importer.handlers.DispatchingImporter;
import com.top_logic.xio.importer.handlers.Handler;

import de.haumacher.ehome.calculation.DeviceCalculations;
import de.haumacher.ehome.calculation.DeviceValidations;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.operational.CalculationParameter;
import de.haumacher.ehome.model.operational.ParameterCalculation;
import de.haumacher.ehome.model.operational.ParameterValidation;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DeviceConfiguration;

/**
 * Imports an ETS project file (<code>.knxproj</code>) or a product file of a manufacturer
 * (<code>.knxprod</code>) into the model, as described in
 * <code>etsImporter.xml</code>, and collects the messages of the import (see
 * {@link #getMessages()}).
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class Importer {

	/**
	 * Prefix of the system ID of an entry of the project archive, see {@link #getMessages()}.
	 */
	public static final String ENTRY_SCHEME = "knxproj:";

	/**
	 * Prefix of the folder of a manufacturer's data in an archive, and of the manufacturer's ID.
	 */
	static final String MANUFACTURER_PREFIX = "M-";

	/**
	 * Prefix of the folder (and the signature) of a project in an archive.
	 */
	static final String PROJECT_PREFIX = "P-";

	/**
	 * Infix of the name of the entry of an application program in the folder of its manufacturer
	 * (<code>M-xxxx/M-xxxx_A-….xml</code>).
	 */
	private static final String PROGRAM_INFIX = "_A-";

	/**
	 * Qualified name of the type of application programs.
	 */
	private static final String PROGRAM_TYPE = "ehome.operational:Program";

	private static final String SETTING_TYPE = "ehome.physical:ParameterSetting";

	private static final String CONNECTOR_TYPE = "ehome.physical:Connector";

	private static final String DEVICE_TYPE = "ehome.physical:Device";

	private static final String CALCULATION_TYPE = "ehome.operational:ParameterCalculation";

	private static final String VALIDATION_TYPE = "ehome.operational:ParameterValidation";

	/**
	 * Name of the folder holding the baggage of a manufacturer (pictures, help and plug-in files of
	 * ETS), and of its index <code>Baggages.xml</code> beside it.
	 */
	static final String BAGGAGES = "Baggages";

	/**
	 * Name of the index of a manufacturer's baggage, see {@link #BAGGAGES}.
	 */
	static final String BAGGAGES_INDEX = BAGGAGES + ".xml";

	/**
	 * Suffix of the archive holding the project part of an ETS 5/6 project file
	 * (<code>P-xxxx.zip</code>).
	 */
	private static final String ARCHIVE_SUFFIX = ".zip";

	/**
	 * Beginning of the namespace of the KNX project schema; the schema version follows.
	 */
	private static final String SCHEMA_NAMESPACE = "http://knx.org/xml/project/";

	private JoiningModelBinding _binding;

	private Set<String> _manufacturers = new LinkedHashSet<>();

	private BufferingI18NLog _messages = new BufferingI18NLog();

	/**
	 * The size limit of a file of a manufacturer's baggage, see {@link #setBaggageSizeLimit(long)}.
	 */
	private long _baggageSizeLimit = BaggageImport.DEFAULT_SIZE_LIMIT;

	/**
	 * The number of application programs the last import read (created or skipped as known).
	 */
	private int _programFiles;

	/**
	 * The files of manufacturers the last import replaced by a newer version.
	 */
	private List<TLObject> _replacedBaggage = List.of();

	/** The beginning of an <code>Assign</code> element in a program file. */
	private static final byte[] ASSIGN_MARKER = "<Assign ".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

	private static final String ASSIGNS_READ = "assignsRead";

	/**
	 * The languages of the application programs the last import read.
	 */
	private ProgramLanguages _programLanguages = new ProgramLanguages();

	/**
	 * The project password for the current import, <code>null</code> if none was given.
	 */
	private String _password;

	/**
	 * The highest project schema version of the files read so far by the current import.
	 */
	private int _schema;

	/**
	 * The progress of the current import.
	 */
	private EntryProgress _progress = new EntryProgress(ImportProgress.NONE, 0, 0, Set.of());

	/**
	 * Creates a {@link Importer}.
	 *
	 */
	public Importer(ModelBinding binding) {
		_binding = new JoiningModelBinding(binding);
	}

	/**
	 * Imports the XML entries of the given archive with the given handler.
	 *
	 * @param productData
	 *        Whether the archive is a product file (<code>.knxprod</code>), which may hold no project
	 *        and must hold the data of a manufacturer.
	 * @param contents
	 *        The contents of the archive read beforehand, whose opened project archives are used;
	 *        <code>null</code> to open them here.
	 */
	private void importETS(ZipInputStream zip, ImportContext context, Handler handler, boolean productData,
			ArchiveContents contents) throws XMLStreamException, IOException {
		FilterInputStream entryIn = new FilterInputStream(zip) {
			@Override
			public void close() throws IOException {
				zip.closeEntry();
			}
		};
		_manufacturers = new LinkedHashSet<>();
		_schema = 0;
		_programFiles = 0;
		_replacedBaggage = List.of();
		// The files the manufacturers ship with their data (haumacher/ehome #154), in a project file as
		// in a product file.
		BaggageImport baggage = new BaggageImport(_baggageSizeLimit);
		Map<String, Map<String, byte[]>> projectArchives = new LinkedHashMap<>();
		String last = null;
		ZipEntry entry;
		while ((entry = nextEntry(zip, last)) != null) {
			String name = entry.getName();
			last = name;
			String[] path = name.split("/");
			if (path.length > 1 && path[0].startsWith(MANUFACTURER_PREFIX)) {
				_manufacturers.add(path[0]);
			}
			if (productData) {
				checkProductEntry(name, path);
			}
			if (entry.isDirectory()) {
				continue;
			}
			if (BaggageImport.isIndex(path)) {
				baggage.readIndex(entryIn);
			} else if (BaggageImport.isFile(path)) {
				baggage.readFile(path[0] + name.substring(path[0].length() + BAGGAGES.length() + 1), entryIn);
			} else {
				if (isProgram(path)) {
					byte[] program = entryIn.readAllBytes();
					_programFiles++;
					scanModules(context, program);
					importXml(context, handler, name, new ByteArrayInputStream(program));
					completeAssigns(program);
					baggage.readProgramReferences(program);
				} else if (name.endsWith(".xml")) {
					importXml(context, handler, name, entryIn);
				} else if (isProjectArchive(name, path)) {
					// ETS 5/6: the project part is an archive of its own (protected by the project
					// password or not). It is opened at once (or was opened when the contents were
					// read), so that a missing or wrong password is refused before anything else is
					// read, and imported after the master and manufacturer data it refers to, which
					// may follow it in the file.
					Map<String, byte[]> opened = contents == null ? null : contents.getProjectArchives().get(name);
					projectArchives.put(name,
						opened != null ? opened : ProtectedProject.read(name, zip.readAllBytes(), _schema, _password));
				}
			}
		}
		for (Entry<String, Map<String, byte[]>> archive : projectArchives.entrySet()) {
			String archiveName = archive.getKey();
			String folder = archiveName.substring(0, archiveName.length() - ARCHIVE_SUFFIX.length());
			for (Entry<String, byte[]> projectEntry : archive.getValue().entrySet()) {
				String name = projectEntry.getKey();
				if (name.endsWith(".xml")) {
					String qualified = name.contains("/") ? name : folder + "/" + name;
					importXml(context, handler, qualified, new ByteArrayInputStream(projectEntry.getValue()));
				}
			}
		}
		if (productData && _manufacturers.isEmpty()) {
			throw new TopLogicException(I18NConstants.ERROR_NO_MANUFACTURER_DATA);
		}
		// After the master and manufacturer data, before the references are resolved (when the import
		// context is closed): the programs and products of the file refer to the files by their IDs.
		baggage.apply(context, _binding, _messages);
		_replacedBaggage = baggage.getReplaced();
		if (productData && _programFiles == 0) {
			_messages.log(Level.INFO, I18NConstants.INFO_NO_PROGRAM_IN_PRODUCT_DATA);
		}
	}

	/**
	 * Scans the dynamic modules of the application programs of the given program file
	 * (haumacher/ehome #119, see {@link ModuleScan}) for the handlers reading them, and plans the
	 * completion of a program an earlier import left without its modules (see
	 * {@link ModuleCompletion}).
	 */
	private void scanModules(ImportContext context, byte[] content) throws IOException {
		if (!ModuleScan.mentionsModules(content)) {
			return;
		}
		ModuleScan modules = ModuleScan.get(context);
		Document document = null;
		for (String id : modules.scan(content)) {
			if (modules.getRefusal(id) != null) {
				continue;
			}
			TLObject existing = existingProgram(id);
			if (existing != null && Boolean.TRUE.equals(existing.tValueByName("usesModules"))) {
				if (document == null) {
					document = ModuleScan.parse(content);
				}
				try {
					modules.complete(id, ModuleCompletion.plan(ModuleScan.dynamicOf(document, id), existing));
				} catch (ModuleCompletion.Mismatch ex) {
					Logger.warn("Program '" + id + "' cannot be completed: " + ex.getMessage(), Importer.class);
					_messages.log(Level.WARN, I18NConstants.WARN_MODULES_NOT_COMPLETED__PROGRAM.fill(id));
				}
			}
		}
	}

	/**
	 * Adds the assignments (<code>Assign</code>, haumacher/ehome #142) of the given program file to the
	 * known programs an earlier import left without them (<code>assignsRead</code> not set), see
	 * {@link AssignCompletion}; the programs this import created read them themselves.
	 */
	private void completeAssigns(byte[] content) throws IOException {
		if (!mentions(content, ASSIGN_MARKER)) {
			return;
		}
		Document document = null;
		for (String id : programIds(content)) {
			TLObject existing = existingProgram(id);
			if (existing == null || Boolean.TRUE.equals(existing.tValueByName(ASSIGNS_READ))) {
				continue;
			}
			if (document == null) {
				document = ModuleScan.parse(content);
			}
			Element program = ModuleScan.program(document, id);
			if (program == null) {
				continue;
			}
			try {
				int count = AssignCompletion.complete(program, existing);
				existing.tUpdateByName(ASSIGNS_READ, Boolean.TRUE);
				if (count > 0) {
					_messages.log(Level.INFO, I18NConstants.INFO_ASSIGNS_COMPLETED__PROGRAM_COUNT.fill(id, count));
				}
			} catch (ModuleCompletion.Mismatch ex) {
				Logger.warn("Program '" + id + "' cannot be completed: " + ex.getMessage(), Importer.class);
				_messages.log(Level.WARN, I18NConstants.WARN_ASSIGNS_NOT_COMPLETED__PROGRAM.fill(id));
			}
		}
	}

	/**
	 * The IDs of the application programs of the given program file, in their order.
	 */
	private static List<String> programIds(byte[] content) throws IOException {
		List<String> result = new ArrayList<>();
		try {
			XMLStreamReader in = XMLStreamUtil.getDefaultInputFactory()
				.createXMLStreamReader(new ByteArrayInputStream(content));
			try {
				while (in.hasNext()) {
					if (in.next() == XMLStreamConstants.START_ELEMENT
						&& "ApplicationProgram".equals(in.getLocalName())) {
						result.add(in.getAttributeValue(null, "Id"));
					}
				}
			} finally {
				in.close();
			}
		} catch (XMLStreamException ex) {
			throw new IOException("Cannot read the program file: " + ex.getMessage(), ex);
		}
		return result;
	}

	/**
	 * Whether the given content holds the given bytes.
	 */
	static boolean mentions(byte[] content, byte[] marker) {
		outer:
		for (int n = 0, last = content.length - marker.length; n <= last; n++) {
			for (int k = 0; k < marker.length; k++) {
				if (content[n + k] != marker[k]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	/**
	 * The application program with the given ID in the database, <code>null</code> if there is none.
	 */
	private static TLObject existingProgram(String id) {
		TLClass type = (TLClass) TLModelUtil.findType(PROGRAM_TYPE);
		for (TLObject program : MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class)) {
			if (id.equals(program.tValueByName("extId"))) {
				return program;
			}
		}
		return null;
	}

	private void importXml(ImportContext context, Handler handler, String name, InputStream content)
			throws XMLStreamException {
		Logger.info("Reading '" + name + "'.", Importer.class);
		_progress.beginEntry(name);

		// The entry name is the system ID, so that a message of the import names the file of the
		// archive. As an absolute URI: the parser resolves a relative one against the working
		// directory of the server.
		XMLStreamReader in = new SchemaVersionReader(XMLStreamUtil.getDefaultInputFactory()
			.createXMLStreamReader(ENTRY_SCHEME + name, content));
		handler.importXml(context, in);
		_progress.endEntry();
	}

	/**
	 * Reader recording the version of the project schema from the namespace of the root element,
	 * and telling the {@link EntryProgress} about the elements read.
	 */
	private final class SchemaVersionReader extends StreamReaderDelegate {

		private boolean _root = true;

		SchemaVersionReader(XMLStreamReader reader) {
			super(reader);
		}

		@Override
		public int next() throws XMLStreamException {
			int event = super.next();
			if (event == XMLStreamConstants.START_ELEMENT) {
				if (_root) {
					_root = false;
					_schema = Math.max(_schema, schemaVersion(getNamespaceURI()));
				}
				_progress.startElement(this);
			}
			return event;
		}

		@Override
		public int nextTag() throws XMLStreamException {
			int event;
			do {
				event = next();
			} while (event != XMLStreamConstants.START_ELEMENT && event != XMLStreamConstants.END_ELEMENT);
			return event;
		}
	}

	/**
	 * The version of the project schema a root element in the given namespace belongs to, 0 if it
	 * is not known.
	 */
	static int schemaVersion(String namespace) {
		if (namespace != null && namespace.startsWith(SCHEMA_NAMESPACE)) {
			try {
				return Integer.parseInt(namespace.substring(SCHEMA_NAMESPACE.length()));
			} catch (NumberFormatException ex) {
				// Not a version number, no information.
			}
		}
		return 0;
	}

	/**
	 * Whether the entry with the given path is an application program in the folder of its
	 * manufacturer (<code>M-xxxx/M-xxxx_A-….xml</code>).
	 */
	static boolean isProgram(String[] path) {
		return path.length == 2 && path[0].startsWith(MANUFACTURER_PREFIX)
			&& path[1].startsWith(path[0] + PROGRAM_INFIX) && path[1].endsWith(".xml");
	}

	/**
	 * Whether the entry with the given name and path is the project archive of ETS 5/6
	 * (<code>P-xxxx.zip</code>).
	 */
	static boolean isProjectArchive(String name, String[] path) {
		return path.length == 1 && name.startsWith(PROJECT_PREFIX) && name.endsWith(ARCHIVE_SUFFIX);
	}

	/**
	 * The next entry of the archive, refusing an archive that cannot be read, in particular one with
	 * an encrypted (password protected) entry.
	 */
	static ZipEntry nextEntry(ZipInputStream zip, String last) throws IOException {
		try {
			return zip.getNextEntry();
		} catch (ZipException ex) {
			// The JDK reads no encrypted entry and says so only in the message of the exception.
			String message = ex.getMessage();
			if (message != null && message.contains("encrypted")) {
				throw new TopLogicException(
					I18NConstants.ERROR_ENCRYPTED_ENTRY__AFTER.fill(last == null ? "-" : last), ex);
			}
			throw new TopLogicException(I18NConstants.ERROR_INVALID_ARCHIVE__DETAIL.fill(message), ex);
		}
	}

	/**
	 * Whether the entry with the given path is the baggage of a manufacturer
	 * (<code>M-xxxx/Baggages.xml</code> or below <code>M-xxxx/Baggages/</code>), which the import
	 * keeps as files of the manufacturer (see {@link BaggageImport}), and which is no XML entry of the
	 * mapping.
	 */
	static boolean isBaggage(String[] path) {
		return path.length > 1 && path[0].startsWith(MANUFACTURER_PREFIX)
			&& (BAGGAGES_INDEX.equals(path[1]) || (path.length > 2 && BAGGAGES.equals(path[1])));
	}

	/**
	 * Refuses an entry a product file must not hold: the part of a project.
	 */
	private static void checkProductEntry(String name, String[] path) {
		if (path[0].startsWith(PROJECT_PREFIX)) {
			throw new TopLogicException(I18NConstants.ERROR_PROJECT_IN_PRODUCT_DATA__ENTRY.fill(name));
		}
	}

	/**
	 * Sets the size limit of a file of a manufacturer's baggage: a larger file is not imported, the
	 * catalog lists it without its content, and the import reports it. By default
	 * {@link BaggageImport#DEFAULT_SIZE_LIMIT}.
	 *
	 * @param bytes
	 *        The largest number of bytes of a file whose content is imported.
	 */
	public void setBaggageSizeLimit(long bytes) {
		_baggageSizeLimit = bytes;
	}

	/**
	 * The messages the last {@link #importStream(InputStream)} reported: the warnings and errors of
	 * the import context, each with its location in the project file (the entry of the archive, line
	 * and column).
	 *
	 * <p>
	 * They are written to the application log as well. A message does not change the outcome of the
	 * import: a value that does not fit its type, for instance, is kept as text and reported.
	 * </p>
	 */
	public List<BufferingI18NLog.Entry> getMessages() {
		return _messages.getEntries();
	}

	/**
	 * Imports the ETS project file (<code>.knxproj</code>) read from the given stream.
	 *
	 * @see #getMessages()
	 */
	public void importStream(InputStream in)
			throws IOException, XMLStreamException, ConfigurationException {
		importStream(in, null);
	}

	/**
	 * Imports the ETS project file (<code>.knxproj</code>) read from the given stream, whose project
	 * may be protected by the given password.
	 *
	 * <p>
	 * A protected project without a password, or with a password that does not open it, is refused
	 * with a {@link TopLogicException} naming the reason.
	 * </p>
	 *
	 * @param password
	 *        The project password, <code>null</code> or empty if none was given.
	 *
	 * @see #getMessages()
	 */
	public void importStream(InputStream in, String password)
			throws IOException, XMLStreamException, ConfigurationException {
		_password = password;
		importStream(in, false, null, ImportProgress.NONE);
	}

	/**
	 * Imports the ETS project file (<code>.knxproj</code>) read from the given stream, whose
	 * contents were read beforehand, reporting the progress to the given callback (haumacher/ehome
	 * #84).
	 *
	 * @param password
	 *        The project password, <code>null</code> or empty if none was given; the project
	 *        archive was opened with it already by {@link ArchiveContents#read}.
	 * @param contents
	 *        The contents of the file, read from it by {@link ArchiveContents#read}.
	 * @param progress
	 *        The callback reporting the progress, which may stop the import with an
	 *        {@link com.top_logic.basic.AbortExecutionException}.
	 *
	 * @see #importStream(InputStream, String)
	 */
	public void importStream(InputStream in, String password, ArchiveContents contents, ImportProgress progress)
			throws IOException, XMLStreamException, ConfigurationException {
		_password = password;
		importStream(in, false, contents, progress);
	}

	/**
	 * Imports the product file (<code>.knxprod</code>) of a manufacturer read from the given stream:
	 * the master data, catalogs, hardware and application programs, with the same mapping as a
	 * project file, without a project.
	 *
	 * <p>
	 * A product file holding a project, an encrypted entry, or no data of a manufacturer is refused
	 * with a {@link TopLogicException} naming the reason. The files the manufacturer ships with its
	 * data (baggage) are kept with the manufacturer (haumacher/ehome #154).
	 * </p>
	 *
	 * @see #getMessages()
	 * @see #getManufacturerIds()
	 * @see #getCreated(String)
	 * @see #getKnown(String)
	 */
	public void importProductData(InputStream in)
			throws IOException, XMLStreamException, ConfigurationException {
		_password = null;
		importStream(in, true, null, ImportProgress.NONE);
	}

	/**
	 * Imports the product file (<code>.knxprod</code>) of a manufacturer read from the given stream,
	 * whose contents were read beforehand, reporting the progress to the given callback
	 * (haumacher/ehome #84).
	 *
	 * @param contents
	 *        The contents of the file, read from it by {@link ArchiveContents#read}.
	 * @param progress
	 *        The callback reporting the progress, which may stop the import with an
	 *        {@link com.top_logic.basic.AbortExecutionException}.
	 *
	 * @see #importProductData(InputStream)
	 */
	public void importProductData(InputStream in, ArchiveContents contents, ImportProgress progress)
			throws IOException, XMLStreamException, ConfigurationException {
		_password = null;
		importStream(in, true, contents, progress);
	}

	private void importStream(InputStream in, boolean productData, ArchiveContents contents,
			ImportProgress progress) throws IOException, XMLStreamException, ConfigurationException {
		_messages = new BufferingI18NLog();
		_progress = progress == ImportProgress.NONE
			? new EntryProgress(progress, 0, 0, Set.of())
			: new EntryProgress(progress, contents == null ? 0 : contents.getEntries(),
				contents == null ? 0 : contents.getPrograms(), knownPrograms());
		Log log = new SyserrProtocol();
		DefaultInstantiationContext context = new DefaultInstantiationContext(log);
		ConfigurationReader reader = new ConfigurationReader(context, Collections.singletonMap("handler",
			TypedConfiguration.getConfigurationDescriptor(DispatchingImporter.Config.class)));
		Content content = new ClassRelativeBinaryContent(Importer.class, "etsImporter.xml");
		reader.setSource(content);
		ConfigurationItem config = reader.read();
		Handler configured = (Handler) context.getInstance((PolymorphicConfiguration<?>) config);
		context.checkErrors();
		_binding.clearCreated();
		// The parts of the existing application programs, asked after the shared objects of
		// <resolve-existing> in etsImporter.xml.
		SharedObjects shared = new SharedObjects(_binding);
		Handler handler = (importContext, xml) -> importContext.withIdLookup(shared, xml, configured);
		ZipInputStream zip = new ZipInputStream(in);
		ModuleImport modules;
		try (ImportContext importContext = new DefaultImportContext(
			log.asI18NLog(ResourcesModule.getInstance().getBundle(Locale.ENGLISH)).tee(_messages), _binding)) {
			importContext.set(JoiningModelBinding.PROPERTY, _binding);
			MasterDataUpdates updates = new MasterDataUpdates(_binding);
			importContext.set(MasterDataUpdates.PROPERTY, updates);
			_programLanguages = new ProgramLanguages();
			importContext.set(ProgramLanguages.PROPERTY, _programLanguages);
			modules = ModuleImport.get(importContext);
			importETS(zip, importContext, handler, productData, contents);
			// Reported at the end of each program already; none is left unless a parameter lies
			// outside a program.
			HiddenProgramValues.reportPending(importContext);
			updates.apply(importContext);
		}
		// Numbers the app allocates for new objects lie above those of the imported ones.
		ExtIds.raiseImported(_binding.getCreated());
		// The module instances of the devices, with their settings and objects (haumacher/ehome #119).
		modules.attach(getCreated(DEVICE_TYPE), _messages);
		dropUnresolvedProgramParts();
		// The parameter calculations of the programs and devices read (haumacher/ehome #123).
		checkCalculations();
		// The parameter validations of the programs and devices read (haumacher/ehome #124), after the
		// calculations, which may have derived L values.
		checkValidations();
		// All done only when the references are resolved, which the import context does when closed.
		_progress.finish();
	}

	/**
	 * Removes the parameter settings and connectors of the devices the import created whose part of
	 * the application program it could not resolve, and reports them per device.
	 *
	 * <p>
	 * They refer to parts of module instances the import could not attach (see {@link ModuleImport}):
	 * of the dynamic modules of a program the import could not read (<code>ModuleDefs</code>, see
	 * {@link ReadModules}), or of an instance E-Home does not compute for the device's values, named
	 * by IDs of the form <code>MD-1_M-1_MI-1_P-2_R-3</code>. Kept
	 * without their part, they would fail the model's check of the mandatory reference and refuse the
	 * whole import (a production import of a project with MDT devices).
	 * </p>
	 */
	private void dropUnresolvedProgramParts() {
		Map<TLObject, int[]> dropped = new LinkedHashMap<>();
		for (TLObject setting : getCreated(SETTING_TYPE)) {
			if (setting.tValid() && setting.tValueByName("parameter") == null) {
				dropped.computeIfAbsent(setting.tContainer(), d -> new int[2])[0]++;
				setting.tDelete();
			}
		}
		for (TLObject connector : getCreated(CONNECTOR_TYPE)) {
			if (connector.tValid() && connector.tValueByName("port") == null) {
				dropped.computeIfAbsent(connector.tContainer(), d -> new int[2])[1]++;
				connector.tDelete();
			}
		}
		for (Map.Entry<TLObject, int[]> entry : dropped.entrySet()) {
			_messages.log(Level.WARN, I18NConstants.WARN_UNRESOLVED_PROGRAM_PARTS__DEVICE_SETTINGS_OBJECTS.fill(
				MetaLabelProvider.INSTANCE.getLabel(entry.getKey()), Integer.valueOf(entry.getValue()[0]),
				Integer.valueOf(entry.getValue()[1])));
		}
	}

	/**
	 * Reports the parameter calculations the import read that E-Home cannot evaluate, and brings the
	 * values of the devices it created in line with the calculations of their programs (see
	 * {@link DeviceCalculations#imported(TLObject)}).
	 */
	private void checkCalculations() {
		// Reported per import for the programs it read and those of the devices it read.
		Set<Program> programs = new LinkedHashSet<>();
		for (TLObject calculation : getCreated(CALCULATION_TYPE)) {
			programs.add(ModuleParts.programOf(calculation));
		}
		for (TLObject device : getCreated(DEVICE_TYPE)) {
			programs.add(DeviceConfiguration.program(device));
		}
		programs.remove(null);
		for (Program program : programs) {
			for (ParameterCalculation calculation : DeviceCalculations.all(program)) {
				String problem = DeviceCalculations.problem(calculation);
				if (problem == null) {
					for (List<? extends CalculationParameter> side : List.of(calculation.getInputs(),
						calculation.getOutputs())) {
						for (CalculationParameter parameter : side) {
							if (parameter.getParameter() == null || DeviceCalculations.variable(parameter) == null) {
								problem = "a parameter of the calculation was not found";
							}
						}
					}
				}
				if (problem != null) {
					_messages.log(Level.WARN,
						I18NConstants.WARN_CALCULATION_NOT_EVALUATED__PROGRAM_CALCULATION_PROBLEM.fill(
							program.getExtId(), DeviceCalculations.label(calculation), problem));
				}
			}
		}
		for (TLObject device : getCreated(DEVICE_TYPE)) {
			for (ResKey problem : DeviceCalculations.imported(device)) {
				_messages.log(Level.WARN, I18NConstants.WARN_CALCULATION_KEPT__DEVICE_PROBLEM.fill(
					MetaLabelProvider.INSTANCE.getLabel(device), problem));
			}
		}
	}

	/**
	 * Reports the parameter validations the import read that E-Home cannot evaluate, and the devices it
	 * created whose values a validation of their program refuses (see
	 * {@link DeviceValidations#imported(TLObject)}); the devices keep their values.
	 */
	private void checkValidations() {
		Set<Program> programs = new LinkedHashSet<>();
		for (TLObject validation : getCreated(VALIDATION_TYPE)) {
			programs.add(ModuleParts.programOf(validation));
		}
		for (TLObject device : getCreated(DEVICE_TYPE)) {
			programs.add(DeviceConfiguration.program(device));
		}
		programs.remove(null);
		for (Program program : programs) {
			for (ParameterValidation validation : DeviceValidations.all(program)) {
				String problem = DeviceValidations.problem(validation);
				if (problem == null) {
					for (CalculationParameter parameter : validation.getParameters()) {
						if (parameter.getParameter() == null || DeviceCalculations.variable(parameter) == null) {
							problem = "a parameter of the validation was not found";
						}
					}
				}
				if (problem != null) {
					_messages.log(Level.WARN,
						I18NConstants.WARN_VALIDATION_NOT_EVALUATED__PROGRAM_VALIDATION_PROBLEM.fill(
							program.getExtId(), DeviceValidations.label(validation), problem));
				}
			}
		}
		for (TLObject device : getCreated(DEVICE_TYPE)) {
			for (ResKey problem : DeviceValidations.imported(device)) {
				_messages.log(Level.WARN, I18NConstants.WARN_VALIDATION_FAILED__DEVICE_PROBLEM.fill(
					MetaLabelProvider.INSTANCE.getLabel(device), problem));
			}
		}
	}

	/**
	 * The IDs of the application programs that exist now.
	 */
	private static Set<String> knownPrograms() {
		Set<String> result = new HashSet<>();
		TLClass type = (TLClass) TLModelUtil.findType(PROGRAM_TYPE);
		for (TLObject program : MetaElementUtil.getAllInstancesOf(type, TLObject.class)) {
			Object id = program.tValueByName("extId");
			if (id != null) {
				result.add(id.toString());
			}
		}
		return result;
	}

	/**
	 * The number of application programs the file the last import read holds (each created or
	 * skipped as known); 0 for a product file without a program, such as one bringing only a driver.
	 */
	public int getProgramFiles() {
		return _programFiles;
	}

	/**
	 * The files of manufacturers (baggage) known before the last import whose content it replaced by
	 * a newer version (haumacher/ehome #154).
	 */
	public List<TLObject> getReplacedBaggage() {
		return _replacedBaggage;
	}

	/**
	 * The version of the project schema of the file the last import read (11 for ETS 4, 23 for ETS
	 * 6.3), 0 if it is not known.
	 */
	public int getSchemaVersion() {
		return _schema;
	}

	/**
	 * The IDs of the manufacturers whose data (the folders <code>M-xxxx/</code>) the last import read.
	 */
	public Set<String> getManufacturerIds() {
		return _manufacturers;
	}

	/**
	 * The objects of the given type the last import created, in the order of their creation.
	 *
	 * @param typeName
	 *        The qualified name of the type, e.g. <code>ehome.operational:Program</code>.
	 */
	public List<TLObject> getCreated(String typeName) {
		return ofType(_binding.getCreated(), typeName);
	}

	/**
	 * The languages the last import added to the texts of known application programs (the
	 * languages a program imported earlier did not have), per program; programs without a new
	 * language are left out (haumacher/ehome #38).
	 */
	public Map<TLObject, Set<Locale>> getAddedLanguages() {
		return _programLanguages.getAdded();
	}

	/**
	 * The objects of the given type that existed before the last import, and which it found under
	 * their IDs: reused, skipped as known, or referenced.
	 *
	 * @param typeName
	 *        The qualified name of the type, e.g. <code>ehome.operational:Program</code>.
	 */
	public List<TLObject> getKnown(String typeName) {
		Set<Object> created = new HashSet<>(_binding.getCreated());
		return ofType(_binding.getResolved().stream().filter(obj -> !created.contains(obj)).toList(), typeName);
	}

	private List<TLObject> ofType(Collection<?> objects, String typeName) {
		List<TLObject> result = new ArrayList<>();
		for (Object obj : objects) {
			// Created objects the import removed again (dropUnresolvedProgramParts) are not listed.
			if (obj instanceof TLObject tlObject && tlObject.tValid() && _binding.isInstanceOf(tlObject, typeName)) {
				result.add(tlObject);
			}
		}
		return result;
	}

}
