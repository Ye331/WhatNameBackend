package com.whatname.iam.repository.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.query.NativeQuery;
import org.springframework.dao.IncorrectResultSizeDataAccessException;

import java.lang.reflect.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/** Native JPA queries for MySQL-specific locks and immutable security snapshots. */
public abstract class JpaSqlSupport {
    @PersistenceContext protected EntityManager entityManager;

    /** Values are always bound; only repository-owned SQL fragments are appended. */
    protected static final class Sql {
        private final StringBuilder text = new StringBuilder();
        private final List<Object> values = new ArrayList<>();

        public Sql() {}
        public void append(String fragment) { text.append(' ').append(fragment).append(' '); }
        public void param(Object value) {
            values.add(value);
            text.append(":p").append(values.size()).append(' ');
        }
        public void trimComma() {
            while (!text.isEmpty() && Character.isWhitespace(text.charAt(text.length() - 1)))
                text.setLength(text.length() - 1);
            if (!text.isEmpty() && text.charAt(text.length() - 1) == ',')
                text.setLength(text.length() - 1);
        }
    }

    private NativeQuery<?> query(Sql sql) {
        var query = entityManager.createNativeQuery(sql.text.toString()).unwrap(NativeQuery.class);
        for (int i = 0; i < sql.values.size(); i++) query.setParameter("p" + (i + 1), sql.values.get(i));
        return query;
    }

    protected <T> List<T> list(Sql sql, Class<T> type) {
        return query(sql).setTupleTransformer((tuple, aliases) -> row(type, tuple, aliases)).getResultList();
    }

    protected <T> T one(Sql sql, Class<T> type) {
        var rows = list(sql, type);
        if (rows.size() > 1) throw new IncorrectResultSizeDataAccessException(1, rows.size());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    protected int update(Sql sql) {
        // Native DML bypasses dirty checking. Flush first, then evict stale managed entities.
        entityManager.flush();
        int changed;
        try {
            changed = query(sql).executeUpdate();
        } catch (RuntimeException failure) {
            // Hibernate normally groups unique-key and other constraint errors together.
            // Keep duplicate-key handling precise for username conflicts and nonce replay.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.sql.SQLException jdbc && jdbc.getErrorCode() == 1062)
                    throw new org.springframework.dao.DuplicateKeyException("Duplicate database key", failure);
            }
            throw failure;
        }
        entityManager.clear();
        return changed;
    }

    protected Long lastInsertId() {
        // Repository writes run in a transaction, so this is the same connection as INSERT.
        return ((Number) entityManager.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    protected static Object property(Object bean, String name) {
        if (bean == null) return null;
        if (bean instanceof Map<?, ?> map) return map.get(name);
        try {
            if (bean.getClass().isRecord()) return bean.getClass().getMethod(name).invoke(bean);
            var field = bean.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(bean);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Unknown persistence property: " + name, e);
        }
    }

    protected static void setProperty(Object bean, String name, Object value) {
        try {
            var field = bean.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(bean, convert(value, field.getType()));
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Cannot map persistence property: " + name, e);
        }
    }

    private static String key(String value) { return value.replace("_", "").toLowerCase(Locale.ROOT); }

    @SuppressWarnings("unchecked")
    private static <T> T row(Class<T> type, Object[] tuple, String[] aliases) {
        if (Map.class.isAssignableFrom(type)) {
            var result = new LinkedHashMap<String, Object>();
            for (int i = 0; i < aliases.length; i++) {
                Object value = tuple[i];
                if (value instanceof Byte || value instanceof Short) value = ((Number) value).intValue();
                if (value != null) result.put(aliases[i], value);
            }
            return (T) result;
        }
        if (type.isPrimitive() || type.getName().startsWith("java.")) return (T) convert(tuple[0], type);
        var columns = new HashMap<String, Object>();
        for (int i = 0; i < aliases.length; i++) columns.put(key(aliases[i]), tuple[i]);
        try {
            if (type.isRecord()) {
                var components = type.getRecordComponents();
                Class<?>[] types = new Class<?>[components.length];
                Object[] values = new Object[components.length];
                for (int i = 0; i < components.length; i++) {
                    types[i] = components[i].getType();
                    values[i] = convert(columns.get(key(components[i].getName())), types[i]);
                }
                return type.getDeclaredConstructor(types).newInstance(values);
            }
            T result = type.getDeclaredConstructor().newInstance();
            for (var field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && columns.containsKey(key(field.getName()))) {
                    field.setAccessible(true);
                    field.set(result, convert(columns.get(key(field.getName())), field.getType()));
                }
            }
            return result;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot map JPA query result to " + type.getSimpleName(), e);
        }
    }

    private static Object convert(Object value, Class<?> type) {
        if (value == null || type.isInstance(value)) return value;
        if (value instanceof Number number) {
            if (type == Long.class || type == long.class) return number.longValue();
            if (type == Integer.class || type == int.class) return number.intValue();
            if (type == Boolean.class || type == boolean.class) return number.intValue() != 0;
        }
        if (value instanceof Timestamp timestamp && type == LocalDateTime.class) return timestamp.toLocalDateTime();
        if (type == String.class) return value.toString();
        return value;
    }
}
