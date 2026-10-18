/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.exporter;

import static de.haumacher.ehome.exporter.Values.list;
import static de.haumacher.ehome.exporter.Values.ref;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.top_logic.model.TLObject;

/**
 * The shared data a project uses, which its export writes besides the project: the master data
 * (datapoint types, mask versions, manufacturers) and per manufacturer the products, program
 * assignments, application programs and catalog sections.
 */
final class Usage {

	final List<TLObject> _devices = new ArrayList<>();

	final Set<TLObject> _variants = new LinkedHashSet<>();

	final Set<TLObject> _assignments = new LinkedHashSet<>();

	final Set<TLObject> _products = new LinkedHashSet<>();

	final Set<TLObject> _programs = new LinkedHashSet<>();

	/** The manufacturers with data of their own in the export (a folder M-xxxx). */
	final Set<TLObject> _manufacturers = new LinkedHashSet<>();

	/** The manufacturers named in the master data: those with data and those referenced otherwise. */
	final Set<TLObject> _masterManufacturers = new LinkedHashSet<>();

	final Set<TLObject> _masks = new LinkedHashSet<>();

	/** The main datapoint types of all datapoint types used. */
	final Set<TLObject> _datapointTypes = new LinkedHashSet<>();

	/**
	 * Collects what the given project (<code>ehome.project:Project</code>) uses.
	 */
	Usage(TLObject project) {
		for (TLObject area : list(project, "areas")) {
			for (TLObject line : list(area, "lines")) {
				_devices.addAll(list(line, "devices"));
			}
		}
		_devices.addAll(list(project, "unassignedDevices"));
		for (TLObject device : _devices) {
			TLObject variant = ref(device, "type");
			if (variant != null) {
				_variants.add(variant);
				addProduct(ref(variant, "product"));
			}
			TLObject assignment = ref(device, "program");
			if (assignment != null) {
				_assignments.add(assignment);
				addProduct(ref(assignment, "product"));
				TLObject program = ref(assignment, "program");
				if (program != null) {
					_programs.add(program);
				}
			}
			for (TLObject connector : list(device, "connectors")) {
				addDatapointTypes(connector);
			}
		}
		for (TLObject range : list(project, "groupAddresses")) {
			addGroupDatapointTypes(range);
		}
		for (TLObject program : _programs) {
			TLObject manufacturer = ref(program, "manufacturer");
			if (manufacturer != null) {
				_manufacturers.add(manufacturer);
			}
			TLObject original = ref(program, "originalManufacturer");
			if (original != null) {
				_masterManufacturers.add(original);
			}
			addMask(ref(program, "mask"));
			for (TLObject port : list(program, "ports")) {
				addDatapointTypes(port);
				for (TLObject reference : list(port, "interfaces")) {
					addDatapointTypes(reference);
				}
			}
		}
		_masterManufacturers.addAll(_manufacturers);
	}

	private void addProduct(TLObject product) {
		if (product != null && _products.add(product)) {
			TLObject manufacturer = product.tContainer();
			if (manufacturer != null) {
				_manufacturers.add(manufacturer);
			}
		}
	}

	private void addMask(TLObject mask) {
		if (mask != null && _masks.add(mask)) {
			for (TLObject compatible : list(mask, "compatibleMasks")) {
				addMask(compatible);
			}
		}
	}

	private void addGroupDatapointTypes(TLObject definition) {
		if (definition.tType().getPart("datapointTypes") != null) {
			addDatapointTypes(definition);
		}
		if (definition.tType().getPart("parts") != null) {
			for (TLObject part : list(definition, "parts")) {
				addGroupDatapointTypes(part);
			}
		}
	}

	private void addDatapointTypes(TLObject obj) {
		for (TLObject type : list(obj, "datapointTypes")) {
			TLObject main = type.tType().getPart("mainType") != null ? ref(type, "mainType") : type;
			if (main != null) {
				_datapointTypes.add(main);
			}
		}
	}

	/**
	 * The used products of the given manufacturer.
	 */
	List<TLObject> products(TLObject manufacturer) {
		return Values.sortedById(_products.stream().filter(p -> p.tContainer() == manufacturer).toList());
	}

	/**
	 * The used programs of the given manufacturer.
	 */
	List<TLObject> programs(TLObject manufacturer) {
		return Values.sortedById(_programs.stream().filter(p -> ref(p, "manufacturer") == manufacturer).toList());
	}

}
