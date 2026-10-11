/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.model.operational.LoadProcedure;
import de.haumacher.ehome.model.operational.LoadStep;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.ParameterEncoding;

/**
 * Matches a device found on the bus ({@link FoundDevice}) against the catalog: which application
 * program it runs and which product it is.
 *
 * <ul>
 * <li>The manufacturer by its KNX ID (<code>ehome.catalog:Manufacturer#id</code>, else the ID in its
 * external ID <code>M-xxxx</code>).</li>
 * <li>The program by manufacturer, application number and version, the program version the device
 * reports; several programs differing in the hash part of their ID are all candidates. A program for
 * another mask version than the device's descriptor is no candidate, nor one whose load procedure
 * compares the hardware type (property 78) with another value than the device reports.</li>
 * <li>The product by the order info the device reports, where it starts with the order number of a
 * variant (<code>ehome.catalog:Variant#orderNumber</code>); otherwise every variant of a product
 * carrying the program (<code>ehome.catalog:ProgramAssignment</code>).</li>
 * <li>A device without program version (a gateway read through KNXnet/IP local device management)
 * by manufacturer and order info alone: the programs the products of the variants carry.</li>
 * </ul>
 *
 * <p>
 * Without a candidate the device is unidentified: its program (or manufacturer) is not in the
 * catalog, and it can be matched again after an import of product data.
 * </p>
 */
public class CatalogMatch {

	/**
	 * The kind of a match.
	 */
	public enum Kind {
		/** Exactly one candidate. */
		UNIQUE,

		/** Several candidates, the user chooses. */
		CHOICE,

		/** No candidate. */
		UNIDENTIFIED;
	}

	/**
	 * A possible identity of a device: a program and a product variant carrying it.
	 *
	 * @param program
	 *        The application program.
	 * @param assignment
	 *        The assignment of the program to the product (<code>ehome.catalog:ProgramAssignment</code>).
	 * @param variant
	 *        The variant of the product (<code>ehome.catalog:Variant</code>).
	 */
	public record Candidate(Program program, TLObject assignment, TLObject variant) {

		/**
		 * The candidate for a person: product, order number and program ID.
		 */
		public String label() {
			Object orderNumber = variant.tValueByName("orderNumber");
			TLObject product = variant.tContainer();
			Object productName = product == null ? null : product.tValueByName("name");
			return (productName == null ? "?" : productName) + (orderNumber == null ? "" : " (" + orderNumber + ")")
				+ ", " + program.getExtId();
		}
	}

	/**
	 * The outcome of matching a device.
	 *
	 * @param kind
	 *        Unique, a choice, or unidentified.
	 * @param candidates
	 *        The candidates.
	 * @param reason
	 *        For an unidentified device why, for a person; otherwise <code>null</code>.
	 */
	public record Match(Kind kind, List<Candidate> candidates, String reason) {

		/**
		 * The match for a person.
		 */
		public String describe() {
			switch (kind) {
				case UNIQUE:
					return candidates.get(0).label();
				case CHOICE:
					return candidates.size() + " candidates";
				default:
					return reason;
			}
		}
	}

	/**
	 * Matches a device against the catalog.
	 */
	public static Match match(FoundDevice device) {
		Integer manufacturerId = device.programManufacturer();
		if (manufacturerId == null) {
			return byOrderInfo(device);
		}
		TLObject manufacturer = manufacturer(manufacturerId.intValue());
		if (manufacturer == null) {
			return new Match(Kind.UNIDENTIFIED, List.of(),
				String.format("The manufacturer M-%04X is not in the catalog.", manufacturerId));
		}
		List<Program> programs = new ArrayList<>();
		List<String> excluded = new ArrayList<>();
		for (Object element : (Collection<?>) manufacturer.tValueByName("programs")) {
			if (!(element instanceof Program program)) {
				continue;
			}
			if (!Objects.equals(program.getApplicationNumber(), device.applicationNumber())
				|| !Objects.equals(program.getApplicationVersion(), device.applicationVersion())) {
				continue;
			}
			if (device.descriptor() != null && program.getMask() != null
				&& program.getMask().getNumber() != device.descriptor().intValue()) {
				excluded.add(program.getExtId() + " is for mask " + String.format("%04X", program.getMask().getNumber()));
				continue;
			}
			byte[] hardware = comparedHardwareType(program);
			if (hardware != null && device.hardwareType() != null && !samePadded(hardware, device.hardwareType())) {
				excluded.add(program.getExtId() + " expects the hardware type " + FoundDevice.hex(hardware));
				continue;
			}
			programs.add(program);
		}
		if (programs.isEmpty()) {
			String reason = String.format("No program %04X version %02X of M-%04X in the catalog.",
				device.applicationNumber(), device.applicationVersion(), manufacturerId);
			if (!excluded.isEmpty()) {
				reason += " " + String.join("; ", excluded) + ".";
			}
			return new Match(Kind.UNIDENTIFIED, List.of(), reason);
		}

		List<Candidate> candidates = new ArrayList<>();
		for (Object product : (Collection<?>) manufacturer.tValueByName("products")) {
			TLObject hardware = (TLObject) product;
			for (Object assignment : (Collection<?>) hardware.tValueByName("programs")) {
				TLObject programAssignment = (TLObject) assignment;
				if (!programs.contains(programAssignment.tValueByName("program"))) {
					continue;
				}
				for (Object variant : (Collection<?>) hardware.tValueByName("variants")) {
					candidates.add(new Candidate((Program) programAssignment.tValueByName("program"), programAssignment,
						(TLObject) variant));
				}
			}
		}
		if (candidates.isEmpty()) {
			return new Match(Kind.UNIDENTIFIED, List.of(), "No product of the catalog carries the program "
				+ programs.get(0).getExtId() + ".");
		}
		if (device.orderInfo() != null) {
			String order = normalize(device.orderInfo());
			List<Candidate> ordered = candidates.stream()
				.filter(c -> ordered(c.variant(), order))
				.toList();
			if (!ordered.isEmpty()) {
				candidates = ordered;
			}
		}
		candidates = new ArrayList<>(candidates);
		candidates.sort((a, b) -> a.label().compareTo(b.label()));
		return new Match(candidates.size() == 1 ? Kind.UNIQUE : Kind.CHOICE, candidates, null);
	}

