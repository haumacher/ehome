/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime.binary;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.zip.CRC32;

import de.haumacher.ehome.connection.Signatures;
import de.haumacher.ehome.runtime.binary.Layout.Field;
import de.haumacher.ehome.runtime.binary.Layout.Section;

/**
 * Writes the tables of an image in the canonical layout of homecontrol
 * <code>spec/runtime-image.md</code> 9.18: the header, the section table, the sections in ascending
 * order of their ids each at the next multiple of 8, the strings sorted bytewise without duplicates,
 * the label entries in the order their references occur, the CRC-32 and, for a signed image, the
 * Ed25519 signature (9.17).
 *
 * <p>
 * A record is a map from the names of its fields ({@link Layout}) to their values: a {@link Number}
 * for a number, a {@link String} for a string reference (<code>null</code> or empty: none), a label
 * as the source gives it (a {@link String} or a {@link Map} of language tags to texts;
 * <code>null</code>: none) for a label reference, a <code>byte[]</code> for raw bytes. A field the map
 * does not have is written 0.
 * </p>
 */
final class ImageWriter {

	private ImageWriter() {
		// Static only.
	}

	/**
	 * The image of the given tables.
	 *
	 * @param tables
	 *        The records by section id; the sections <code>strings</code> and <code>labels</code> are
	 *        made here, an empty table is left out.
	 * @param byteSections
	 *        The byte sections but <code>strings</code> (<code>ui</code>, <code>ui.anonymous</code>) by
	 *        their ids; one that is not given is left out.
	 * @param seed
	 *        The private installation key (its seed, 64 hex digits) to sign with, <code>null</code> for
	 *        an unsigned image.
	 */
	/** Whether a datapoint of the tables has an initial value (9.7, format 1.2). */
	private static boolean hasInitial(SortedMap<Integer, List<Map<String, Object>>> tables) {
		List<Map<String, Object>> datapoints = tables.get(Integer.valueOf(Layout.DATAPOINTS));
		if (datapoints != null) {
			for (Map<String, Object> rec : datapoints) {
				if (rec.get("init") instanceof Number init && (init.intValue() & Layout.INIT_INITIAL) != 0) {
					return true;
				}
			}
		}
		return false;
	}

	/** Whether a history of the tables is a meter reading (9.9, format 1.3). */
	private static boolean hasMeter(SortedMap<Integer, List<Map<String, Object>>> tables) {
		List<Map<String, Object>> histories = tables.get(Integer.valueOf(Layout.HISTORY));
		if (histories != null) {
			for (Map<String, Object> rec : histories) {
				if (rec.get("flags") instanceof Number flags && (flags.intValue() & Layout.HISTORY_METER) != 0) {
					return true;
				}
			}
		}
		return false;
	}

