/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime.binary;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;

import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.binary.ImageCompiler;
import de.haumacher.ehome.runtime.binary.ImageCompiler.Options;
import de.haumacher.ehome.runtime.binary.ImageDecoder;
import de.haumacher.ehome.runtime.binary.Layout;

import test.de.haumacher.ehome.blocks.ConformanceBlock;

/**
 * The compiled corpora of homecontrol <code>spec/runtime-image.md</code> 13.1, made from the sources
 * of a <code>spec/fixtures</code> directory: the images and their accepted cases, the images of the
 * rule and block cases and of their <code>image</code> steps, and the mutation corpus of the device's
 * own refusals (<code>images/binary/</code>).
 *
 * <p>
 * {@link #generate(File)} gives every file by its path relative to <code>spec/fixtures</code>;
 * {@link TestImageCorpus} compares them with a homecontrol checkout or writes them there.
 * </p>
 */
public final class ImageCorpus {

	/** The schema the cases of the rule and block corpora are written in (13.1). */
	static final String CASE_SCHEMA = "1.7";

	/**
	 * The rule and block cases refused by the device rather than by E-Home's validation (13.1): an
	 * unknown block type, a block's ports and parameters against its manifest, a limit. Their images
	 * are compiled; every other <code>loadRefused</code> case has none.
	 */
	static boolean refusedByDevice(String corpus, Map<?, ?> expected) {
		Object reason = expected.get("loadRefused");
		Object path = expected.get("path");
		if (!"blocks".equals(corpus)) {
			// The rule cases' refusals are E-Home's semantic checks; the limit case 97 is cut, see isCut.
			return false;
		}
		if (List.of("unknownType", "missingInput", "typeError", "invalidParameter", "limitExceeded").contains(reason)) {
			return true;
		}
		return "forbidden".equals(reason) && path instanceof String text
			&& (text.contains(".aggregation.") || text.contains(".inputs."));
	}

	/**
	 * A case whose image the device would refuse that E-Home cannot compile: its rule loader refuses
	 * the limit before anything is compiled.
	 */
	static boolean isCut(String corpus, String name) {
		return "rules".equals(corpus) && name.startsWith("97-");
	}

	private ImageCorpus() {
		// Static only.
	}

	/**
	 * Every file of the compiled corpora, by its path relative to <code>fixtures</code>.
	 */
	public static Map<String, byte[]> generate(File fixtures) throws Exception {
		return generate(fixtures, new ArrayList<>());
	}

	/**
	 * Every file of the compiled corpora, by its path relative to <code>fixtures</code>; every image is
	 * decoded and compiled again, and where that does not give the same bytes, the path and the
	 * difference are added to <code>roundTrip</code>.
	 */
	public static Map<String, byte[]> generate(File fixtures, List<String> roundTrip) throws Exception {
		Map<String, byte[]> result = new TreeMap<>();
		_roundTrip = roundTrip;
		String seed = seed(fixtures, "installation");
		File images = new File(fixtures, "images");
		// A template (test-system.template.json, the placeholders of the KNX test system) is no image.
		for (File file : sorted(images.listFiles((dir, name) -> name.endsWith(".json") && !name.endsWith(".template.json")))) {
			Map<?, ?> source = read(file);
			result.put("images/" + base(file) + ".bin", compile("images/" + base(file), source, Options.unsigned()));
			if (file.getName().equals("minimal.json")) {
				result.put("images/minimal.signed.bin", compile("images/minimal.signed", source, Options.signed(seed)));
			}
		}
		for (File file : sorted(new File(images, "cases").listFiles((dir, name) -> name.startsWith("accept-")
			&& name.endsWith(".json")))) {
			result.put("images/cases/" + base(file) + ".bin", compile("images/cases/" + base(file), read(file),
				Options.unsigned()));
		}
		cases(fixtures, "rules", result);
		cases(fixtures, "blocks", result);
		mutations(fixtures, result);
		return result;
	}

	private static List<String> _roundTrip = new ArrayList<>();

