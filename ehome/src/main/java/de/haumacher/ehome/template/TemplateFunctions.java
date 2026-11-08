/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.template;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.modules.ModuleParts;

/**
 * The TL-Script functions of the device templates and of the datapoint type in effect at a
 * communication object (haumacher/ehome #103), see {@link ObjectTypes} and {@link DeviceTemplates}.
 *
 * <p>
 * Each function is registered in <code>ehomeConf.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class. The derived attributes of the
 * model call the reading ones, so that the chain of the type in effect is computed in Java only.
 * </p>
 */
public class TemplateFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/**
		 * <code>placementManufacturerTypes(placement)</code>:
		 * {@link ObjectTypes#manufacturer(Interface)}.
		 */
		placementManufacturerTypes(1, true),
		/** <code>placementInheritedTypes(placement)</code>: {@link ObjectTypes#inherited(Interface)}. */
		placementInheritedTypes(1, true),
		/** <code>placementTypeChoices(placement)</code>: {@link DeviceTemplates#typeChoices(Interface)}. */
		placementTypeChoices(1, true),
		/**
		 * <code>placementUntypedConnected(placement)</code>:
		 * {@link DeviceTemplates#untypedConnected(Interface, TLObject)} in any project.
		 */
		placementUntypedConnected(1, true),
		/** <code>connectorDatapointType(connector)</code>: {@link ObjectTypes#effective(TLObject)}. */
		connectorDatapointType(1, true),
		/**
		 * <code>deviceObjectType(device, placement)</code>:
		 * {@link ObjectTypes#effective(TLObject, Interface)}.
		 */
		deviceObjectType(2, true),
		/**
		 * <code>deviceObjectConnector(device, placement)</code>:
		 * {@link ObjectTypes#connector(TLObject, Interface)}.
		 */
		deviceObjectConnector(2, true),
		/**
		 * <code>deviceObjectConnectorCreate(device, placement)</code>: the connector of the device for
		 * the object, created if the project has none, so that a datapoint type can be entered for it.
		 */
		deviceObjectConnectorCreate(2, false),
		/** <code>templateOf(program)</code>: {@link DeviceTemplates#template(TLObject)}. */
		templateOf(1, true),
		/** <code>templateCreate(program)</code>: {@link DeviceTemplates#create(TLObject)}. */
		templateCreate(1, false),
		/** <code>templateProducts(program)</code>: {@link DeviceTemplates#products(TLObject)}. */
		templateProducts(1, true),
		/**
		 * <code>templateUntypedObjects(program)</code>:
		 * {@link DeviceTemplates#untypedConnected(TLObject)}.
		 */
		templateUntypedObjects(1, true);

		private final int _arguments;

		private final boolean _sideEffectFree;

		Function(int arguments, boolean sideEffectFree) {
			_arguments = arguments;
			_sideEffectFree = sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link TemplateFunctions}.
	 */
	protected TemplateFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new TemplateFunctions(getName(), _function, arguments);
	}

	@Override
	public Object getId() {
		return _function;
	}

	@Override
	public TLType getType(List<TLType> argumentTypes) {
		return null;
	}

	@Override
	public boolean isSideEffectFree() {
		return _function._sideEffectFree;
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		switch (_function) {
			case placementManufacturerTypes:
				return ObjectTypes.manufacturer(placement(arguments[0]));
			case placementInheritedTypes:
				return ObjectTypes.inherited(placement(arguments[0]));
			case placementTypeChoices:
				return DeviceTemplates.typeChoices(placement(arguments[0]));
			case placementUntypedConnected:
				return Boolean.valueOf(placement(arguments[0]) != null
					&& DeviceTemplates.untypedConnected(placement(arguments[0]), null));
			case connectorDatapointType:
				return ObjectTypes.effective(object(arguments[0]));
			case deviceObjectType:
				return placement(arguments[1]) == null ? null
					: ObjectTypes.effective(object(arguments[0]), placement(arguments[1]));
			case deviceObjectConnector:
				return ObjectTypes.connector(object(arguments[0]), placement(arguments[1]));
			case deviceObjectConnectorCreate:
				return createConnector(object(arguments[0]), placement(arguments[1]));
			case templateOf:
				return DeviceTemplates.template(object(arguments[0]));
			case templateCreate:
				return DeviceTemplates.create(object(arguments[0]));
			case templateProducts:
				return DeviceTemplates.products(object(arguments[0]));
			case templateUntypedObjects:
				return object(arguments[0]) == null ? List.of() : DeviceTemplates.untypedConnected(object(arguments[0]));
		}
		return null;
	}

	private static TLObject createConnector(TLObject device, Interface placed) {
		if (!ExtIds.isA(device, "ehome.physical:Device") || placed == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_DEVICE_OBJECT);
		}
		TLObject existing = ObjectTypes.connector(device, placed);
		if (existing != null) {
			return existing;
		}
		TLObject connector = ModelService.getInstance().getFactory()
			.createObject((TLClass) TLModelUtil.findType("ehome.physical:Connector"));
		ModuleParts.bind(connector, ModuleParts.PORT, placed);
		List<Object> connectors = new ArrayList<>((Collection<?>) device.tValueByName("connectors"));
		connectors.add(connector);
		device.tUpdateByName("connectors", connectors);
		return connector;
	}

	private static Interface placement(Object value) {
		return value instanceof Interface placed && placed.tValid() ? placed : null;
	}

	private static TLObject object(Object value) {
		return value instanceof TLObject obj && obj.tValid() ? obj : null;
	}

	/**
	 * Builder of a {@link TemplateFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<TemplateFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public TemplateFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._arguments, _function._arguments);
			return new TemplateFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
