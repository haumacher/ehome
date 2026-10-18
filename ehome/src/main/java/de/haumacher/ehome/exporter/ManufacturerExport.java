/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.exporter;

import static de.haumacher.ehome.exporter.Values.extId;
import static de.haumacher.ehome.exporter.Values.list;
import static de.haumacher.ehome.exporter.Values.ref;
import static de.haumacher.ehome.exporter.Values.sorted;
import static de.haumacher.ehome.exporter.Values.string;

import java.util.List;

import javax.xml.stream.XMLStreamException;

import com.top_logic.model.TLObject;

/**
 * Writes the catalog (<code>Catalog.xml</code>) and the hardware (<code>Hardware.xml</code>) of a
 * manufacturer, restricted to the products the project uses.
 */
final class ManufacturerExport {

	private static final String PRODUCT_INFIX = "_P-";

	private final Usage _usage;

	private final TLObject _manufacturer;

	ManufacturerExport(Usage usage, TLObject manufacturer) {
		_usage = usage;
		_manufacturer = manufacturer;
	}

	/**
	 * Whether the manufacturer has catalog sections with products of the project.
	 */
	boolean hasCatalog() {
		return list(_manufacturer, "catalogs").stream().anyMatch(this::isUsed);
	}

	/**
	 * Whether the manufacturer has products of the project.
	 */
	boolean hasHardware() {
		return !_usage.products(_manufacturer).isEmpty();
	}

	void writeCatalog(XmlOut out) throws XMLStreamException {
		Texts texts = new Texts("de");
		out.open("ManufacturerData");
		out.open("Manufacturer").attr("RefId", extId(_manufacturer));
		out.open("Catalog");
		for (TLObject section : sorted(_manufacturer, "catalogs")) {
			writeSection(out, texts, section);
		}
		out.close();
		texts.writeLanguages(out);
		out.close();
		out.close();
	}

	private void writeSection(XmlOut out, Texts texts, TLObject section) throws XMLStreamException {
		if (!isUsed(section)) {
			return;
		}
		texts.unit(extId(section));
		out.open("CatalogSection")
			.attr("Id", extId(section))
			.attr("Name", texts.text(section, "name", "Name"))
			// Not modeled; required by the schema.
			.attr("Number", "")
			.attr("DefaultLanguage", texts.defaultLanguage());
		for (TLObject child : sorted(section, "sections")) {
			writeSection(out, texts, child);
		}
		for (TLObject variant : list(section, "variants")) {
			if (!_usage._variants.contains(variant)) {
				continue;
			}
			TLObject assignment = assignment(variant);
			String variantId = extId(variant);
			int infix = variantId == null ? -1 : variantId.lastIndexOf(PRODUCT_INFIX);
			String itemId = assignment == null || infix < 0 ? null
				: extId(assignment) + "_CI-" + variantId.substring(infix + PRODUCT_INFIX.length()) + "-1";
			texts.unit(itemId);
			out.open("CatalogItem")
				// Not modeled: ID, name and number of the item; formed as ETS forms them from the
				// program assignment and the product.
				.attr("Id", itemId)
				.attr("Name", texts.untranslated(variant, "name"))
				.attr("Number", "1")
				.attr("ProductRefId", variantId)
				.attr("Hardware2ProgramRefId", extId(assignment))
				.close();
		}
		out.close();
	}

	/**
	 * The program assignment of the given product variant that the project uses; otherwise the first
	 * of its hardware.
	 */
	private TLObject assignment(TLObject variant) {
		TLObject product = ref(variant, "product");
		if (product == null) {
			return null;
		}
		List<TLObject> assignments = sorted(product, "programs");
		for (TLObject assignment : assignments) {
			if (_usage._assignments.contains(assignment)) {
				return assignment;
			}
		}
		return assignments.isEmpty() ? null : assignments.get(0);
	}

	private boolean isUsed(TLObject section) {
		for (TLObject variant : list(section, "variants")) {
			if (_usage._variants.contains(variant)) {
				return true;
			}
		}
		for (TLObject child : list(section, "sections")) {
			if (isUsed(child)) {
				return true;
			}
		}
		return false;
	}

	void writeHardware(XmlOut out) throws XMLStreamException {
		Texts texts = new Texts("de");
		out.open("ManufacturerData");
		out.open("Manufacturer").attr("RefId", extId(_manufacturer));
		out.open("Hardware");
		for (TLObject product : _usage.products(_manufacturer)) {
			out.open("Hardware")
				.attr("Id", extId(product))
				.attr("Name", string(product, "name"))
				.attr("SerialNumber", string(product, "serialNumber"))
				.attr("VersionNumber", string(product, "versionNumber"))
				// Not modeled; required by the schema.
				.attr("HasIndividualAddress", "1")
				.attr("HasApplicationProgram", "1");
			List<TLObject> variants = sorted(product, "variants").stream().filter(_usage._variants::contains).toList();
			if (!variants.isEmpty()) {
				out.open("Products");
				for (TLObject variant : variants) {
					texts.unit(extId(variant));
					out.open("Product")
						.attr("Id", extId(variant))
						.attr("Text", texts.text(variant, "name", "Text"))
						.attr("OrderNumber", string(variant, "orderNumber"))
						// Not modeled; required by the schema.
						.attr("IsRailMounted", "0")
						.attr("DefaultLanguage", texts.defaultLanguage())
						.close();
				}
				out.close();
			}
			List<TLObject> assignments =
				sorted(product, "programs").stream().filter(_usage._assignments::contains).toList();
			if (!assignments.isEmpty()) {
				out.open("Hardware2Programs");
				for (TLObject assignment : assignments) {
					out.open("Hardware2Program").attr("Id", extId(assignment));
					TLObject program = ref(assignment, "program");
					if (program != null) {
						out.open("ApplicationProgramRef").attr("RefId", extId(program)).close();
					}
					out.close();
				}
				out.close();
			}
			out.close();
		}
		out.close();
		texts.writeLanguages(out);
		out.close();
		out.close();
	}

}
