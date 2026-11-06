/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.stream.Location;

import com.top_logic.basic.col.TypedAnnotatable;
import com.top_logic.basic.col.TypedAnnotatable.Property;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.xml.log.XMLStreamLog;
import com.top_logic.model.TLClassifier;
import com.top_logic.xio.importer.binding.ImportContext;

import de.haumacher.ehome.model.operational.AccessKind;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;

/**
 * The warnings about values of an application program that do not fit their type, held back until
 * the access of their parameter is known (haumacher/ehome #83).
 *
 * <p>
 * A value of the program itself (the default of a <code>Parameter</code> or a
 * <code>ParameterRef</code>) that does not fit its type is kept as text, but reported only if the
 * parameter is visible: a hidden parameter (access <code>None</code>) is never shown or edited, ETS
 * writes and downloads its value as it stands, and the installer can do nothing about it. The access
 * in effect is that of the <code>ParameterRef</code>, otherwise that of the <code>Parameter</code>,
 * otherwise <code>ReadWrite</code> (the default of ETS).
 * </p>
 *
 * <p>
 * The value of a <code>ParameterRef</code> is decided at once: its access and that of its parameter
 * are read before its value. The value of a <code>Parameter</code> is shown through its references,
 * which the program defines after its parameters; its warning is therefore held back here and
 * reported at the end of the program ({@link #report(XMLStreamLog)}, called by
 * {@link ProgramImported}) if a reference of the parameter is visible, or, for a parameter that
 * no reference places, if the parameter itself is. A warning is so reported once or not at all, never
 * withdrawn.
 * </p>
 *
 * @see TypedValueImport
 */
public class HiddenProgramValues {

	/**
	 * The {@link HiddenProgramValues} of an import in its {@link ImportContext}.
	 */
	public static final Property<HiddenProgramValues> PROPERTY =
		TypedAnnotatable.property(HiddenProgramValues.class, "hiddenProgramValues");

	/**
	 * A warning about the value of a parameter, and what the references read so far tell about its
	 * visibility.
	 */
	private static final class Pending {

		final Location _position;

		final ResKey _message;

		boolean _referenced;

		boolean _visible;

		Pending(Location position, ResKey message) {
			_position = position;
			_message = message;
		}

	}

	private final Map<Parameter, Pending> _pending = new LinkedHashMap<>();

	/**
	 * The {@link HiddenProgramValues} of the given import, created on first use.
	 */
	public static HiddenProgramValues get(ImportContext context) {
		HiddenProgramValues result = context.get(PROPERTY);
		if (result == null) {
			result = new HiddenProgramValues();
			context.set(PROPERTY, result);
		}
		return result;
	}

	/**
	 * Reports the warnings held back in the given import, if any, see {@link #report(XMLStreamLog)}.
	 */
	public static void reportPending(ImportContext context) {
		HiddenProgramValues values = context.get(PROPERTY);
		if (values != null) {
			values.report(context);
		}
	}

	/**
	 * Holds back the warning about the value of the given parameter until its references are read.
	 *
	 * @param position
	 *        Where the program defines the value; a fixed copy, the reader changes its location as it
	 *        reads on.
	 */
	public void defer(Parameter parameter, Location position, ResKey message) {
		_pending.put(parameter, new Pending(position, message));
	}

	/**
	 * Records a reference of the program: if it places a parameter whose warning is held back, the
	 * warning is reported if this or another reference of the parameter is visible.
	 */
	public void referenced(ParameterView view) {
		Pending pending = _pending.get(view.getParameter());
		if (pending != null) {
			pending._referenced = true;
			pending._visible |= isVisible(view);
		}
	}

	/**
	 * Reports the warnings held back about parameters that are visible, and forgets all held back.
	 */
	public void report(XMLStreamLog log) {
		for (Map.Entry<Parameter, Pending> entry : _pending.entrySet()) {
			Pending pending = entry.getValue();
			boolean visible = pending._referenced ? pending._visible : isVisible(entry.getKey().getAccess());
			if (visible) {
				log.log(Level.WARN, pending._position, pending._message);
			}
		}
		_pending.clear();
	}

	/**
	 * Whether the parameter the given reference places is visible there: the access of the
	 * reference, otherwise that of the parameter, is not <code>None</code>.
	 */
	public static boolean isVisible(ParameterView view) {
		TLClassifier access = view.getAccess();
		if (access == null && view.getParameter() != null) {
			access = view.getParameter().getAccess();
		}
		return isVisible(access);
	}

	private static boolean isVisible(TLClassifier access) {
		return access == null || !AccessKind.NONE_CLASSIFIER.equals(access.getName());
	}

}
