/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.template;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.top_logic.model.TLClass;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.programming.GroupAddressTypes;

/**
 * The device templates of the installation (haumacher/ehome #103, module <code>ehome.template</code>):
 * at most one per application program, each with an overlay per communication object of the
 * program. What a template's overlay gives an object applies to every device of the program through
 * {@link ObjectTypes}.
 */
public final class DeviceTemplates {

	private static final String TEMPLATE = "ehome.template:DeviceTemplate";

	private static final String OVERLAY = "ehome.template:ObjectOverlay";

	private DeviceTemplates() {
		// Static only.
	}

	/**
	 * The singleton holding the templates (<code>ehome.template:DeviceTemplates</code>).
	 */
	public static TLObject root() {
		TLModule module = TLModelUtil.findModule("ehome.template");
		return module == null ? null : module.getSingleton(TLModule.DEFAULT_SINGLETON_NAME);
	}

	/**
	 * The template of the given application program, <code>null</code> if it has none.
	 */
	public static TLObject template(TLObject program) {
		if (program == null) {
			return null;
		}
		for (TLObject template : program.tReferers(reference(TEMPLATE, "program"))) {
			if (template != null && template.tValid() && template.tContainer() != null) {
				return template;
			}
		}
		return null;
	}

	/**
	 * Creates the template of the given application program, with an overlay without a type for
	 * each of its communication objects (each placement), ordered by the number of the object.
	 *
	 * <p>
	 * Must be called within a transaction.
	 * </p>
	 *
	 * @return The new template.
	 * @throws TopLogicException
	 *         If the program has a template already, or is no application program.
	 */
	public static TLObject create(TLObject program) {
		if (!ExtIds.isA(program, "ehome.operational:Program")) {
			throw new TopLogicException(I18NConstants.ERROR_NO_PROGRAM__VALUE.fill(program));
		}
		TLObject existing = template(program);
		if (existing != null) {
			throw new TopLogicException(I18NConstants.ERROR_TEMPLATE_EXISTS__PROGRAM.fill(program));
		}
		TLObject root = root();
		TLObject template = create(TEMPLATE);
		template.tUpdateByName("program", program);
		List<TLObject> overlays = new ArrayList<>();
		for (Interface placed : placements(program)) {
			TLObject overlay = create(OVERLAY);
			overlay.tUpdateByName("placement", placed);
			overlays.add(overlay);
		}
		template.tUpdateByName("overlays", overlays);
		List<Object> templates = new ArrayList<>((Collection<?>) root.tValueByName("templates"));
		templates.add(template);
		root.tUpdateByName("templates", templates);
		return template;
	}

	/**
	 * The communication objects of the program as placed in its structure, by the number of the
	 * object.
	 */
	public static List<Interface> placements(TLObject program) {
		List<Interface> result = new ArrayList<>();
		if (program == null) {
			return result;
		}
		for (Object element : (Collection<?>) program.tValueByName("ports")) {
			result.addAll(((Port) element).getInterfaces());
		}
		result.sort(Comparator.comparingInt((Interface i) -> i.getPort().getIndex())
			.thenComparing(i -> String.valueOf(i.tValueByName("displayName"))));
		return result;
	}

	/**
	 * The products that run the program (through their program assignments).
	 */
	public static List<TLObject> products(TLObject program) {
		Set<TLObject> result = new LinkedHashSet<>();
		if (program != null) {
			for (TLObject assignment : program.tReferers(reference("ehome.catalog:ProgramAssignment", "program"))) {
				if (assignment != null && assignment.tValid() && assignment.tContainer() != null) {
					result.add(assignment.tContainer());
				}
			}
		}
		return new ArrayList<>(result);
	}

	/**
	 * The communication objects of the program for which E-Home cannot determine a datapoint type in
	 * a device that connects them to a group address: no type in the chain of {@link ObjectTypes},
	 * and a size other than 1 bit (DPT 1) or 4 bits (DPT 3), which alone decide the main type.
	 * Each object (placement) is counted once, whichever devices and projects connect it.
	 */
	public static List<Interface> untypedConnected(TLObject program) {
		return untypedConnected(program, null);
	}

	/**
	 * The {@link #untypedConnected(TLObject)} objects of the program in the given project only, in
	 * all projects for <code>null</code>.
	 */
	public static List<Interface> untypedConnected(TLObject program, TLObject project) {
		List<Interface> result = new ArrayList<>();
		for (Interface placed : placements(program)) {
			if (untypedConnected(placed, project)) {
				result.add(placed);
			}
		}
		return result;
	}

	/**
	 * Whether a device in the given project (in any project for <code>null</code>) connects the
	 * placed object to a group address although E-Home cannot determine its datapoint type, see
	 * {@link #untypedConnected(TLObject)}.
	 */
	public static boolean untypedConnected(Interface placed, TLObject project) {
		int size = ObjectTypes.size(placed);
		if (size == 1 || size == 4) {
			return false;
		}
		for (TLObject connector : placed.tReferers(reference("ehome.physical:Connector", "port"))) {
			if (connector == null || !connector.tValid() || connector.tContainer() == null
				|| (project != null && !within(connector, project))) {
				continue;
			}
			if (!((Collection<?>) connector.tValueByName("connections")).isEmpty()
				&& ObjectTypes.effective(connector) == null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The datapoint types offered for the placed object: those of its size, all if none has its size.
	 */
	public static List<TLObject> typeChoices(Interface placed) {
		if (placed == null) {
			return GroupAddressTypes.allTypes();
		}
		List<TLObject> result = GroupAddressTypes.ofSize(Integer.valueOf(ObjectTypes.size(placed)));
		return result.isEmpty() ? GroupAddressTypes.allTypes() : result;
	}

	private static boolean within(TLObject obj, TLObject ancestor) {
		for (TLObject current = obj; current != null; current = current.tContainer()) {
			if (current == ancestor) {
				return true;
			}
		}
		return false;
	}

	private static TLReference reference(String type, String name) {
		return (TLReference) ((TLClass) TLModelUtil.findType(type)).getPart(name);
	}

	private static TLObject create(String typeName) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
	}

}
