/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.List;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.basic.config.annotation.DefaultContainer;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.model.TLObject;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;
import com.top_logic.xio.importer.handlers.Handler;

/**
 * Imports the content of a device (ETS: <code>DeviceInstance</code>) with the scopes that the short
 * IDs of newer ETS versions are relative to.
 *
 * <p>
 * Since ETS 5, a device names its communication objects by IDs relative to its application program
 * (<code>O-2_R-1</code> for <code>M-0083_A-0028-38-DB99_O-2_R-1</code>), and since ETS 5.7 the group
 * addresses it is linked to by IDs relative to its installation (<code>GA-1</code> for
 * <code>P-047E-0_GA-1</code>). The program is the one of the device's program assignment
 * (<code>Hardware2ProgramRefId</code>); the installation is the scope of the device's own ID. Both
 * are made available in the variables {@link #PROGRAM_VAR} and {@link #INSTALLATION_VAR} for
 * {@link ProgramReference} and {@link GroupLinks}.
 * </p>
 */
public class DeviceScope<C extends DeviceScope.Config<?>> extends ConfiguredImportHandler<C> {

	/**
	 * Name of the import variable holding the ID of the application program of the current device.
	 */
	public static final String PROGRAM_VAR = "programId";

	/**
	 * Name of the import variable holding the ID of the installation of the current device.
	 */
	public static final String INSTALLATION_VAR = "installationId";

	private static final String ID = "Id";

	private static final String ASSIGNMENT = "Hardware2ProgramRefId";

	private static final String PROGRAM = "program";

	private static final String EXT_ID = "extId";

	/**
	 * Separator of the parts of an ETS ID.
	 */
	private static final char SEPARATOR = '_';

	/**
	 * Configuration of {@link DeviceScope}.
	 */
	@TagName("device-scope")
	public interface Config<I extends DeviceScope<?>> extends ConfiguredImportHandler.Config<I> {

		/**
		 * The handlers importing the content of the device.
		 */
		@DefaultContainer
		List<PolymorphicConfiguration<? extends Handler>> getContents();

	}

	private final List<Handler> _contents;

	/**
	 * Creates a {@link DeviceScope} from configuration.
	 */
	@CalledByReflection
	public DeviceScope(InstantiationContext context, C config) {
		super(context, config);
		_contents = TypedConfiguration.getInstanceList(context, config.getContents());
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String id = in.getAttributeValue(null, ID);
		int scope = id == null ? -1 : id.lastIndexOf(SEPARATOR);
		String installation = scope < 0 ? null : id.substring(0, scope);
		String program = programId(context, in);
		return context.withVar(INSTALLATION_VAR, installation, in,
			(c1, in1) -> c1.withVar(PROGRAM_VAR, program, in1, this::importContents));
	}

	/**
	 * The ID of the application program the device's program assignment names, <code>null</code>
	 * if it is not known.
	 */
	private String programId(ImportContext context, XMLStreamReader in) {
		String assignmentId = in.getAttributeValue(null, ASSIGNMENT);
		if (assignmentId == null || assignmentId.isBlank()) {
			return null;
		}
		Object assignment = context.resolveObjectBackwards(this, in.getLocation(), assignmentId.trim());
		if (assignment instanceof TLObject obj && obj.tValueByName(PROGRAM) instanceof TLObject program) {
			return (String) program.tValueByName(EXT_ID);
		}
		return null;
	}

	private Object importContents(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		Object result = null;
		for (Handler handler : _contents) {
			result = handler.importXml(context, in);
		}
		return result;
	}

	/**
	 * The full ID of an ID given relative to the scope in the given variable: the ID itself if it is
	 * a full one already (it names the scope, as ETS 4 writes all IDs) or no scope is known, else the
	 * scope, '_' and the ID.
	 *
	 * @param fullPrefix
	 *        The beginning of a full ID: <code>M-</code> (manufacturer data) or <code>P-</code>
	 *        (project).
	 */
	public static String absolute(ImportContext context, String scopeVar, String fullPrefix, String id) {
		if (id.startsWith(fullPrefix)) {
			return id;
		}
		Object scope = context.getVarOrNull(scopeVar);
		if (scope == null) {
			return id;
		}
		return scope + String.valueOf(SEPARATOR) + id;
	}

}
