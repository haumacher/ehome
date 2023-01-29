/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package test.de.haumacher.ehome.importer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.xml.stream.XMLStreamException;

import junit.framework.Test;
import junit.framework.TestCase;

import test.com.top_logic.ModuleLicenceTestSetup;

import com.top_logic.basic.UnreachableAssertion;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.model.search.expr.config.ExprPrinter;
import com.top_logic.model.search.expr.config.dom.Expr;

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.importer.binding.ModelBinding;

/**
 * Test case for {@link Importer}.
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class TestImporter extends TestCase {

	public void testImport() throws ConfigurationException, IOException, XMLStreamException {
		new Importer(objectHandler()).importStream(getClass().getResourceAsStream("Hebenstreit-16.knxproj"));
	}

	private ModelBinding objectHandler() {
		return new ModelBinding() {
			private Map<String, Obj> _ids = new HashMap<>();

			private int _nextId = 0;

			@Override
			public void setProperty(Object obj, String property, Object applicationValue) {
				System.out.println(id(obj) + "." + property + " = " + applicationValue);
			}

			private String id(Object obj) {
				return obj(obj).getId();
			}

			private Obj obj(Object obj) {
				return (Obj) obj;
			}

			@Override
			public Object resolveObject(String id) {
				return _ids.get(id);
			}

			@Override
			public void setReference(Object obj, String reference, Object target) {
				System.out.println(id(obj) + "." + reference + " = " + id(target));
			}

			@Override
			public Object createObject(String modelType, String id) {
				String name;
				if (id == null) {
					name = newId();
				} else {
					name = id;
				}
				System.out.println(name + " = " + "new " + modelType);
				Obj obj = new Obj(name, modelType);
				if (id != null) {
					assignId(obj, id);
				}
				return obj;
			}

			@Override
			public void assignId(Object value, String id) {
				_ids.put(id, (Obj) value);
			}

			private String newId() {
				return "$" + (_nextId++);
			}

			@Override
			public boolean isInstanceOf(Object obj, String type) {
				return type.equals(obj(obj).getType());
			}

			@Override
			public void addValue(Object obj, String part, Object element) {
				System.out.println(id(obj) + "." + part + " += " + id(element));
			}

			@Override
			public Object eval(Expr expr, Object... args) {
				try {
					return expr.visit(ExprPrinter.INSTANCE, new StringBuilder()).toString();
				} catch (IOException ex) {
					throw new UnreachableAssertion(ex);
				}
			}

			class Obj {

				private String _type;

				private String _id;

				/**
				 * Creates a {@link Obj}.
				 *
				 * @param id
				 * @param type
				 */
				public Obj(String id, String type) {
					_id = id;
					_type = type;
				}

				/**
				 * TODO
				 */
				public String getType() {
					return _type;
				}

				/**
				 * TODO
				 */
				public String getId() {
					return _id;
				}

			}
		};
	}

	public static Test suite() {
		return ModuleLicenceTestSetup.setupModule(TestImporter.class);
	}

}
