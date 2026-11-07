/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.template;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Port;

/**
 * The datapoint type in effect at a communication object (haumacher/ehome #103): the one place of
 * the chain that every reader of the type shares - the derived attributes of the model
 * (<code>ehome.physical:Connector#inheritedDatapointTypes</code>, <code>#effectiveDatapointType</code>,
 * <code>ehome.template:ObjectOverlay#manufacturerDatapointTypes</code>, through the TL-Script functions
 * of {@link TemplateFunctions}) and the Java readers (the group addresses, the bind dialog, the
 * runtime image, the simulation, the function proposal).
 *
 * <p>
 * The chain, the first that names a type:
 * </p>
 * <ol>
 * <li>the type entered in the project at the device's object
 * (<code>ehome.physical:Connector#datapointTypes</code>, read from and written to the project
 * file);</li>
 * <li>the type of the object's overlay in the template of the program
 * (<code>ehome.template:ObjectOverlay#datapointTypes</code>, E-Home's own data);</li>
 * <li>the type of the manufacturer's placement of the object
 * (<code>ehome.operational:Interface#datapointTypes</code>);</li>
 * <li>the type of the manufacturer's object (<code>ehome.operational:Port#datapointTypes</code>).</li>
 * </ol>
 *
 * <p>
 * Nothing is copied: a type entered in a template applies to every device of the program at once,
 * in every project.
 * </p>
 */
public final class ObjectTypes {

	private static final String DATAPOINT_TYPES = "datapointTypes";

	private ObjectTypes() {
		// Static only.
	}

	/**
	 * The datapoint types the manufacturer gives the placed object: the placement's, else the
	 * program object's (steps 3 and 4 of the chain).
	 */
	public static List<TLObject> manufacturer(Interface placed) {
		if (placed == null) {
			return new ArrayList<>();
		}
		List<TLObject> result = list(placed);
		if (result.isEmpty()) {
			Port port = placed.getPort();
			if (port != null) {
				result = list(port);
			}
		}
		return result;
	}

	/**
	 * The datapoint types a device's object inherits unless a type is entered in the project: the
	 * template's, else the manufacturer's (steps 2 to 4 of the chain).
	 */
	public static List<TLObject> inherited(Interface placed) {
		TLObject overlay = overlay(placed);
		if (overlay != null) {
			List<TLObject> own = list(overlay);
			if (!own.isEmpty()) {
				return own;
			}
		}
		return manufacturer(placed);
	}

	/**
	 * The datapoint type in effect at a connector of a device (<code>ehome.physical:Connector</code>):
	 * the one entered in the project, else the inherited one; <code>null</code> if none of them
	 * names one.
	 */
	public static TLObject effective(TLObject connector) {
		if (connector == null) {
			return null;
		}
		TLObject own = first(list(connector));
		if (own != null) {
			return own;
		}
		return connector.tValueByName("port") instanceof Interface placed ? first(inherited(placed)) : null;
	}

	/**
	 * The datapoint type in effect at the given object of a device, also for an object the project
	 * holds no connector of (then the inherited one).
	 */
	public static TLObject effective(TLObject device, Interface placed) {
		TLObject connector = connector(device, placed);
		return connector != null ? effective(connector) : first(inherited(placed));
	}

	/**
	 * The connector of the device for the given placed object (for one of its definition), <code>null</code> if
	 * the project holds none.
	 */
	public static TLObject connector(TLObject device, Interface placed) {
		if (placed == null || !ExtIds.isA(device, "ehome.physical:Device")) {
			return null;
		}
		TLObject fallback = null;
		for (Object element : (Collection<?>) device.tValueByName("connectors")) {
			TLObject connector = (TLObject) element;
			if (connector.tValueByName("port") instanceof Interface other) {
				if (other == placed) {
					return connector;
				}
				if (fallback == null && other.getPort() == placed.getPort()) {
					fallback = connector;
				}
			}
		}
		return fallback;
	}

	/**
	 * The overlay of the placed object in the template of its program, <code>null</code> if the
	 * program has no template.
	 */
	public static TLObject overlay(Interface placed) {
		if (placed == null) {
			return null;
		}
		for (TLObject overlay : placed.tReferers(placementReference())) {
			if (overlay != null && overlay.tValid() && overlay.tContainer() != null) {
				return overlay;
			}
		}
		return null;
	}

	/**
	 * The size of the placed object in bits, as in effect at its placement.
	 */
	public static int size(Interface placed) {
		Integer size = placed.getSize();
		return size != null ? size.intValue() : placed.getPort().getSize();
	}

	private static TLReference placementReference() {
		return (TLReference) TLModelUtil.findPart("ehome.template:ObjectOverlay#placement");
	}

	private static List<TLObject> list(TLObject holder) {
		List<TLObject> result = new ArrayList<>();
		if (holder.tValueByName(DATAPOINT_TYPES) instanceof Collection<?> types) {
			for (Object type : types) {
				if (type instanceof TLObject obj) {
					result.add(obj);
				}
			}
		}
		return result;
	}

	private static TLObject first(List<TLObject> types) {
		return types.isEmpty() ? null : types.get(0);
	}

}