	/** Compiles the source, and checks that decoding and compiling again gives the same bytes. */
	private static byte[] compile(String path, Map<?, ?> source, Options options) {
		byte[] result;
		try {
			result = ImageCompiler.compile(source, options);
		} catch (RuntimeException ex) {
			throw new IllegalStateException(path + ": " + ex.getMessage(), ex);
		}
		try {
			Map<String, Object> view = ImageDecoder.decode(result, options.registry());
			byte[] again = ImageCompiler.compile(view, options);
			if (!Arrays.equals(result, again)) {
				_roundTrip.add(path + ": " + result.length + " bytes, again " + again.length + "; the view: " + Json.write(view));
			}
		} catch (Exception ex) {
			_roundTrip.add(path + ": " + ex);
		}
		return result;
	}

	static String seed(File fixtures, String key) throws Exception {
		Map<?, ?> vectors = read(new File(fixtures, "signatures/vectors.json"));
		return (String) ((Map<?, ?>) ((Map<?, ?>) vectors.get("keys")).get(key)).get("private_seed_TEST_ONLY");
	}

	private static void cases(File fixtures, String corpus, Map<String, byte[]> result) throws Exception {
		File dir = new File(fixtures, corpus);
		for (File file : sorted(dir.listFiles((d, name) -> name.matches("\\d+-.*\\.json")))) {
			Map<?, ?> test = read(file);
			String name = base(file);
			Map<?, ?> expected = test.get("expected") instanceof Map<?, ?> map ? map : Map.of();
			if (expected.containsKey("loadRefused") && !refusedByDevice(corpus, expected) || isCut(corpus, name)) {
				continue;
			}
			Options options = caseOptions(test);
			Object schedules = test.get("schedules");
			result.put(corpus + "/" + name + ".bin", compile(corpus + "/" + name,
				caseImage(test, name, 0, test.get("rules"), test.get("blocks"), schedules), options));
			List<?> timeline = test.get("timeline") instanceof List<?> list ? list : List.of();
			for (int k = 0; k < timeline.size(); k++) {
				if (!(timeline.get(k) instanceof Map<?, ?> step) || !(step.get("image") instanceof Map<?, ?> image)) {
					continue;
				}
				if (image.containsKey("schedules")) {
					schedules = image.get("schedules");
				}
				result.put(corpus + "/" + name + ".step-" + k + ".bin", compile(corpus + "/" + name + ".step-" + k,
					caseImage(test, name, k + 1, image.get("rules"), image.get("blocks"), schedules), options));
			}
		}
	}

	/** How the image of a case is compiled: its datapoints owned by the harness's test technology. */
	static Options caseOptions(Map<?, ?> test) {
		Set<String> harness = new HashSet<>();
		Set<String> unreadable = new HashSet<>();
		for (Object item : (List<?>) test.get("datapoints")) {
			Map<?, ?> dp = (Map<?, ?>) item;
			if (!Boolean.TRUE.equals(dp.get("application"))) {
				harness.add((String) dp.get("name"));
			}
			if (Boolean.FALSE.equals(dp.get("readable"))) {
				unreadable.add((String) dp.get("name"));
			}
		}
		return new Options(null, ConformanceBlock.CORPUS_REGISTRY, harness, datapoint -> !unreadable.contains(datapoint));
	}

