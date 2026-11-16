/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.query;

import static com.top_logic.knowledge.search.ExpressionFactory.allOf;
import static com.top_logic.knowledge.search.ExpressionFactory.and;
import static com.top_logic.knowledge.search.ExpressionFactory.attribute;
import static com.top_logic.knowledge.search.ExpressionFactory.destination;
import static com.top_logic.knowledge.search.ExpressionFactory.eqBinary;
import static com.top_logic.knowledge.search.ExpressionFactory.eqCi;
import static com.top_logic.knowledge.search.ExpressionFactory.filter;
import static com.top_logic.knowledge.search.ExpressionFactory.flex;
import static com.top_logic.knowledge.search.ExpressionFactory.ge;
import static com.top_logic.knowledge.search.ExpressionFactory.le;
import static com.top_logic.knowledge.search.ExpressionFactory.literal;
import static com.top_logic.knowledge.search.ExpressionFactory.map;
import static com.top_logic.knowledge.search.ExpressionFactory.order;
import static com.top_logic.knowledge.search.ExpressionFactory.orderDesc;
import static com.top_logic.knowledge.search.ExpressionFactory.params;
import static com.top_logic.knowledge.search.ExpressionFactory.queryResolved;
import static com.top_logic.knowledge.search.ExpressionFactory.reference;
import static com.top_logic.knowledge.search.ExpressionFactory.revisionArgs;
import static com.top_logic.knowledge.search.ExpressionFactory.source;
import static com.top_logic.knowledge.search.ExpressionFactory.substraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import com.top_logic.basic.col.CloseableIterator;
import com.top_logic.dob.MOAttribute;
import com.top_logic.dob.attr.MOPrimitive;
import com.top_logic.dob.ex.UnknownTypeException;
import com.top_logic.dob.meta.MOClass;
import com.top_logic.element.meta.AssociationStorage;
import com.top_logic.element.meta.AttributeOperations;
import com.top_logic.element.meta.kbbased.WrapperMetaAttributeUtil;
import com.top_logic.element.meta.kbbased.storage.ColumnStorage;
import com.top_logic.knowledge.search.BranchParam;
import com.top_logic.knowledge.search.Expression;
import com.top_logic.knowledge.search.RangeParam;
import com.top_logic.knowledge.search.SetExpression;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelUtil;

/**
 * Lookups the database answers, so that the application reads only the objects found, never all
 * objects of a type (haumacher/ehome #172: no code path that runs per request, per page or per
 * controller connection iterates over all projects, controllers, accounts or rules).
 *
 * <p>
 * The condition of a lookup is part of the query the knowledge base compiles to SQL: a column of the
 * type's table, or a flex attribute joined from the table of the flex data. The database compares
 * the values row by row unless the column has an index; the application loads only the rows that
 * match. An exact value is found with {@link AttributeOperations#instancesWithValue(TLClass, String,
 * Object, Class)}; this class adds the comparisons that does not offer. Like it, a lookup finds only
 * committed objects.
 * </p>
 */
public final class Queries {

	private Queries() {
		// Static methods only.
	}

	/**
	 * The objects of the type (and its specializations) whose text attribute equals the given text,
	 * ignoring case (the database's <code>LOWER</code> on both sides).
	 *
	 * @param typeName
	 *        The qualified name of the type.
	 * @param attributeName
	 *        A text attribute stored with the object (a column or a flex attribute).
	 * @param text
	 *        The text, not <code>null</code>.
	 */
	public static List<TLObject> withTextIgnoringCase(String typeName, String attributeName, String text) {
		List<TLObject> result = new ArrayList<>();
		forEach(type(typeName), attributeName, stored -> eqCi(stored, literal(text)), null, found -> {
			if (found.tValueByName(attributeName) instanceof String value && value.equalsIgnoreCase(text)) {
				result.add(found);
			}
			return true;
		});
		return result;
	}

	/**
	 * The object of the type (and its specializations) with the greatest value of the text attribute
	 * in the given range that is accepted, <code>null</code> if there is none. The database orders the
	 * objects of each table in the range downwards, the application reads them until the first one is
	 * accepted.
	 *
	 * @param typeName
	 *        The qualified name of the type.
	 * @param attributeName
	 *        A text attribute stored with the object (a column or a flex attribute).
	 * @param from
	 *        The lower bound of the values (inclusive).
	 * @param to
	 *        The upper bound of the values (inclusive).
	 * @param accept
	 *        Whether an object found counts (e.g. whether its value has the expected shape).
	 * @param compare
	 *        The value an object is compared with among the tables: its greatest one wins.
	 */
	public static TLObject greatest(String typeName, String attributeName, String from, String to,
			Predicate<TLObject> accept, Function<TLObject, Long> compare) {
		TLObject[] best = new TLObject[1];
		forEach(type(typeName), attributeName,
			stored -> and(ge(stored, literal(from)), le(stored, literal(to))),
			Boolean.TRUE, found -> {
				if (!accept.test(found)) {
					return true;
				}
				if (best[0] == null || compare.apply(found).longValue() > compare.apply(best[0]).longValue()) {
					best[0] = found;
				}
				// The first accepted object is the greatest of its table.
				return false;
			});
		return best[0];
	}

