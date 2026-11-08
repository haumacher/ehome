/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLFormObjectBase;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.runtime.ApplicationDatapoints;
import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.LabelLimit;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuntimeFunctions;
import de.haumacher.ehome.runtime.ValueTypes;

/**
 * The bindings of the functions of Zigbee devices to datapoints of the project (haumacher/ehome #40,
 * homecontrol <code>spec/zigbee.md</code>, "Bindings").
 *
 * <p>
 * A binding is an application datapoint (<code>ehome.runtime:ApplicationDatapoint</code>) whose
 * <code>zigbeeFunction</code> names the function: the image then carries the datapoint with a
 * <code>zigbee</code> member ({@link #json(TLObject)}) instead of <code>virtual</code>. A datapoint for
 * a function is created with {@link #createDatapoint(TLObject)} (its id derived from the device's
 * name and the function as the ids of functions are, {@link FunctionProposal#uniqueIdentifier}), or an
 * existing unbound datapoint whose value type fits is bound with {@link #bind(TLObject, TLObject)}.
 * Whether a binding is valid (the type fits, no function is bound twice, at most 512 bindings) is
 * checked with the image, as the controller checks it ({@link de.haumacher.ehome.runtime.ImageValidator}).
 * </p>
 */
public final class ZigbeeBindings {

	private ZigbeeBindings() {
		// Static helpers.
	}

	/**
	 * Whether the object is a function of a Zigbee device.
	 */
	public static boolean isFunction(TLObject obj) {
		return obj != null && "ZigbeeFunction".equals(obj.tType().getName())
			&& "ehome.zigbee".equals(obj.tType().getModule().getName());
	}

	/**
	 * The datapoints bound to the function.
	 */
	public static List<TLObject> boundDatapoints(TLObject function) {
		return ZigbeeImport.objects(function.tValueByName("datapoints"));
	}

	/**
	 * The function the application datapoint is bound to, <code>null</code> for none.
	 */
	public static TLObject function(TLObject datapoint) {
		return datapoint.tValueByName("zigbeeFunction") instanceof TLObject function && function.tValid() ? function
			: null;
	}

	/**
	 * The device of a function.
	 */
	public static TLObject device(TLObject function) {
		return function.tContainer();
	}

	/**
	 * The value type a function carries (the image's names), <code>null</code> if unknown.
	 */
	public static String valueType(TLObject function) {
		return function.tValueByName("valueType") instanceof TLClassifier type ? type.getName() : null;
	}

	/**
	 * Whether a datapoint of the given value type can be bound to a function carrying the other:
	 * the same type, or both numeric (the numeric types and the enumeration are interchangeable).
	 */
	public static boolean fits(String functionType, String datapointType) {
		return functionType == null || functionType.equals(datapointType)
			|| (ValueTypes.isNumeric(functionType) && ValueTypes.isNumeric(datapointType));
	}

	/**
	 * Why no datapoint can be created for the function or bound to it now, <code>null</code> if one
	 * can: it is bound already.
	 */
	public static ResKey bindVeto(TLObject function) {
		List<TLObject> bound = boundDatapoints(function);
		return bound.isEmpty() ? null : I18NConstants.VETO_BOUND__DATAPOINT.fill(DatapointNames.name(bound.get(0)));
	}

	/**
	 * Why the function cannot be unbound, <code>null</code> if it can: it is bound to no datapoint.
	 */
	public static ResKey unbindVeto(TLObject function) {
		return boundDatapoints(function).isEmpty() ? I18NConstants.VETO_UNBOUND : null;
	}