	/**
	 * Matches a device that reports no program version (a gateway read through local device
	 * management has no application object) by its manufacturer and order info: the variants whose
	 * order number the order info starts with, with the programs their products carry.
	 */
	private static Match byOrderInfo(FoundDevice device) {
		if (device.manufacturerId() == null || device.orderInfo() == null) {
			return new Match(Kind.UNIDENTIFIED, List.of(), "The device reported no program version and no order info.");
		}
		TLObject manufacturer = manufacturer(device.manufacturerId().intValue());
		if (manufacturer == null) {
			return new Match(Kind.UNIDENTIFIED, List.of(),
				String.format("The manufacturer M-%04X is not in the catalog.", device.manufacturerId()));
		}
		String order = normalize(device.orderInfo());
		List<Candidate> candidates = new ArrayList<>();
		for (Object product : (Collection<?>) manufacturer.tValueByName("products")) {
			TLObject hardware = (TLObject) product;
			for (Object variant : (Collection<?>) hardware.tValueByName("variants")) {
				if (!ordered((TLObject) variant, order)) {
					continue;
				}
				for (Object assignment : (Collection<?>) hardware.tValueByName("programs")) {
					TLObject programAssignment = (TLObject) assignment;
					if (programAssignment.tValueByName("program") instanceof Program program) {
						candidates.add(new Candidate(program, programAssignment, (TLObject) variant));
					}
				}
			}
		}
		if (candidates.isEmpty()) {
			return new Match(Kind.UNIDENTIFIED, List.of(), "The device reported no program version, and no product of "
				+ manufacturer.tValueByName("name") + " has the order number " + device.orderInfo() + ".");
		}
		candidates.sort((a, b) -> a.label().compareTo(b.label()));
		return new Match(candidates.size() == 1 ? Kind.UNIQUE : Kind.CHOICE, candidates, null);
	}

	/**
	 * Whether the order info a device reports (normalized) names the given variant: it starts with
	 * the variant's order number (devices pad it, e.g. <code>N000402000</code> for
	 * <code>N000402</code>).
	 */
	private static boolean ordered(TLObject variant, String order) {
		return variant.tValueByName("orderNumber") instanceof String number && !normalize(number).isEmpty()
			&& order.startsWith(normalize(number));
	}

	/**
	 * The manufacturer of the catalog with the given KNX ID, <code>null</code> if there is none.
	 */
	public static TLObject manufacturer(int id) {
		TLModule module = TLModelUtil.findType("ehome.catalog:ManufacturerRoot").getModule();
		TLObject root = module.getSingleton(TLModule.DEFAULT_SINGLETON_NAME);
		if (root == null) {
			return null;
		}
		String extId = String.format("M-%04X", id);
		TLObject byExtId = null;
		for (Object element : (Collection<?>) root.tValueByName("manufacturers")) {
			TLObject manufacturer = (TLObject) element;
			if (manufacturer.tValueByName("id") instanceof Integer knxId) {
				if (knxId.intValue() == id) {
					return manufacturer;
				}
			} else if (extId.equals(manufacturer.tValueByName("extId"))) {
				byExtId = manufacturer;
			}
		}
		return byExtId;
	}

	/**
	 * The hardware type the load procedure of a program compares (a <code>CompareProp</code> of
	 * property 78), <code>null</code> if it compares none.
	 */
	private static byte[] comparedHardwareType(Program program) {
		for (LoadProcedure procedure : program.getLoadProcedures()) {
			for (LoadStep step : procedure.getSteps()) {
				if (step.getKind() != null && "CompareProp".equals(step.getKind().getName())
					&& step.getPropertyId() != null && step.getPropertyId().intValue() == BusScanner.PID_HARDWARE_TYPE
					&& (step.getObjectIndex() == null || step.getObjectIndex().intValue() == 0)) {
					return ParameterEncoding.toBytes(step.getInlineData());
				}
			}
		}
		return null;
	}

	/**
	 * Whether two values are equal when the shorter is padded with zeros (as the download compares a
	 * property with the inline data of a <code>CompareProp</code> step).
	 */
	private static boolean samePadded(byte[] a, byte[] b) {
		for (int n = 0; n < Math.max(a.length, b.length); n++) {
			if ((n < a.length ? a[n] : 0) != (n < b.length ? b[n] : 0)) {
				return false;
			}
		}
		return true;
	}

	private static String normalize(String orderNumber) {
		return orderNumber.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
	}

}
