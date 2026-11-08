/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.model.TLObject;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;

/**
 * Reads the module instances a device of a project file lists with their arguments (ETS:
 * <code>DeviceInstance/ModuleInstances</code>, haumacher/ehome #119) for the comparison with the
 * instances E-Home computes (see {@link ModuleImport}): the instances themselves are E-Home's.
 */
public class DeviceModuleInstances<C extends DeviceModuleInstances.Config<?>> extends ConfiguredImportHandler<C> {

	/**
	 * Configuration of {@link DeviceModuleInstances}.
	 */
	@TagName("device-module-instances")
	public interface Config<I extends DeviceModuleInstances<?>> extends ConfiguredImportHandler.Config<I> {
		// No options.
	}

	/**
	 * Creates a {@link DeviceModuleInstances} from configuration.
	 */
	@CalledByReflection
	public DeviceModuleInstances(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		Object device = context.getVar(ImportContext.THIS_VAR);
		ModuleImport modules = ModuleImport.get(context);
		String instance = null;
		int depth = 0;
		while (true) {
			int event = in.next();
			if (event == XMLStreamConstants.START_ELEMENT) {
				depth++;
				String name = in.getLocalName();
				if ("ModuleInstance".equals(name)) {
					instance = in.getAttributeValue(null, "Id");
				} else if ("Argument".equals(name) && instance != null && device instanceof TLObject obj) {
					String argument = in.getAttributeValue(null, "RefId");
					if (argument != null) {
						modules.arguments(obj, instance, argument, in.getAttributeValue(null, "Value"));
					}
				}
			} else if (event == XMLStreamConstants.END_ELEMENT) {
				if (depth == 0) {
					break;
				}
				depth--;
			} else if (event == XMLStreamConstants.END_DOCUMENT) {
				break;
			}
		}
		return null;
	}

}
