#!/usr/bin/env python3
"""Compares a KNX project schema (XSD) with the ETS import mapping (etsImporter.xml).

Prints, as Markdown, every element path of the schema starting at <KNX> and, for each element,
whether the import handles it, which model types it creates and which attributes it reads.

Usage: schema-coverage.py KNXSchema.xsd src/main/java/de/haumacher/ehome/importer/etsImporter.xml

Recursive schema types are expanded once. Import handlers are matched by the last two path
segments (parent and element name), because the mapping reuses handlers (<ref handler-id>) at
several depths. This can show an element as handled below a parent that is not.
"""
import sys
import xml.etree.ElementTree as ET

XS = '{http://www.w3.org/2001/XMLSchema}'


def schema_rows(xsd_file):
	xsd = ET.parse(xsd_file).getroot()
	types = {t.get('name'): t for t in xsd.findall(XS + 'complexType')}

	def local(q):
		return q.split(':')[-1] if q else None

	def content(ct):
		attrs, kids = [], []

		def walk(n):
			for c in n:
				if c.tag == XS + 'attribute':
					attrs.append(c.get('name'))
				elif c.tag == XS + 'element':
					kids.append(c)
				elif c.tag in (XS + 'sequence', XS + 'choice', XS + 'all', XS + 'complexContent', XS + 'simpleContent'):
					walk(c)
				elif c.tag == XS + 'extension':
					base = local(c.get('base'))
					if base in types:
						a, k = content(types[base])
						attrs.extend(a)
						kids.extend(k)
					walk(c)
		walk(ct)
		return attrs, kids

	rows = []

	def visit(el, path, stack):
		p = path + '/' + el.get('name')
		t = local(el.get('type'))
		ct = el.find(XS + 'complexType')
		if ct is None and t in types:
			ct = types[t]
		row = {'path': p, 'attrs': [], 'recursive': False}
		rows.append(row)
		if ct is None:
			return
		if t and t in stack:
			row['recursive'] = True
			return
		row['attrs'], kids = content(ct)
		for c in kids:
			visit(c, p, stack | ({t} if t else set()))

	visit(xsd.find(XS + "element[@name='KNX']"), '', set())
	return rows


def import_coverage(mapping_file):
	root = ET.parse(mapping_file).getroot()
	handlers = {e.get('id'): e for e in root.iter() if e.get('id') and e.tag in ('dispatch', 'object', 'handler')}
	cov = {}

	def node(p):
		return cov.setdefault(p, {'types': set(), 'attrs': {}})

	def walk(e, path, current, seen):
		for c in e:
			if c.tag == 'tag':
				p = path + '/' + c.get('name')
				node(p)
				walk(c, p, current, seen)
			elif c.tag == 'ref':
				h = c.get('handler-id')
				if h not in seen:
					walk_one(handlers[h], path, current, seen | {h})
			else:
				walk_one(c, path, current, seen)

	def walk_one(c, path, current, seen):
		n = node(path)
		if c.tag == 'object' and c.get('model-type'):
			current = c.get('model-type')
			n['types'].add(current.split(':')[-1])
		if c.tag == 'object-ref':
			n['types'].add('(existing object)')
		for a in ('xml-attribute', 'id-attribute'):
			if c.get(a):
				if c.tag in ('object', 'object-ref', 'with-result'):
					target = '(id)'
				elif c.tag == 'switch':
					target = '(type switch)'
				elif c.tag == 'reference':
					target = c.get('name')
				else:
					target = c.get('name')
				n['attrs'].setdefault(c.get(a), set()).add(target)
		walk(c, path, current, seen)

	walk(root, '', None, set())
	result = {}
	for p, v in cov.items():
		key = tuple(p.split('/')[-2:])
		e = result.setdefault(key, {'types': set(), 'attrs': {}})
		e['types'] |= v['types']
		for a, t in v['attrs'].items():
			e['attrs'].setdefault(a, set()).update(t)
	return result


def main(xsd_file, mapping_file):
	rows = schema_rows(xsd_file)
	cov = import_coverage(mapping_file)
	handled = attrs_total = attrs_mapped = 0
	lines = []
	for r in rows:
		m = cov.get(tuple(r['path'].split('/')[-2:]))
		mapped = [a for a in r['attrs'] if m and a in m['attrs']]
		missing = [a for a in r['attrs'] if not (m and a in m['attrs'])]
		handled += m is not None
		attrs_total += len(r['attrs'])
		attrs_mapped += len(mapped)
		types = ', '.join(sorted(m['types'])) if m else ''
		mapped_txt = ', '.join(f"{a}→{'/'.join(sorted(m['attrs'][a]))}" for a in mapped)
		lines.append(f"| {'✔' if m else '✘'} | `{r['path'][5:] or 'KNX'}`{' (recursion)' if r['recursive'] else ''} | {types} | {mapped_txt} | {', '.join(missing)} |")
	print(f"Elements handled: {handled} of {len(rows)}; attributes read: {attrs_mapped} of {attrs_total}.\n")
	print('| | Element path | Model types | Attributes read (→ model) | Attributes not read |')
	print('|---|---|---|---|---|')
	print('\n'.join(lines))


if __name__ == '__main__':
	main(*sys.argv[1:3])
