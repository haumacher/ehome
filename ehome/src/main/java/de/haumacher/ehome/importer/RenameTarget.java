/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.logging.Level;
import com.top_logic.model.TLObject;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.AbstractReferenceImport;

/**
 * Imports the target of a rename of an application program (ETS: <code>Rename@RefId</code>, the block
 * or channel getting another title, haumacher/ehome #122).
 *
 * <p>
 * ETS renames separators of the parameter dialog as well (<code>ParameterSeparator</code>, ID
 * <code>…_PS-31</code>), which E-Home does not import (<code>doc/knx/schema-coverage.md</code>, gap 7).
 * A rename whose target the program does not hold in E-Home is left out of the program with a
 * warning naming the rename and its target (haumacher/ehome #160); the program is imported
 * without it.
 * </p>
 */
public class RenameTarget<C extends RenameTarget.Config<?>> extends AbstractReferenceImport<C> {

	private static final String EXT_ID = "extId";

	/**
	 * Configuration of {@link RenameTarget}.
	 */
	@TagName("rename-target")
	public interface Config<I extends RenameTarget<?>> extends AbstractReferenceImport.Config<I> {
		// Only the ID attribute and the target.
	}

	/**
	 * Creates a {@link RenameTarget} from configuration.
	 */
	@CalledByReflection
	public RenameTarget(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String rawId = readRawId(in);
		if (rawId == null || rawId.isBlank()) {
			return null;
		}
		String target = rawId.trim();
		Object rename = var(context);
		Location location = MasterDataUpdates.position(in.getLocation());
		// Not required: an unresolved target is reported here, not as an error of the import.
		Object reference = context.resolveObject(this, location, target, false);
		context.deref(reference, x -> {
			// Stored below.
		}, () -> leaveOut(context, location, rename, target));
		storeReference(context, reference);
		return null;
	}

	private static void leaveOut(ImportContext context, Location location, Object rename, String target) {
		if (!(rename instanceof TLObject obj) || !obj.tValid()) {
			return;
		}
		Object id = obj.tValueByName(EXT_ID);
		context.log(Level.WARN, location,
			I18NConstants.WARN_RENAME_TARGET_NOT_IMPORTED__RENAME_TARGET.fill(id == null ? "" : id.toString(), target),
			null);
		obj.tDelete();
	}

}