	/**
	 * Creates an application datapoint bound to the function, at the end of the project's application
	 * datapoints: its id derived from the device's name and the function, its label the device's
	 * name and the function, its type and unit the function's, writable where the function can be
	 * commanded. Must run in a transaction.
	 *
	 * <p>
	 * Where the device's type in the Zigbee catalog lists the function ({@link ZigbeeCatalog#entry(TLObject)}),
	 * the function is named by the catalog's name (the id in the user's language, the label in every
	 * language), the type and unit are the ones the catalog recommends, and the catalog's reporting
	 * recommendation becomes the function's override, unless the installer has set one.
	 * </p>
	 *
	 * @return The new datapoint.
	 */
	public static TLObject createDatapoint(TLObject function) {
		TLObject project = RuntimeFunctions.project(function);
		TLObject device = device(function);
		TLObject entry = ZigbeeCatalog.entry(function);
		String deviceName = String.valueOf(device.tValueByName("name"));
		ResKey friendly = entry != null && entry.tValueByName("name") instanceof ResKey key ? key : null;
		String name;
		ResKey label;
		if (friendly != null) {
			name = deviceName + " " + Resources.getInstance().getString(friendly);
			Map<String, String> texts = new TreeMap<>();
			for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
				texts.put(locale.getLanguage(), deviceName + " " + Resources.getInstance(locale).getString(friendly));
			}
			label = FunctionProposal.text(texts);
		} else {
			name = deviceName + " " + function.tValueByName("function");
			label = FunctionProposal.text(ResKey.text(name));
		}
		Set<String> ids = new HashSet<>();
		for (TLObject other : ApplicationDatapoints.of(project)) {
			String id = ApplicationDatapoints.id(other);
			if (id != null) {
				ids.add(id);
			}
		}
		TLObject datapoint = RuleFunctions.create(project, ApplicationDatapoints.REFERENCE,
			(TLClass) TLModelUtil.findType("ehome.runtime:" + ApplicationDatapoints.TYPE));
		datapoint.tUpdateByName("identifier", FunctionProposal.uniqueIdentifier(name, ids));
		// The controller shows at most 63 bytes (haumacher/ehome #116).
		datapoint.tUpdateByName("label", LabelLimit.fit(label));
		String type = ZigbeeCatalog.recommendedType(entry);
		if (type == null) {
			type = valueType(function);
		}
		if (type != null) {
			datapoint.tUpdateByName("type", ZigbeeImport.valueType(type));
		}
		String unit = ZigbeeCatalog.recommendedUnit(entry);
		datapoint.tUpdateByName("unit", unit != null ? unit : function.tValueByName("unit"));
		datapoint.tUpdateByName("writable", Boolean.valueOf(Boolean.TRUE.equals(function.tValueByName("writable"))));
		datapoint.tUpdateByName("zigbeeFunction", function);
		if (entry != null && function.tValueByName(ZigbeeReporting.MIN) == null
			&& function.tValueByName(ZigbeeReporting.MAX) == null
			&& function.tValueByName(ZigbeeReporting.CHANGE) == null) {
			for (String member : new String[] { ZigbeeReporting.MIN, ZigbeeReporting.MAX, ZigbeeReporting.CHANGE }) {
				Object value = entry.tValueByName(member);
				if (value != null) {
					function.tUpdateByName(member, value);
				}
			}
		}
		return datapoint;
	}

	/**
	 * The functions of the device "Create datapoints" creates datapoints for: those the controller
	 * still exports, bound to no datapoint, and not hidden by the device's type in the catalog.
	 */
	public static List<TLObject> datapointCandidates(TLObject device) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject function : ZigbeeImport.objects(device.tValueByName("functions"))) {
			if (!Boolean.TRUE.equals(function.tValueByName("absent")) && bindVeto(function) == null
				&& !ZigbeeCatalog.hidden(function)) {
				result.add(function);
			}
		}
		return result;
	}

	/**
	 * Why "Create datapoints" has nothing to do for the device, <code>null</code> if it has.
	 */
	public static ResKey createDatapointsVeto(TLObject device) {
		return datapointCandidates(device).isEmpty() ? I18NConstants.VETO_NOTHING_TO_CREATE : null;
	}

	/**
	 * Creates a datapoint ({@link #createDatapoint(TLObject)}) for every function of the device
	 * {@link #datapointCandidates(TLObject)} names. Must run in a transaction.
	 *
	 * @return The new datapoints, in the order of the functions.
	 */
	public static List<TLObject> createDatapoints(TLObject device) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject function : datapointCandidates(device)) {
			result.add(createDatapoint(function));
		}
		return result;
	}

	/**
	 * The sentence saying which datapoints {@link #createDatapoints(TLObject)} created.
	 */
	public static String createdSummary(List<TLObject> datapoints) {
		List<String> ids = new ArrayList<>();
		for (TLObject datapoint : datapoints) {
			ids.add(DatapointNames.name(datapoint));
		}
		return Resources.getInstance().getString(
			I18NConstants.DATAPOINTS_CREATED__COUNT_IDS.fill(Integer.valueOf(ids.size()), String.join(", ", ids)));
	}

	/**
	 * Binds the application datapoint to the function. Must run in a transaction.
	 *
	 * @throws TopLogicException
	 *         If the function is bound already, the datapoint is bound to another function, the types
	 *         do not fit or the two belong to different projects.
	 */
	public static TLObject bind(TLObject function, TLObject datapoint) {
		ResKey veto = bindVeto(function);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		TLObject other = function(datapoint);
		if (other != null && other != function) {
			throw new TopLogicException(I18NConstants.ERROR_BIND_TAKEN__DATAPOINT.fill(DatapointNames.name(datapoint)));
		}
		if (RuntimeFunctions.project(function) != RuntimeFunctions.project(datapoint)) {
			throw new TopLogicException(I18NConstants.ERROR_BIND_OTHER_PROJECT);
		}
		String type = ApplicationDatapoints.valueType(datapoint);
		if (!fits(valueType(function), type)) {
			throw new TopLogicException(I18NConstants.ERROR_BIND_TYPE__FUNCTION_TYPE_DATAPOINT_DATAPOINT_TYPE.fill(
				function.tValueByName("function"), valueType(function), DatapointNames.name(datapoint), type));
		}
		datapoint.tUpdateByName("zigbeeFunction", function);
		return datapoint;
	}

	/**
	 * Removes the bindings of the function: its datapoints stay, as datapoints of the application.
	 * Must run in a transaction.
	 */
	public static void unbind(TLObject function) {
		for (TLObject datapoint : boundDatapoints(function)) {
			datapoint.tUpdateByName("zigbeeFunction", null);
		}
	}

	/**
	 * The application datapoints of the function's project that can be bound to it: bound to no
	 * function, of a value type that fits.
	 */
	public static List<TLObject> candidates(TLObject function) {
		List<TLObject> result = new ArrayList<>();
		TLObject project = RuntimeFunctions.project(function);
		if (project == null) {
			return result;
		}
		String type = valueType(function);
		for (TLObject datapoint : ApplicationDatapoints.of(project)) {
			if (function(datapoint) == null && fits(type, ApplicationDatapoints.valueType(datapoint))) {
				result.add(datapoint);
			}
		}
		return result;
	}

	/**
	 * The functions an application datapoint may be bound to, the options of its field: the functions
	 * of the Zigbee devices of its project bound to no other datapoint whose value type fits its own.
	 *
	 * @param obj
	 *        The datapoint, in a form the form's object.
	 */
	public static List<TLObject> functionOptions(Object obj) {
		TLObject form = obj instanceof TLObject o ? o : null;
		TLObject self = obj instanceof TLFormObjectBase edit ? edit.getEditedObject() : form;
		List<TLObject> result = new ArrayList<>();
		if (self == null || !self.tValid()) {
			return result;
		}
		TLObject project = RuntimeFunctions.project(self);
		if (project == null) {
			return result;
		}
		String type = form.tValueByName("type") instanceof TLClassifier classifier ? classifier.getName() : null;
		for (TLObject device : ZigbeeImport.devices(project)) {
			for (TLObject function : ZigbeeImport.objects(device.tValueByName("functions"))) {
				List<TLObject> bound = boundDatapoints(function);
				boolean free = bound.isEmpty() || (bound.size() == 1 && bound.get(0) == self);
				if (free && (type == null || fits(valueType(function), type))) {
					result.add(function);
				}
			}
		}
		return result;
	}

	/**
	 * The <code>zigbee</code> member of the datapoint bound to the function in the image: the device's
	 * IEEE address, the endpoint, the cluster and the attribute, or for the commands of a client
	 * cluster (<code>action</code>, no attribute) the client binding; the installer's reporting
	 * override as <code>reporting</code> with the members set, no member at all without one
	 * ({@link ZigbeeReporting#json(TLObject)}).
	 */
	public static Map<String, Object> json(TLObject function) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ieee", String.valueOf(device(function).tValueByName("ieee")));
		result.put("endpoint", number(function.tValueByName("endpoint")));
		result.put("cluster", number(function.tValueByName("cluster")));
		Object attribute = function.tValueByName("attribute");
		if (attribute instanceof Number) {
			result.put("attribute", number(attribute));
		} else {
			result.put("client", Boolean.TRUE);
		}
		Map<String, Object> reporting = ZigbeeReporting.json(function);
		if (reporting != null) {
			result.put("reporting", reporting);
		}
		return result;
	}

	/**
	 * Whether the controller reads the value of the function after a start: a server attribute,
	 * except the multistate input (homecontrol <code>ZigbeePoint::flags()</code>).
	 */
	public static boolean readOnStart(TLObject function) {
		Object attribute = function.tValueByName("attribute");
		return attribute instanceof Number && !ZigbeeFunctionTable.MULTISTATE.equals(function.tValueByName("function"));
	}

	private static Long number(Object value) {
		return value instanceof Number number ? Long.valueOf(number.longValue()) : null;
	}

}