	/** The source of the image of a case (13.1). */
	static Map<String, Object> caseImage(Map<?, ?> test, String name, int revision, Object rules, Object blocks,
			Object schedules) {
		Map<String, Object> image = new LinkedHashMap<>();
		Map<String, Object> header = new LinkedHashMap<>();
		header.put("schemaVersion", CASE_SCHEMA);
		header.put("projectId", name);
		header.put("revision", Long.valueOf(revision));
		image.put("image", header);
		List<Object> datapoints = new ArrayList<>();
		for (Object item : (List<?>) test.get("datapoints")) {
			Map<?, ?> dp = (Map<?, ?>) item;
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("id", dp.get("name"));
			json.put("type", dp.get("type"));
			if (!Boolean.FALSE.equals(dp.get("writable"))) {
				json.put("writable", Boolean.TRUE);
				json.put("transmit", Boolean.TRUE);
			}
			if (Boolean.TRUE.equals(dp.get("application"))) {
				json.put("virtual", new LinkedHashMap<>());
			}
			if (Boolean.TRUE.equals(dp.get("persist"))) {
				json.put("persist", Boolean.TRUE);
			}
			datapoints.add(json);
		}
		image.put("datapoints", datapoints);
		if (schedules instanceof List<?> list && !list.isEmpty()) {
			List<Object> entries = new ArrayList<>();
			for (Object item : list) {
				if (item instanceof Map<?, ?> entry && entry.containsKey("kind")) {
					entries.add(entry);
					continue;
				}
				String id = item instanceof Map<?, ?> entry ? (String) entry.get("id") : (String) item;
				Map<String, Object> json = new LinkedHashMap<>();
				json.put("id", id);
				json.put("label", id);
				json.put("kind", "once");
				json.put("enabled", Boolean.FALSE);
				json.put("enabledAdjustable", Boolean.FALSE);
				Map<String, Object> at = new LinkedHashMap<>();
				at.put("year", Long.valueOf(1970));
				at.put("month", Long.valueOf(1));
				at.put("day", Long.valueOf(1));
				at.put("hour", Long.valueOf(0));
				at.put("minute", Long.valueOf(0));
				at.put("second", Long.valueOf(0));
				json.put("at", at);
				if (item instanceof Map<?, ?> entry && entry.get("output") != null) {
					json.put("output", entry.get("output"));
				}
				entries.add(json);
			}
			image.put("schedules", entries);
		}
		if (rules instanceof List<?> list && !list.isEmpty()) {
			image.put("rules", rules);
		}
		if (blocks instanceof List<?> list && !list.isEmpty()) {
			image.put("blocks", blocks);
		}
		return image;
	}

	// ---- the mutation corpus -----------------------------------------------------------------

	/** One mutation: its image and what the device answers. */
	private record Mutation(String name, String from, String mutation, byte[] bytes, String code, String path,
			String reason, String deviceKey) {
		// A value.
	}

