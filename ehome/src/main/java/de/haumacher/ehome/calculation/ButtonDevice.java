/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.calculation;

/**
 * What the handler of a button of the parameter dialog (ETS 6: <code>Button@EventHandler</code>,
 * haumacher/ehome #158) reads and sets of the device it runs for: the object <code>device</code> of
 * <code>function(device, online, progress, context)</code>, see
 * {@link JavaScriptLanguage#handle(String, String, ButtonDevice, String)}.
 *
 * <p>
 * A parameter is an opaque key the implementation hands out and takes back.
 * </p>
 */
public interface ButtonDevice {

	/**
	 * The parameter of the given ID, as the program writes it (ETS:
	 * <code>device.GetParameterById(id)</code>); <code>null</code> if the device has none.
	 *
	 * @throws CalculationException
	 *         With the reason, if the parameter cannot be given to the handler.
	 */
	Object parameterById(String id) throws CalculationException;

	/**
	 * The parameter of the given name (ETS: <code>device.getParameterByName(name)</code>);
	 * <code>null</code> if the device has none.
	 *
	 * @throws CalculationException
	 *         With the reason, if the parameter cannot be given to the handler.
	 */
	Object parameterByName(String name) throws CalculationException;

	/**
	 * The current value of the given parameter as a script reads it: a {@link Double} for a number, a
	 * time or the entry of an enumeration (its value), a {@link String} for a text.
	 */
	Object value(Object parameter);

	/**
	 * Sets the value of the given parameter (ETS: <code>parameter.Value = …</code>): a {@link Number},
	 * a {@link String}, a {@link Boolean} or <code>null</code>, as the script gives it.
	 */
	void setValue(Object parameter, Object value);

}
