/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.model.operational.LoadProcedure;
import de.haumacher.ehome.model.operational.LoadStep;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.ParameterEncoding;
import de.haumacher.ehome.programming.download.DownloadPlan;

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
	 * @param reasonKey
	 *        For an unidentified device why, for a person; otherwise <code>null</code>.
	 */
	public record Match(Kind kind, List<Candidate> candidates, ResKey reasonKey) {

		/**
		 * The {@link #reasonKey()} in English, for the log; <code>null</code> for an identified
		 * device.
		 */
		public String reason() {
			return reasonKey == null ? null : DownloadPlan.english(reasonKey);
		}
	}

	/**
	 * Matches a device found on the bus of a project against the catalog, preferring what the project
	 * says: where the catalog offers several candidates (e.g. several editions of the same program,
	 * imported from different exports of a project) and the project has a device at the address that
	 * runs one of them, that one (of the device's product variant, if it is among them) is the match.
	 */
	public static Match match(TLObject project, FoundDevice found) {
		Match match = match(found);
		if (match.kind() != Kind.CHOICE || project == null) {
			return match;
		}
		TLObject device = InstallationBuilder.device(project, found.address());
		if (device == null) {
			return match;
		}
		Object assignment = device.tValueByName("program");
		List<Candidate> same = match.candidates().stream().filter(c -> c.assignment() == assignment).toList();
		if (same.isEmpty()) {
			Program program = DeviceConfiguration.program(device);
			same = match.candidates().stream().filter(c -> c.program() == program).toList();
		}
		if (same.isEmpty()) {
			return match;
		}
		Object variant = device.tValueByName("type");
		List<Candidate> sameVariant = same.stream().filter(c -> c.variant() == variant).toList();
		if (!sameVariant.isEmpty()) {
			same = sameVariant;
		}
		return new Match(same.size() == 1 ? Kind.UNIQUE : Kind.CHOICE, same, null);
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
				I18NConstants.UNIDENTIFIED_MANUFACTURER__MANUFACTURER.fill(String.format("M-%04X", manufacturerId)));
		}
		List<Program> programs = new ArrayList<>();
		List<ResKey> excluded = new ArrayList<>();
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
				excluded.add(I18NConstants.EXCLUDED_MASK__PROGRAM_MASK.fill(program.getExtId(),
					String.format("%04X", program.getMask().getNumber())));
				continue;
			}
			byte[] hardware = comparedHardwareType(program);
			if (hardware != null && device.hardwareType() != null && !samePadded(hardware, device.hardwareType())) {
				excluded.add(I18NConstants.EXCLUDED_HARDWARE__PROGRAM_TYPE.fill(program.getExtId(), FoundDevice.hex(hardware)));
				continue;
			}
			programs.add(program);
		}
		if (programs.isEmpty()) {
			String number = String.format("%04X", device.applicationNumber());
			String version = String.format("%02X", device.applicationVersion());
			String manufacturerText = String.format("M-%04X", manufacturerId);
			ResKey reason = excluded.isEmpty()
				? I18NConstants.UNIDENTIFIED_PROGRAM__NUMBER_VERSION_MANUFACTURER.fill(number, version, manufacturerText)
				: I18NConstants.UNIDENTIFIED_PROGRAM_EXCLUDED__NUMBER_VERSION_MANUFACTURER_EXCLUDED.fill(number, version,
					manufacturerText, join(excluded));
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
			return new Match(Kind.UNIDENTIFIED, List.of(),
				I18NConstants.UNIDENTIFIED_NO_PRODUCT__PROGRAM.fill(programs.get(0).getExtId()));
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
			return new Match(Kind.UNIDENTIFIED, List.of(), I18NConstants.UNIDENTIFIED_NO_IDENTITY);
		}
		TLObject manufacturer = manufacturer(device.manufacturerId().intValue());
		if (manufacturer == null) {
			return new Match(Kind.UNIDENTIFIED, List.of(),
				I18NConstants.UNIDENTIFIED_MANUFACTURER__MANUFACTURER.fill(String.format("M-%04X", device.manufacturerId())));
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
			return new Match(Kind.UNIDENTIFIED, List.of(),
				I18NConstants.UNIDENTIFIED_ORDER__MANUFACTURER_ORDER.fill(manufacturer.tValueByName("name"), device.orderInfo()));
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

	/**
	 * The given texts as one, separated by semicolons.
	 */
	private static ResKey join(List<ResKey> parts) {
		ResKey result = parts.get(0);
		for (int n = 1; n < parts.size(); n++) {
			result = I18NConstants.JOIN__FIRST_NEXT.fill(result, parts.get(n));
		}
		return result;
	}

	private static String normalize(String orderNumber) {
		return orderNumber.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
	}

}