	private static void mutations(File fixtures, Map<String, byte[]> result) throws Exception {
		byte[] minimal = result.get("images/minimal.bin");
		byte[] signed = result.get("images/minimal.signed.bin");
		byte[] example = result.get("images/example.bin");
		List<Mutation> mutations = new ArrayList<>();

		mutations.add(new Mutation("truncated", "minimal.bin", "cut to 100 bytes", Arrays.copyOf(minimal, 100),
			"image.format", "header.length", "the header says 112 bytes, the body has 100", null));
		byte[] json = Files.readAllBytes(new File(fixtures, "images/minimal.json").toPath());
		mutations.add(new Mutation("json-text", "minimal.json", "the source text minimal.json itself", json, "image.format",
			"header.magic", "not a runtime image: the magic is " + hex(Arrays.copyOf(json, 4))
				+ ", not 48 43 49 4D (\"HCIM\"): a JSON text (E-Home compiles the image; send the binary form)",
			null));
		mutations.add(new Mutation("major-2", "minimal.bin", "header.major 2", crc(u16(minimal, 4, 2)), "image.version",
			"header.major", "format 2.0: this firmware reads format 1 (up to 1.1)", null));
		byte[] damaged = minimal.clone();
		damaged[0x66] ^= 0x20;
		mutations.add(new Mutation("damaged", "minimal.bin", "byte 0x66 changed, the CRC kept", damaged, "image.damaged",
			"header.crc", "the CRC-32 is 59772c42, the bytes give " + String.format("%08x", Integer.valueOf(crcOf(damaged)))
				+ ": the image was damaged on the way or in the slot",
			null));
		mutations.add(new Mutation("unsigned-key", "minimal.bin",
			"minimal.bin unchanged, sent to a device that holds the installation key", minimal, "image.unsigned",
			"header.flags",
			"the image is not signed; the device accepts only images signed with the installation key ce7d 4a41 a476 9ee3",
			"installation"));
		mutations.add(new Mutation("rotated-key", "minimal.signed.bin",
			"minimal.signed.bin unchanged, sent to a device that holds the rotated key", signed, "image.bad_signature",
			"signature", "the signature does not verify under the installation key 90ae 3702 f3a9 63ab", "rotated"));
		mutations.add(new Mutation("offset-unaligned", "minimal.bin", "sections[1].offset 0x61",
			crc(u32(minimal, 0x34, 0x61)), "image.invalid", "sections[1].offset", "malformed: offset 0x61 is not a multiple of 8",
			null));
		mutations.add(new Mutation("strings-last-nul", "minimal.bin", "sections[1].length 12 (the last NUL outside)",
			crc(u32(minimal, 0x38, 12)), "image.invalid", "strings", "malformed: the last byte is not a NUL", null));
		mutations.add(new Mutation("entry-size", "minimal.bin", "sections[0].entrySize 28", crc(u16(minimal, 0x2c, 28)),
			"image.invalid", "sections[0].entrySize", "malformed: entry size 28 for image, which needs at least 32", null));
		mutations.add(new Mutation("image-two-records", "minimal.bin",
			"sections[0].length 64 with entrySize 32: two image records, the strings moved behind them", twoRecords(minimal),
			"image.invalid", "sections[0].length", "malformed: the section image holds 2 records, it has one", null));
		mutations.add(new Mutation("section-twice", "minimal.bin", "sections[1].id 0x01", crc(u16(minimal, 0x30, 1)),
			"image.invalid", "sections[1].id", "malformed: section 0x01 twice", null));
		mutations.add(new Mutation("unknown-required", "minimal.bin", "sections[1].id 0x61 with flags 1 (required)",
			crc(u16(u16(minimal, 0x30, 0x61), 0x32, 1)), "image.invalid", "sections[1].id",
			"malformed: section 0x61 is required, but this firmware does not know it", null));
		mutations.add(new Mutation("unknown-optional", "minimal.bin",
			"sections[1].id 0x61 with flags 0: skipped, so the strings are missing", crc(u16(minimal, 0x30, 0x61)),
			"image.invalid", "sections", "malformed: the section strings (0x02) is missing", null));
		mutations.add(new Mutation("project-id-6", "minimal.bin", "image.projectId 6", crc(u32(minimal, 0x40, 6)),
			"image.invalid", "image[0].projectId",
			"malformed: 6 is no string (not the start of one of the 13 bytes of strings)", null));
		mutations.add(new Mutation("project-id-0", "minimal.bin", "image.projectId 0", crc(u32(minimal, 0x40, 0)),
			"image.invalid", "image[0].projectId", "malformed: empty, it is required", null));
		mutations.add(new Mutation("minor-1-section", "minimal.bin",
			"header.minor 1 and a third section 0x61 of 8 bytes, not required", newerMinor(minimal), null, null, null, null));

		// Out of range in a larger image.
		Section roles = section(example, Layout.ROLES);
		Section datapoints = section(example, Layout.DATAPOINTS);
		int count = datapoints.length() / 24;
		mutations.add(new Mutation("datapoint-index", "example.bin", "roles[0].datapoint 1203",
			crc(u16(example, roles.offset() + 4, 1203)), "image.invalid", "roles[0].datapoint",
			"malformed: datapoint 1203 does not exist (" + count + " datapoints)", null));
		// The last section longer than the signed bytes; the strings longer than up to the next section.
		Section last = lastSection(example);
		int signedEnd = example.length;
		int beyond = signedEnd + 0x100;
		mutations.add(new Mutation("section-beyond", "example.bin",
			"the length of the last section (sections[" + last.position() + "]) to 256 bytes beyond the signed bytes",
			crc(u32(example, last.entry() + 8, beyond - last.offset())), "image.invalid",
			"sections[" + last.position() + "].length", "malformed: the section ends at 0x" + Integer.toHexString(beyond)
				+ ", beyond the signed bytes (0x" + Integer.toHexString(signedEnd) + ")",
			null));
		Section strings = section(example, Layout.STRINGS);
		Section following = sectionAt(example, strings.position() + 1);
		int overlap = following.offset() + 8;
		mutations.add(new Mutation("section-overlap", "example.bin",
			"the length of the section strings to 8 bytes into the section after it",
			crc(u32(example, strings.entry() + 8, overlap - strings.offset())), "image.invalid",
			"sections[" + strings.position() + "].length", "malformed: the section ends at 0x" + Integer.toHexString(overlap)
				+ ", beyond the next section's offset (0x" + Integer.toHexString(following.offset()) + ")",
			null));

		for (Mutation mutation : mutations) {
			result.put("images/binary/" + mutation.name() + ".bin", mutation.bytes());
			Map<String, Object> description = new LinkedHashMap<>();
			description.put("from", mutation.from());
			description.put("mutation", mutation.mutation());
			description.put("code", mutation.code());
			description.put("path", mutation.path());
			description.put("reason", mutation.reason());
			if (mutation.deviceKey() != null) {
				description.put("deviceKey", mutation.deviceKey());
			}
			result.put("images/binary/" + mutation.name() + ".json", Json.write(description).getBytes(StandardCharsets.UTF_8));
		}
	}

