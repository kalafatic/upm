package upm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal dependency-free JSON parser/writer. */
public final class Json {

    public static final Object NULL = new Object();

    private Json() {
    }

    public static Object parse(String text) {
        P p = new P(text);
        Object v = p.value();
        p.ws();
        if (!p.eof()) {
            throw new IllegalArgumentException("Trailing content at " + p.pos());
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) {
        return (List<Object>) v;
    }

    public static String str(Map<String, Object> o, String key) {
        if (o == null) {
            return null;
        }
        Object v = o.get(key);
        return v instanceof String s ? s : null;
    }

    public static String str(Map<String, Object> o, String key, String dflt) {
        String s = str(o, key);
        return s == null ? dflt : s;
    }

    public static int integer(Map<String, Object> o, String key, int dflt) {
        if (o == null) {
            return dflt;
        }
        Object v = o.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return dflt;
    }

    public static long longVal(Map<String, Object> o, String key, long dflt) {
        if (o == null) {
            return dflt;
        }
        Object v = o.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        return dflt;
    }

    public static boolean bool(Map<String, Object> o, String key, boolean dflt) {
        if (o == null) {
            return dflt;
        }
        Object v = o.get(key);
        return v instanceof Boolean b ? b : dflt;
    }

    public static List<String> strList(Map<String, Object> o, String key) {
        if (o == null) {
            return new ArrayList<>();
        }
        Object v = o.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object e : l) {
                if (e instanceof String s) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    public static Map<String, Object> objField(Map<String, Object> o, String key) {
        Object v = o.get(key);
        return v instanceof Map<?, ?> m ? castObj(m) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castObj(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    public static List<Map<String, Object>> objList(Map<String, Object> o, String key) {
        Object v = o.get(key);
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object e : l) {
                if (e instanceof Map<?, ?> m) {
                    out.add(castObj(m));
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- writer

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null || v == NULL) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Number n) {
            if (n instanceof Double d && (d.isNaN() || d.isInfinite())) {
                sb.append("null");
            } else {
                sb.append(n);
            }
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object e : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, e);
            }
            sb.append(']');
        } else if (v instanceof Object[] arr) {
            sb.append('[');
            boolean first = true;
            for (Object e : arr) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, e);
            }
            sb.append(']');
        } else if (v instanceof long[] arr) {
            List<Long> l = new ArrayList<>();
            for (long e : arr) {
                l.add(e);
            }
            writeValue(sb, l);
        } else if (v instanceof int[] arr) {
            List<Integer> l = new ArrayList<>();
            for (int e : arr) {
                l.add(e);
            }
            writeValue(sb, l);
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ----------------------------------------------------------------- parse

    private static final class P {
        private final String s;
        private int i;

        P(String s) {
            this.s = s;
        }

        int pos() {
            return i;
        }

        boolean eof() {
            return i >= s.length();
        }

        void ws() {
            while (!eof()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        char peek() {
            return s.charAt(i);
        }

        Object value() {
            ws();
            if (eof()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char c = peek();
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't', 'f' -> boolLit();
                case 'n' -> nullLit();
                default -> number();
            };
        }

        Map<String, Object> object() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (!eof() && peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                m.put(key, value());
                ws();
                if (eof()) {
                    throw new IllegalArgumentException("Unterminated object");
                }
                char c = peek();
                if (c == ',') {
                    i++;
                } else if (c == '}') {
                    i++;
                    return m;
                } else {
                    throw new IllegalArgumentException("Expected , or } at " + i);
                }
            }
        }

        List<Object> array() {
            expect('[');
            List<Object> l = new ArrayList<>();
            ws();
            if (!eof() && peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                if (eof()) {
                    throw new IllegalArgumentException("Unterminated array");
                }
                char c = peek();
                if (c == ',') {
                    i++;
                } else if (c == ']') {
                    i++;
                    return l;
                } else {
                    throw new IllegalArgumentException("Expected , or ] at " + i);
                }
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw new IllegalArgumentException("Unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (eof()) {
                        throw new IllegalArgumentException("Bad escape");
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw new IllegalArgumentException("Bad unicode escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("Bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object boolLit() {
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Bad literal at " + i);
        }

        Object nullLit() {
            if (s.startsWith("null", i)) {
                i += 4;
                return NULL;
            }
            throw new IllegalArgumentException("Bad literal at " + i);
        }

        Object number() {
            int start = i;
            if (!eof() && (peek() == '-' || peek() == '+')) {
                i++;
            }
            while (!eof() && (Character.isDigit(peek()) || peek() == '.' || peek() == 'e'
                    || peek() == 'E' || peek() == '-' || peek() == '+')) {
                i++;
            }
            String t = s.substring(start, i);
            try {
                if (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0) {
                    return Double.parseDouble(t);
                }
                long l = Long.parseLong(t);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return (int) l;
                }
                return l;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad number: " + t);
            }
        }

        void expect(char c) {
            if (eof() || s.charAt(i) != c) {
                throw new IllegalArgumentException("Expected '" + c + "' at " + i);
            }
            i++;
        }
    }

    /** Strips JSONC comments (line comments and block comments) outside strings. */
    public static String stripComments(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean inStr = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                sb.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    sb.append(text.charAt(++i));
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
                sb.append(c);
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
                if (i < text.length()) {
                    sb.append('\n');
                }
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < text.length() && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
                    i++;
                }
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
