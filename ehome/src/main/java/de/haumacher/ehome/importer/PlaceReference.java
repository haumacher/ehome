/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.Set;

import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.col.TypedAnnotatable;
import com.top_logic.basic.col.TypedAnnotatable.Property;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.xml.XMLStreamUtil;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;

/**
 * Places a parameter or communication object reference (ETS: <code>ParameterRefRef</code>,
 * <code>ComObjectRefRef</code>) in the structure element being imported.
 *
 * <p>
 * The first place of a reference owns it (<code>ehome.operational:ConfigFragment#contents</code>).
 * ETS 6 places one reference in several cases of a switch; every further place gets an
 * <code>ehome.operational:SharedPlacement</code> referring to it, so that the reference keeps one
 * identity: its ID, its default, and the device settings referring to it.
 * </p>
 */
public class PlaceReference<C extends PlaceReference.Config<?>> extends ConfiguredImportHandler<C> {

	private static final String CONTENTS = "contents";

	private static final String SHARED_PLACEMENT = "ehome.operational:SharedPlacement";

	private static final String PLACED = "placed";

	/**
	 * The references placed by the current import.
	 */
	private static final Property<Set<Object>> PLACED_REFERENCES =
		TypedAnnotatable.propertySet("placedReferences");

	/**
	 * Configuration of {@link PlaceReference}.
	 */
	@TagName("place-reference")
	public interface Config<I extends PlaceReference<?>> extends ConfiguredImportHandler.Config<I> {

		/**
		 * The XML attribute holding the ID of the placed reference.
		 */
		@Name("id-attribute")
		@Mandatory
		String getIdAttribute();

	}

	/**
	 * Creates a {@link PlaceReference} from configuration.
	 */
	@CalledByReflection
	public PlaceReference(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String id = in.getAttributeValue(null, getConfig().getIdAttribute());
		Object container = context.getVar(ImportContext.THIS_VAR);
		Location location = in.getLocation();
		if (id != null && !id.isBlank()) {
			Object reference = context.resolveObject(this, location, id.trim());
			context.deref(reference, target -> place(context, location, container, target));
		}
		XMLStreamUtil.skipUpToMatchingEndTag(in);
		return null;
	}

	private void place(ImportContext context, Location location, Object container, Object reference) {
		Set<Object> placed = context.mkSet(PLACED_REFERENCES);
		if (placed.add(reference)) {
			context.addValue(this, container, CONTENTS, reference);
		} else {
			Object placement = context.createObject(this, location, SHARED_PLACEMENT, null);
			context.setReference(this, placement, PLACED, reference);
			context.addValue(this, container, CONTENTS, placement);
		}
	}

}