	/** A section of an image: its position in the table, its entry's offset, its offset and length. */
	private record Section(int position, int entry, int offset, int length) {
		// A value.
	}

	private static Section section(byte[] image, int id) {
		ByteBuffer in = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
		int count = in.getShort(22);
		for (int k = 0; k < count; k++) {
			int at = Layout.HEADER_SIZE + k * Layout.ENTRY_SIZE;
			if (in.getShort(at) == id) {
				return new Section(k, at, in.getInt(at + 4), in.getInt(at + 8));
			}
		}
		throw new IllegalArgumentException("no section " + id);
	}

	private static Section sectionAt(byte[] image, int k) {
		ByteBuffer in = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
		int at = Layout.HEADER_SIZE + k * Layout.ENTRY_SIZE;
		return new Section(k, at, in.getInt(at + 4), in.getInt(at + 8));
	}

	private static Section lastSection(byte[] image) {
		return sectionAt(image, ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).getShort(22) - 1);
	}

	private static byte[] twoRecords(byte[] minimal) {
		// Header and table as before, the image section twice as long, the strings behind it.
		byte[] result = new byte[0x90];
		System.arraycopy(minimal, 0, result, 0, 0x60);
		System.arraycopy(minimal, 0x40, result, 0x60, 0x20);
		System.arraycopy(minimal, 0x60, result, 0x80, 13);
		ByteBuffer out = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
		out.putInt(8, result.length);
		out.putInt(0x28, 64);
		out.putInt(0x34, 0x80);
		return crc(result);
	}

	private static byte[] newerMinor(byte[] minimal) {
		// Three entries: the table ends at 0x50; image at 0x50, strings at 0x70, the new section at 0x80.
		byte[] result = new byte[0x88];
		ByteBuffer out = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
		System.arraycopy(minimal, 0, result, 0, 0x20);
		out.putShort(6, (short) 1);
		out.putInt(8, result.length);
		out.putShort(22, (short) 3);
		System.arraycopy(minimal, 0x20, result, 0x20, 0x20);
		out.putInt(0x24, 0x50);
		out.putInt(0x34, 0x70);
		out.putShort(0x40, (short) 0x61);
		out.putInt(0x44, 0x80);
		out.putInt(0x48, 8);
		System.arraycopy(minimal, 0x40, result, 0x50, 0x20);
		System.arraycopy(minimal, 0x60, result, 0x70, 13);
		return crc(result);
	}

	private static byte[] u16(byte[] image, int at, int value) {
		byte[] result = image.clone();
		ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putShort(at, (short) value);
		return result;
	}

	private static byte[] u32(byte[] image, int at, int value) {
		byte[] result = image.clone();
		ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value);
		return result;
	}

	/** The image with its CRC recomputed (an unsigned image). */
	private static byte[] crc(byte[] image) {
		ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).putInt(Layout.CRC_OFFSET, crcOf(image));
		return image;
	}

	private static int crcOf(byte[] image) {
		CRC32 crc = new CRC32();
		crc.update(image, 0, Layout.CRC_OFFSET);
		crc.update(new byte[4]);
		crc.update(image, Layout.CRC_OFFSET + 4, image.length - Layout.CRC_OFFSET - 4);
		return (int) crc.getValue();
	}

	private static String hex(byte[] bytes) {
		StringBuilder out = new StringBuilder();
		for (byte b : bytes) {
			out.append(out.length() > 0 ? " " : "").append(String.format("%02X", Integer.valueOf(b & 0xFF)));
		}
		return out.toString();
	}

	// ---- helpers -----------------------------------------------------------------------------

	static Map<?, ?> read(File file) throws IOException {
		try {
			return (Map<?, ?>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8), 100);
		} catch (Json.JsonException ex) {
			throw new IOException(file + ": " + ex.getMessage(), ex);
		}
	}

	static String base(File file) {
		return file.getName().substring(0, file.getName().length() - ".json".length());
	}

	static File[] sorted(File[] files) {
		if (files == null) {
			return new File[0];
		}
		Arrays.sort(files);
		return files;
	}

}
