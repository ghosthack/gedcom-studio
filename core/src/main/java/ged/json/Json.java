package ged.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Just enough JSON for the daemon's API, without a library.
///
/// [#write] takes maps (keys in their own order), collections, strings,
/// numbers, booleans, `null` and [Optional]s. [#parse] gives back
/// [LinkedHashMap]s, [ArrayList]s, strings, [Long]s (whole numbers) or
/// [Double]s, booleans and `null`, and refuses anything that isn't JSON.
public final class Json {
    private Json() {}

    /// A JSON object in the order its keys are given: `obj("a", 1, "b", 2)`.
    public static Map<String, Object> obj(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) throw new IllegalArgumentException("odd number of arguments");
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return m;
    }

    public static String write(Object value) {
        var sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case Optional<?> o -> write(sb, o.orElse(null));
            case String s -> string(sb, s);
            case Boolean b -> sb.append(b);
            case Double d -> sb.append(d.isNaN() || d.isInfinite() ? "null" : d.toString());
            case Float f -> write(sb, f.doubleValue());
            case Number n -> sb.append(n);
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (var e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    string(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            }
            case Collection<?> c -> {
                sb.append('[');
                boolean first = true;
                for (var x : c) {
                    if (!first) sb.append(',');
                    first = false;
                    write(sb, x);
                }
                sb.append(']');
            }
            case Object[] a -> write(sb, List.of(a));
            case Enum<?> e -> string(sb, e.name());
            default -> string(sb, v.toString());
        }
    }

    private static void string(StringBuilder sb, String s) {
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
                    // < and > too, so JSON dropped into a page can't close a <script>
                    if (c < 0x20 || c == '<' || c == '>' || c == ' ' || c == ' ') sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /// What went wrong reading JSON, and where.
    public static final class ParseException extends IllegalArgumentException {
        ParseException(String message, int at) { super(message + " at character " + (at + 1)); }
    }

    public static Object parse(String text) {
        var p = new Parser(text);
        p.space();
        var v = p.value(0);
        p.space();
        if (p.i != text.length()) throw new ParseException("unexpected text after the value", p.i);
        return v;
    }

    /// Parses a JSON object (the body of an API request).
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        if (text.isBlank()) return new LinkedHashMap<>();
        if (parse(text) instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new ParseException("expected a JSON object", 0);
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 64;
        final String s;
        int i;

        Parser(String s) { this.s = s; }

        void space() {
            while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) throw new ParseException("nested too deeply", i);
            if (i >= s.length()) throw new ParseException("unexpected end", i);
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield number();
                    throw new ParseException("unexpected '" + c + "'", i);
                }
            };
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, i)) throw new ParseException("unexpected '" + s.charAt(i) + "'", i);
            i += word.length();
            return v;
        }

        Map<String, Object> object(int depth) {
            var m = new LinkedHashMap<String, Object>();
            i++;
            space();
            if (peek('}')) { i++; return m; }
            while (true) {
                space();
                if (!peek('"')) throw new ParseException("expected a key", i);
                var k = string();
                space();
                expect(':');
                space();
                m.put(k, value(depth + 1));
                space();
                if (peek(',')) { i++; continue; }
                expect('}');
                return m;
            }
        }

        List<Object> array(int depth) {
            var a = new ArrayList<Object>();
            i++;
            space();
            if (peek(']')) { i++; return a; }
            while (true) {
                space();
                a.add(value(depth + 1));
                space();
                if (peek(',')) { i++; continue; }
                expect(']');
                return a;
            }
        }

        String string() {
            expect('"');
            var sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new ParseException("unterminated string", i);
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c < 0x20) throw new ParseException("control character in string", i - 1);
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw new ParseException("unterminated string", i);
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw new ParseException("bad \\u escape", i);
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException x) {
                            throw new ParseException("bad \\u escape", i);
                        }
                        i += 4;
                    }
                    default -> throw new ParseException("bad escape \\" + e, i - 1);
                }
            }
        }

        Object number() {
            int start = i;
            if (peek('-')) i++;
            digits();
            boolean whole = true;
            if (peek('.')) { whole = false; i++; digits(); }
            if (peek('e') || peek('E')) {
                whole = false;
                i++;
                if (peek('+') || peek('-')) i++;
                digits();
            }
            var t = s.substring(start, i);
            try {
                return whole ? (Object) Long.parseLong(t) : (Object) Double.parseDouble(t);
            } catch (NumberFormatException e) {
                return Double.parseDouble(t);
            }
        }

        void digits() {
            int start = i;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i == start) throw new ParseException("expected a digit", i);
        }

        boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }

        void expect(char c) {
            if (!peek(c)) throw new ParseException("expected '" + c + "'", i);
            i++;
        }
    }
}
