/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A DNS message as multicast DNS (RFC 6762) and DNS-based service discovery (RFC 6763) use it: the
 * questions and the records of the types {@link #PTR}, {@link #SRV}, {@link #TXT}, {@link #A} and
 * {@link #AAAA}, read from and written to the wire format (RFC 1035) with name compression.
 *
 * <p>
 * Names are kept as written, compared without regard to case ({@link #key(String)}). Records of
 * other types are kept with their raw data.
 * </p>
 */
public final class DnsMessage {

	/** The record type of an IPv4 address. */
	public static final int A = 1;

	/** The record type of a name pointer (the instances of a service type). */
	public static final int PTR = 12;

	/** The record type of the text attributes of a service instance. */
	public static final int TXT = 16;

	/** The record type of an IPv6 address. */
	public static final int AAAA = 28;

	/** The record type of the host and port of a service instance. */
	public static final int SRV = 33;

	/** The class of the internet. */
	public static final int IN = 1;

	/**
	 * The top bit of the class: in a question the request for a unicast answer (QU, RFC 6762
	 * section 5.4), in a record the cache flush flag.
	 */
	public static final int UNICAST_OR_FLUSH = 0x8000;

	/** The flags of a response from an authority. */
	public static final int FLAGS_RESPONSE = 0x8400;

	private static final int MAX_POINTERS = 64;

	/**
	 * Thrown when a message cannot be read.
	 */
	public static final class DnsException extends Exception {

		/**
		 * Creates a {@link DnsException}.
		 */
		public DnsException(String message) {
			super(message);
		}
	}

	/**
	 * A question of a message.
	 *
	 * @param name
	 *        The name asked for.
	 * @param type
	 *        The record type asked for.
	 * @param unicast
	 *        Whether a unicast answer is requested.
	 */
	public record Question(String name, int type, boolean unicast) {
		// Pure data.
	}

	/**
	 * A resource record.
	 *
	 * @param name
	 *        The owner name.
	 * @param type
	 *        The record type.
	 * @param ttl
	 *        The time to live in seconds; 0 announces that the record is gone.
	 * @param target
	 *        For {@link #PTR} the name pointed to, for {@link #SRV} the host; otherwise
	 *        <code>null</code>.
	 * @param port
	 *        For {@link #SRV} the port; otherwise 0.
	 * @param texts
	 *        For {@link #TXT} the strings; otherwise empty.
	 * @param address
	 *        For {@link #A} and {@link #AAAA} the address; otherwise <code>null</code>.
	 * @param data
	 *        For another type the raw data; otherwise <code>null</code>.
	 */
	public record Record(String name, int type, long ttl, String target, int port, List<String> texts,
			InetAddress address, byte[] data) {

		/**
		 * A {@link #PTR} record.
		 */
		public static Record ptr(String name, long ttl, String target) {
			return new Record(name, PTR, ttl, target, 0, List.of(), null, null);
		}

		/**
		 * A {@link #SRV} record.
		 */
		public static Record srv(String name, long ttl, String host, int port) {
			return new Record(name, SRV, ttl, host, port, List.of(), null, null);
		}

		/**
		 * A {@link #TXT} record.
		 */
		public static Record txt(String name, long ttl, List<String> texts) {
			return new Record(name, TXT, ttl, null, 0, List.copyOf(texts), null, null);
		}

		/**
		 * An {@link #A} or {@link #AAAA} record.
		 */
		public static Record address(String name, long ttl, InetAddress address) {
			return new Record(name, address.getAddress().length == 4 ? A : AAAA, ttl, null, 0, List.of(), address,
				null);
		}

		/**
		 * The text attributes of a {@link #TXT} record (RFC 6763 section 6): the keys in lower case,
		 * the first occurrence of a key wins, a key without <code>=</code> has the empty value.
		 */
		public Map<String, String> attributes() {
			Map<String, String> result = new LinkedHashMap<>();
			for (String text : texts) {
				int separator = text.indexOf('=');
				String key = (separator < 0 ? text : text.substring(0, separator)).toLowerCase(Locale.ROOT);
				if (!key.isEmpty()) {
					result.putIfAbsent(key, separator < 0 ? "" : text.substring(separator + 1));
				}
			}
			return result;
		}
	}

	private final int _id;

	private final int _flags;

	private final List<Question> _questions;

	private final List<Record> _records;

	/**
	 * Creates a {@link DnsMessage}.
	 *
	 * @param id
	 *        The identification (0 in multicast DNS, echoed in a unicast answer).
	 * @param flags
	 *        The flags (0 for a query, {@link #FLAGS_RESPONSE} for a response).
	 * @param questions
	 *        The questions.
	 * @param records
	 *        The records of the answer, authority and additional sections, all written as answers.
	 */
	public DnsMessage(int id, int flags, List<Question> questions, List<Record> records) {
		_id = id;
		_flags = flags;
		_questions = List.copyOf(questions);
		_records = List.copyOf(records);
	}

	/**
	 * The identification.
	 */
	public int id() {
		return _id;
	}

	/**
	 * Whether the message is a response.
	 */
	public boolean isResponse() {
		return (_flags & 0x8000) != 0;
	}

	/**
	 * The questions.
	 */
	public List<Question> questions() {
		return _questions;
	}

	/**
	 * The records of all sections.
	 */
	public List<Record> records() {
		return _records;
	}

	/**
	 * A name in the form names are compared in: lower case, without a final dot.
	 */
	public static String key(String name) {
		String result = name.toLowerCase(Locale.ROOT);
		return result.endsWith(".") ? result.substring(0, result.length() - 1) : result;
	}

	/**
	 * The message in the wire format, names compressed.
	 */
	public byte[] write() {
		Writer out = new Writer();
		out.u16(_id);
		out.u16(_flags);
		out.u16(_questions.size());
		out.u16(_records.size());
		out.u16(0);
		out.u16(0);
		for (Question question : _questions) {
			out.name(question.name());
			out.u16(question.type());
			out.u16(IN | (question.unicast() ? UNICAST_OR_FLUSH : 0));
		}
		for (Record record : _records) {
			out.name(record.name());
			out.u16(record.type());
			out.u16(IN);
			out.u32(record.ttl());
			int lengthAt = out.size();
			out.u16(0);
			int start = out.size();
			switch (record.type()) {
				case PTR:
					out.name(record.target());
					break;
				case SRV:
					out.u16(0);
					out.u16(0);
					out.u16(record.port());
					out.name(record.target());
					break;
				case TXT:
					if (record.texts().isEmpty()) {
						out.write(0);
					}
					for (String text : record.texts()) {
						byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
						out.write(Math.min(255, bytes.length));
						out.write(bytes, 0, Math.min(255, bytes.length));
					}
					break;
				case A:
				case AAAA:
					byte[] address = record.address().getAddress();
					out.write(address, 0, address.length);
					break;
				default:
					out.write(record.data(), 0, record.data().length);
			}
			out.patch16(lengthAt, out.size() - start);
		}
		return out.toByteArray();
	}

	/**
	 * Reads a message in the wire format.
	 *
	 * @throws DnsException
	 *         If the message is truncated or malformed.
	 */
	public static DnsMessage read(byte[] bytes, int length) throws DnsException {
		Reader in = new Reader(bytes, length);
		int id = in.u16();
		int flags = in.u16();
		int questionCount = in.u16();
		int recordCount = in.u16() + in.u16() + in.u16();
		List<Question> questions = new ArrayList<>();
		for (int n = 0; n < questionCount; n++) {
			String name = in.name();
			int type = in.u16();
			int dnsClass = in.u16();
			questions.add(new Question(name, type, (dnsClass & UNICAST_OR_FLUSH) != 0));
		}
		List<Record> records = new ArrayList<>();
		for (int n = 0; n < recordCount; n++) {
			String name = in.name();
			int type = in.u16();
			in.u16();
			long ttl = in.u32();
			int dataLength = in.u16();
			int end = in.position() + dataLength;
			if (end > length) {
				throw new DnsException("record data beyond the end of the message");
			}
			switch (type) {
				case PTR:
					records.add(Record.ptr(name, ttl, in.name()));
					break;
				case SRV:
					in.u16();
					in.u16();
					int port = in.u16();
					records.add(Record.srv(name, ttl, in.name(), port));
					break;
				case TXT:
					List<String> texts = new ArrayList<>();
					while (in.position() < end) {
						int size = in.u8();
						if (in.position() + size > end) {
							throw new DnsException("text beyond the end of its record");
						}
						if (size > 0) {
							texts.add(new String(in.bytes(size), StandardCharsets.UTF_8));
						}
					}
					records.add(Record.txt(name, ttl, texts));
					break;
				case A:
				case AAAA:
					if (dataLength != (type == A ? 4 : 16)) {
						throw new DnsException("address of wrong length");
					}
					try {
						records.add(Record.address(name, ttl, InetAddress.getByAddress(in.bytes(dataLength))));
					} catch (UnknownHostException ex) {
						throw new DnsException("invalid address");
					}
					break;
				default:
					records.add(new Record(name, type, ttl, null, 0, List.of(), null, in.bytes(dataLength)));
			}
			if (in.position() != end) {
				throw new DnsException("record data of wrong length");
			}
		}
		return new DnsMessage(id, flags, questions, records);
	}

	private static final class Writer extends ByteArrayOutputStream {

		private final Map<String, Integer> _names = new HashMap<>();

		void u16(int value) {
			write(value >>> 8);
			write(value);
		}

		void u32(long value) {
			u16((int) (value >>> 16));
			u16((int) value);
		}

		void patch16(int at, int value) {
			buf[at] = (byte) (value >>> 8);
			buf[at + 1] = (byte) value;
		}

		void name(String name) {
			String rest = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
			while (!rest.isEmpty()) {
				Integer known = _names.get(key(rest));
				if (known != null) {
					u16(0xC000 | known.intValue());
					return;
				}
				if (size() < 0x3FFF) {
					_names.put(key(rest), Integer.valueOf(size()));
				}
				int dot = rest.indexOf('.');
				String label = dot < 0 ? rest : rest.substring(0, dot);
				byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
				write(Math.min(63, bytes.length));
				write(bytes, 0, Math.min(63, bytes.length));
				rest = dot < 0 ? "" : rest.substring(dot + 1);
			}
			write(0);
		}
	}

	private static final class Reader {

		private final byte[] _bytes;

		private final int _length;

		private int _pos;

		Reader(byte[] bytes, int length) {
			_bytes = bytes;
			_length = length;
		}

		int position() {
			return _pos;
		}

		int u8() throws DnsException {
			check(1);
			return _bytes[_pos++] & 0xFF;
		}

		int u16() throws DnsException {
			check(2);
			int result = ((_bytes[_pos] & 0xFF) << 8) | (_bytes[_pos + 1] & 0xFF);
			_pos += 2;
			return result;
		}

		long u32() throws DnsException {
			return ((long) u16() << 16) | u16();
		}

		byte[] bytes(int count) throws DnsException {
			check(count);
			byte[] result = Arrays.copyOfRange(_bytes, _pos, _pos + count);
			_pos += count;
			return result;
		}

		String name() throws DnsException {
			StringBuilder result = new StringBuilder();
			int pos = _pos;
			int resume = -1;
			int pointers = 0;
			while (true) {
				if (pos >= _length) {
					throw new DnsException("name beyond the end of the message");
				}
				int size = _bytes[pos] & 0xFF;
				if ((size & 0xC0) == 0xC0) {
					if (pos + 1 >= _length) {
						throw new DnsException("name pointer beyond the end of the message");
					}
					if (++pointers > MAX_POINTERS) {
						throw new DnsException("name pointers in a loop");
					}
					if (resume < 0) {
						resume = pos + 2;
					}
					pos = ((size & 0x3F) << 8) | (_bytes[pos + 1] & 0xFF);
					continue;
				}
				if ((size & 0xC0) != 0) {
					throw new DnsException("unknown label type");
				}
				pos++;
				if (size == 0) {
					break;
				}
				if (pos + size > _length) {
					throw new DnsException("label beyond the end of the message");
				}
				if (result.length() > 0) {
					result.append('.');
				}
				result.append(new String(_bytes, pos, size, StandardCharsets.UTF_8));
				pos += size;
			}
			_pos = resume >= 0 ? resume : pos;
			return result.toString();
		}

		private void check(int count) throws DnsException {
			if (_pos + count > _length) {
				throw new DnsException("message truncated");
			}
		}
	}

}
