/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

/**
 * An argument of a message for the user that stands for a datapoint of the image, by its id
 * (haumacher/ehome #118).
 *
 * <p>
 * The loaders of the image (rules, schedule entries, virtual devices, the image itself) know only the
 * ids of the datapoints; they put the id of a datapoint into a message as this marker. Where the
 * message is displayed for a project, E-Home names the datapoint as <code>Name (id)</code>
 * (<code>de.haumacher.ehome.runtime.DatapointNames#names(ResKey)</code>, haumacher/ehome #113); a
 * message displayed without a project (the simulation's own loader) shows the id, the
 * {@link #toString()} of the marker.
 * </p>
 *
 * @param id
 *        The id of the datapoint in the image (<code>11/0/4</code>, <code>rule/licht/max</code>).
 */
public record DatapointId(String id) {

	/**
	 * The marker of the given id.
	 */
	public static DatapointId of(String id) {
		return new DatapointId(id);
	}

	/**
	 * The id: how a message names the datapoint where nobody names it otherwise.
	 */
	@Override
	public String toString() {
		return id;
	}

}
