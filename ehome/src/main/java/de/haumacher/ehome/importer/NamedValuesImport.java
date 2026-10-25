/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.logging.Level;
import com.top_logic.model.TLObject;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;

/**
 * Imports the named values of a datapoint subtype from its encoding (ETS 5.5 and later:
 * <code>DatapointSubtype/Format</code>) into <code>ehome.datapoint:DatapointSubtype#values</code>.
 *
 * <p>
 * Read are the values of a subtype whose value is an enumeration: a <code>Format</code> consisting
 * of one <code>Enumeration</code> (20.102 HVAC mode: 0 "Auto", 1 "Comfort", ...). An enumeration
 * that is one field of a composed value (the day of 10.001 time of day, the fields of 219.001) names
 * the values of that field, not of the subtype; the model has no fields of a datapoint type, so these
 * are not read. A value known from an earlier import (by its ID) is reused, its text updated.
 * </p>
 */
public class NamedValuesImport<C extends NamedValuesImport.Config<?>> extends ConfiguredImportHandler<C> {

	private static final String NAMED_VALUE = "ehome.datapoint:NamedValue";

	private static final String VALUES = "values";

	private static final String EXT_ID = "extId";

	private static final String VALUE = "value";

	private static final String TEXT = "text";

	private static final String ENUMERATION = "Enumeration";

	private static final String ENUM_VALUE = "EnumValue";

	/**
	 * Configuration of {@link NamedValuesImport}.
	 */
	@TagName("named-values")
	public interface Config<I extends NamedValuesImport<?>> extends ConfiguredImportHandler.Config<I> {
		// No options.
	}

	private record Entry(String id, String value, String text, Location location) {
		// Pure data.
	}

	/**
	 * Creates a {@link NamedValuesImport} from configuration.
	 */
	@CalledByReflection
	public NamedValuesImport(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		Object subtype = context.getVar(ImportContext.THIS_VAR);
		int fields = 0;
		boolean enumeration = false;
		List<Entry> entries = new ArrayList<>();
		int depth = 1;
		while (depth > 0) {
			int event = in.next();
			if (event == XMLStreamConstants.START_ELEMENT) {
				depth++;
				if (depth == 2) {
					fields++;
					enumeration = ENUMERATION.equals(in.getLocalName());
				} else if (depth == 3 && enumeration && ENUM_VALUE.equals(in.getLocalName())) {
					entries.add(new Entry(in.getAttributeValue(null, "Id"), in.getAttributeValue(null, "Value"),
						in.getAttributeValue(null, "Text"), in.getLocation()));
				}
			} else if (event == XMLStreamConstants.END_ELEMENT) {
				depth--;
			}
		}
		if (fields == 1 && enumeration) {
			Locale locale = I18NTexts.language(context);
			for (Entry entry : entries) {
				importValue(context, subtype, entry, locale);
			}
		}
		return null;
	}

	private void importValue(ImportContext context, Object subtype, Entry entry, Locale locale) {
		Integer number;
		try {
			number = Integer.valueOf(entry.value().trim());
		} catch (NumberFormatException | NullPointerException ex) {
			context.log(Level.WARN, entry.location(),
				I18NConstants.WARN_NAMED_VALUE_NOT_A_NUMBER__ID_VALUE.fill(entry.id(), entry.value()));
			return;
		}
		Object value = entry.id() == null ? null : context.resolveObjectBackwards(this, entry.location(), entry.id());
		if (value == null) {
			value = context.createObject(this, entry.location(), NAMED_VALUE, entry.id());
		}
		context.setProperty(this, value, EXT_ID, entry.id());
		context.setProperty(this, value, VALUE, number);
		I18NTexts.set((TLObject) value, TEXT, locale, entry.text(), true);
		context.addValue(this, subtype, VALUES, value);
	}

}
