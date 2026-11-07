/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;

import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;

/**
 * Imports a parameter value as ETS writes it into the typed value of the current object (a
 * parameter, parameter reference or parameter setting), decoded according to the object's parameter
 * type, see {@link ParameterValues#decode(TLObject, String)}.
 *
 * <p>
 * The type is read from the object's references, so they must be imported before this handler
 * runs. ETS files define the parameter types before the parameters, and the programs before the
 * projects using them, so the references are resolved at this point. A value that cannot be decoded
 * for its type is kept as text and reported as a warning naming the parameter, with its location
 * (see {@link Importer#getMessages()}).
 * </p>
 *
 * <p>
 * A value of the application program itself (of a parameter or a parameter reference) is reported
 * only if the parameter is visible, see {@link HiddenProgramValues}. A value of the project (a
 * parameter setting) is always reported.
 * </p>
 */
public class TypedValueImport<C extends TypedValueImport.Config<?>> extends ConfiguredImportHandler<C> {

	private static final String NAME = "name";

	private static final String PARAMETER = "parameter";

	/**
	 * Configuration of {@link TypedValueImport}.
	 */
	@TagName("typed-value")
	public interface Config<I extends TypedValueImport<?>> extends ConfiguredImportHandler.Config<I> {

		/**
		 * The XML attribute holding the value.
		 */
		@Name("xml-attribute")
		@Mandatory
		String getXmlAttribute();

	}

	/**
	 * Creates a {@link TypedValueImport} from configuration.
	 */
	@CalledByReflection
	public TypedValueImport(InstantiationContext context, C config) {
		super(context, config);
	}

	private static final String SETTING_TYPE = "ehome.physical:ParameterSetting";

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String value = in.getAttributeValue(null, getConfig().getXmlAttribute());
		TLObject self = (TLObject) context.getVar(ImportContext.THIS_VAR);
		ResKey problem = ParameterValues.decode(self, value);
		HiddenProgramValues hidden = HiddenProgramValues.get(context);
		ParameterView view = self instanceof ParameterView placed ? placed : null;
		if (view != null) {
			hidden.referenced(view);
		}
		if (problem != null && !withoutParameter(self)) {
			String name = parameterName(self);
			ResKey message = name == null ? problem : I18NConstants.WARN_PARAMETER__NAME_PROBLEM.fill(name, problem);
			if (self instanceof Parameter parameter) {
				// Reported at the end of the program, if the parameter is visible.
				hidden.defer(parameter, MasterDataUpdates.position(in.getLocation()), message);
			} else if (view == null || HiddenProgramValues.isVisible(view)) {
				context.log(Level.WARN, in.getLocation(), message);
			}
		}
		return null;
	}

	/**
	 * Whether the holder is a device setting whose parameter the import did not find (a parameter of
	 * a dynamic module): the importer leaves it out and reports it per device
	 * (<code>Importer#dropUnresolvedProgramParts</code>), its value has no type to tell.
	 */
	private static boolean withoutParameter(TLObject self) {
		return SETTING_TYPE.equals(TLModelUtil.qualifiedName(self.tType())) && self.tValueByName("parameter") == null;
	}

	/**
	 * The name of the parameter whose value the given holder is: its own stored name, or that of
	 * the parameter it refers to (a parameter reference without a name, a device setting).
	 */
	private static String parameterName(TLObject holder) {
		TLObject current = holder;
		while (current != null) {
			TLStructuredTypePart namePart = current.tType().getPart(NAME);
			if (namePart != null && !namePart.isDerived()
				&& current.tValue(namePart) instanceof String name && !name.isBlank()) {
				return name;
			}
			TLStructuredTypePart parameterPart = current.tType().getPart(PARAMETER);
			current = parameterPart == null ? null : (TLObject) current.tValue(parameterPart);
		}
		return null;
	}

}