	/**
	 * Whether any object references the given one by the given reference: one row of the link table
	 * read, however many there are.
	 *
	 * @param target
	 *        The object referenced.
	 * @param reference
	 *        The reference, stored in a link table.
	 */
	public static boolean hasReferer(TLObject target, TLReference reference) {
		return firstReferer(target, reference) != null;
	}

	/**
	 * Some object referencing the given one by the given reference, <code>null</code> if there is
	 * none: the source of one row of the link table, however many there are. Which one is not
	 * defined.
	 *
	 * @param target
	 *        The object referenced.
	 * @param reference
	 *        The reference, stored in a link table. An inherited reference stands for its
	 *        definition: the referer may be of any type holding it.
	 */
	public static TLObject firstReferer(TLObject target, TLReference reference) {
		AssociationStorage storage = linkStorage(reference);
		KnowledgeBase kb = kb();
		String linkTable = storage.getTable();
		Expression condition = eqBinary(destination(), literal(target.tHandle()));
		if (!storage.monomophicTable()) {
			condition = and(condition, eqBinary(reference(linkTable, WrapperMetaAttributeUtil.META_ATTRIBUTE_ATTR),
				literal(reference.getDefinition().tHandle())));
		}
		SetExpression sources = map(filter(allOf(table(kb, linkTable)), condition), source());
		try (CloseableIterator<TLObject> found =
			kb.searchStream(queryResolved(BranchParam.single, RangeParam.head, params(), sources, null, TLObject.class),
				revisionArgs().setStopRow(1))) {
			return found.hasNext() ? found.next() : null;
		}
	}

	/**
	 * The objects of the type (and its specializations) that have no value in the given reference:
	 * the database subtracts the sources of the reference's links from the type's table.
	 *
	 * @param typeName
	 *        The qualified name of the type.
	 * @param reference
	 *        A reference of the type, stored in a link table.
	 */
	public static List<TLObject> withoutValue(String typeName, TLReference reference) {
		AssociationStorage storage = linkStorage(reference);
		KnowledgeBase kb = kb();
		String linkTable = storage.getTable();
		SetExpression links = allOf(table(kb, linkTable));
		if (!storage.monomophicTable()) {
			links = filter(links, eqBinary(reference(linkTable, WrapperMetaAttributeUtil.META_ATTRIBUTE_ATTR),
				literal(reference.getDefinition().tHandle())));
		}
		SetExpression sources = map(links, source());
		List<TLObject> result = new ArrayList<>();
		for (Entry<String, Set<TLClass>> entry : AttributeOperations.typesByTableName(type(typeName)).entrySet()) {
			SetExpression query =
				substraction(AttributeOperations.tableQuery(table(kb, entry.getKey()), entry.getValue()), sources);
			result.addAll(kb.search(queryResolved(query, TLObject.class)));
		}
		return result;
	}

	/**
	 * Runs a query per table of the type: the objects whose attribute fulfils the condition, ordered
	 * by the attribute if an order is given, until the visitor returns <code>false</code>.
	 */
	private static void forEach(TLClass type, String attributeName, Function<Expression, Expression> condition,
			Boolean descending, Predicate<TLObject> visitor) {
		KnowledgeBase kb = kb();
		for (Entry<String, Set<TLClass>> entry : AttributeOperations.typesByTableName(type).entrySet()) {
			MOClass table = table(kb, entry.getKey());
			TLStructuredTypePart attribute = entry.getValue().iterator().next().getPartOrFail(attributeName);
			if (!(attribute.getStorageImplementation() instanceof ColumnStorage storage)) {
				throw new IllegalArgumentException("Not stored with its object: " + attribute);
			}
			String storageAttribute = storage.getStorageAttribute();
			MOAttribute column = table.getAttributeOrNull(storageAttribute);
			Expression stored = column != null ? attribute(column) : flex(MOPrimitive.STRING, storageAttribute);
			SetExpression query = filter(AttributeOperations.tableQuery(table, entry.getValue()), condition.apply(stored));
			try (CloseableIterator<TLObject> found = kb.searchStream(queryResolved(BranchParam.single,
				RangeParam.complete, params(), query,
				descending == null ? null : descending.booleanValue() ? orderDesc(stored) : order(stored),
				TLObject.class))) {
				while (found.hasNext()) {
					if (!visitor.test(found.next())) {
						break;
					}
				}
			}
		}
	}

	private static AssociationStorage linkStorage(TLReference reference) {
		if (!(reference.getStorageImplementation() instanceof AssociationStorage storage)) {
			throw new IllegalStateException("Not stored in a link table: " + reference);
		}
		return storage;
	}

	private static TLClass type(String typeName) {
		return (TLClass) TLModelUtil.findType(typeName);
	}

	private static MOClass table(KnowledgeBase kb, String name) {
		try {
			return (MOClass) kb.getMORepository().getType(name);
		} catch (UnknownTypeException ex) {
			throw new IllegalStateException("No table " + name + ".", ex);
		}
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

}