	static byte[] write(SortedMap<Integer, List<Map<String, Object>>> tables, Map<Integer, byte[]> byteSections,
			String seed) {
		// The strings: every string reference and every tag and text of a label, sorted bytewise.
		TreeSet<byte[]> strings = new TreeSet<>(Arrays::compareUnsigned);
		strings.add(new byte[0]);
		List<Map<String, Object>> labels = new ArrayList<>();
		for (Map.Entry<Integer, List<Map<String, Object>>> entry : tables.entrySet()) {
			Section section = Layout.section(entry.getKey().intValue());
			for (Map<String, Object> record : entry.getValue()) {
				for (Field field : section.fields()) {
					Object value = record.get(field.name());
					if (field.kind() == Layout.Kind.STR) {
						strings.add(utf8(value, section, field));
					} else if (field.kind() == Layout.Kind.LABEL && value != null) {
						// The entries of every label reference anew, in the order the references occur (9.5).
						int first = labels.size();
						if (value instanceof Map<?, ?> map) {
							for (Map.Entry<?, ?> language : map.entrySet()) {
								labels.add(Map.of("tag", language.getKey().toString(), "text", language.getValue().toString()));
							}
						} else {
							labels.add(Map.of("tag", "", "text", value.toString()));
						}
						int count = labels.size() - first;
						if (count < 1 || count > Layout.MAX_LABEL_LANGUAGES) {
							throw new CompileException(section.name() + "." + field.name(),
								"a label of " + count + " languages, 1 to " + Layout.MAX_LABEL_LANGUAGES);
						}
						record.put(field.name(), Long.valueOf(((long) count << 24) | first));
					}
				}
			}
		}
		Section labelSection = Layout.section(Layout.LABELS);
		for (Map<String, Object> label : labels) {
			strings.add(utf8(label.get("tag"), labelSection, labelSection.field("tag")));
			strings.add(utf8(label.get("text"), labelSection, labelSection.field("text")));
		}
		Map<String, Integer> offsets = new HashMap<>();
		int stringBytes = 0;
		for (byte[] string : strings) {
			offsets.put(new String(string, StandardCharsets.UTF_8), Integer.valueOf(stringBytes));
			stringBytes += string.length + 1;
		}

		// The sections present, in ascending order of their ids.
		List<Integer> ids = new ArrayList<>();
		for (Map.Entry<Integer, List<Map<String, Object>>> entry : tables.entrySet()) {
			if (!entry.getValue().isEmpty()) {
				ids.add(entry.getKey());
			}
		}
		ids.add(Integer.valueOf(Layout.STRINGS));
		if (!labels.isEmpty()) {
			ids.add(Integer.valueOf(Layout.LABELS));
		}
		ids.addAll(byteSections.keySet());
		ids.sort(null);

		int[] offset = new int[ids.size()];
		int[] length = new int[ids.size()];
		int end = Layout.HEADER_SIZE + Layout.ENTRY_SIZE * ids.size();
		for (int i = 0; i < ids.size(); i++) {
			int id = ids.get(i).intValue();
			end = align(end);
			offset[i] = end;
			Section section = Layout.section(id);
			if (id == Layout.STRINGS) {
				length[i] = stringBytes;
			} else if (byteSections.containsKey(ids.get(i))) {
				length[i] = byteSections.get(ids.get(i)).length;
			} else if (id == Layout.LABELS) {
				length[i] = labels.size() * section.size();
			} else {
				length[i] = tables.get(ids.get(i)).size() * section.size();
			}
			end += length[i];
		}
		int signedEnd = align(end);
		boolean signed = seed != null;
		int total = signedEnd + (signed ? Layout.SIGNATURE_SIZE : 0);

		ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
		out.put(0, Layout.MAGIC);
		out.putShort(4, (short) Layout.MAJOR);
		// The oldest minor version with what the image uses (spec/runtime-image.md 8): 1.3 a meter, 1.2
		// an initial value of a datapoint, 1.1 the sections of the anonymous pages.
		out.putShort(6, (short) (hasMeter(tables) ? 3 : hasInitial(tables) ? 2
			: byteSections.containsKey(Integer.valueOf(Layout.UI_ANONYMOUS)) ? 1 : 0));
		out.putInt(8, total);
		out.putInt(16, signed ? Layout.FLAG_SIGNED : 0);
		out.putShort(20, (short) Layout.HEADER_SIZE);
		out.putShort(22, (short) ids.size());
		for (int i = 0; i < ids.size(); i++) {
			int at = Layout.HEADER_SIZE + i * Layout.ENTRY_SIZE;
			Section section = Layout.section(ids.get(i).intValue());
			out.putShort(at, (short) section.id());
			out.putShort(at + 2, (short) 0);
			out.putInt(at + 4, offset[i]);
			out.putInt(at + 8, length[i]);
			out.putShort(at + 12, (short) section.size());
		}
		for (int i = 0; i < ids.size(); i++) {
			int id = ids.get(i).intValue();
			Section section = Layout.section(id);
			if (id == Layout.STRINGS) {
				int at = offset[i];
				for (byte[] string : strings) {
					out.put(at, string);
					at += string.length + 1;
				}
			} else if (byteSections.containsKey(ids.get(i))) {
				out.put(offset[i], byteSections.get(ids.get(i)));
			} else {
				List<Map<String, Object>> records = id == Layout.LABELS ? labels : tables.get(ids.get(i));
				for (int r = 0; r < records.size(); r++) {
					writeRecord(out, offset[i] + r * section.size(), section, records.get(r), offsets);
				}
			}
		}
		byte[] result = out.array();
		ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(Layout.CRC_OFFSET, crc(result, signedEnd));
		if (signed) {
			byte[] signature = Signatures.sign(seed, Signatures.message(Arrays.copyOf(result, signedEnd)));
			System.arraycopy(signature, 0, result, signedEnd, Layout.SIGNATURE_SIZE);
		}
		return result;
	}

	/**
	 * The CRC-32 (zlib's) over the first bytes of the image, the field <code>header.crc</code> taken as
	 * 0.
	 */
	static int crc(byte[] image, int signedEnd) {
		CRC32 crc = new CRC32();
		crc.update(image, 0, Layout.CRC_OFFSET);
		crc.update(new byte[4]);
		crc.update(image, Layout.CRC_OFFSET + 4, signedEnd - Layout.CRC_OFFSET - 4);
		return (int) crc.getValue();
	}

	private static void writeRecord(ByteBuffer out, int at, Section section, Map<String, Object> record,
			Map<String, Integer> strings) {
		for (Field field : section.fields()) {
			Object value = record.get(field.name());
			int pos = at + field.offset();
			switch (field.kind()) {
				case U8:
					out.put(pos, (byte) number(value));
					break;
				case U16:
					out.putShort(pos, (short) number(value));
					break;
				case U32:
				case I32:
				case LABEL:
					out.putInt(pos, (int) number(value));
					break;
				case I64:
				case U64:
					out.putLong(pos, number(value));
					break;
				case F64:
					out.putDouble(pos, value == null ? 0 : ((Number) value).doubleValue());
					break;
				case STR:
					out.putInt(pos, strings.get(value == null ? "" : value.toString()).intValue());
					break;
				case RAW:
					if (value != null) {
						out.put(pos, (byte[]) value);
					}
					break;
				default:
					throw new IllegalStateException(field.kind().name());
			}
		}
	}

	private static long number(Object value) {
		return value == null ? 0 : ((Number) value).longValue();
	}

	private static byte[] utf8(Object value, Section section, Field field) {
		byte[] result = value == null ? new byte[0] : value.toString().getBytes(StandardCharsets.UTF_8);
		if (result.length > Layout.MAX_STRING) {
			throw new CompileException(section.name() + "." + field.name(),
				"a string of " + result.length + " bytes, at most " + Layout.MAX_STRING);
		}
		for (byte b : result) {
			if (b == 0) {
				throw new CompileException(section.name() + "." + field.name(), "a string with a NUL");
			}
		}
		return result;
	}

	static int align(int offset) {
		return (offset + 7) & ~7;
	}

}
