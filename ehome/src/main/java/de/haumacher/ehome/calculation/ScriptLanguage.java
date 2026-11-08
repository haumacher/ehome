/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.calculation;

import java.util.Map;

/**
 * A language the scripts of a parameter calculation are written in (ETS:
 * <code>ParameterCalculation@Language</code>).
 *
 * <p>
 * A transformation is given in one of two forms:
 * </p>
 * <ul>
 * <li>Inline (ETS: the elements <code>LRTransformation</code>, <code>RLTransformation</code>, the form
 * of the programs in the field): statements reading and assigning the parameters of the calculation as
 * variables ({@link #run(String, String, Map)}).</li>
 * <li>A function of the program's script (ETS: the attributes <code>LRTransformationFunc</code>,
 * <code>RLTransformationFunc</code> with <code>…Parameters</code>), called with the parameters of the
 * source side, those of the target side and the parameters of the transformation
 * ({@link #call(String, String, Map, Map, String)}).</li>
 * </ul>
 *
 * <p>
 * The library (ETS: <code>Static/Script</code> of the program and of a module definition) holds
 * functions both forms may call; it is loaded first. Code runs without any access to the host: no
 * files, no network, no Java, and with a limit of its run time.
 * </p>
 */
public interface ScriptLanguage {

	/**
	 * Why the given inline code cannot be evaluated (a syntax error, a construct not supported, a
	 * function neither the language nor the library defines), as a person reads it; <code>null</code>
	 * if it can.
	 *
	 * @param library
	 *        The functions of the program, <code>null</code> for none.
	 */
	String check(String library, String code);

	/**
	 * Why the function of the given name cannot be called, <code>null</code> if it can.
	 */
	String checkFunction(String library, String function);

	/**
	 * Runs the given inline code.
	 *
	 * @param library
	 *        Functions of the program the code may call, loaded first; <code>null</code> for none.
	 * @param code
	 *        The code of the transformation.
	 * @param variables
	 *        The variables by their names, with their values: a {@link Double} for a number, a
	 *        {@link String} for a text.
	 * @return The values of the given variables after the run, by the same names: a {@link Number}, a
	 *         {@link String} or a {@link Boolean}.
	 * @throws CalculationException
	 *         If the code cannot be run or fails.
	 */
	Map<String, Object> run(String library, String code, Map<String, Object> variables) throws CalculationException;

	/**
	 * Calls a function of the library as a transformation: <code>function(input, output, context)</code>.
	 *
	 * @param inputs
	 *        The parameters of the source side by their names (the members of <code>input</code>).
	 * @param outputs
	 *        The parameters of the target side by their names with their current values (the members of
	 *        <code>output</code>, which the function sets).
	 * @param parameters
	 *        The parameters of the transformation (ETS: <code>…TransformationParameters</code>), as
	 *        <code>context</code>: the value of a JSON text, else the text; <code>null</code> for none.
	 * @return The members of <code>output</code> after the call, by the names of the outputs.
	 */
	Map<String, Object> call(String library, String function, Map<String, Object> inputs, Map<String, Object> outputs,
			String parameters) throws CalculationException;

}
