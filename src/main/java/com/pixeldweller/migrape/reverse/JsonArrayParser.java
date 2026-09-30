package com.pixeldweller.migrape.reverse;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Liest die JSON-Arrays zurueck, als die der Migrator H2-ARRAY-Werte in MariaDB ablegt
 *  (siehe BatchInserter): Zahlen, Strings, true/false, null und verschachtelte Arrays.
 *  Ergebnis ist ein Object[], das H2 selbst in den Elementtyp der Spalte umwandelt.
 *  JSON-Objekte kommen in solchen Spalten nicht vor und werden abgewiesen. */
final class JsonArrayParser {

    private final String text;
    private int pos;

    private JsonArrayParser(String text) {
        this.text = text;
    }

    static Object[] parse(String text) {
        JsonArrayParser parser = new JsonArrayParser(text);
        parser.skipWhitespace();
        Object[] result = parser.array();
        parser.skipWhitespace();
        if (parser.pos != text.length()) {
            throw parser.error("unerwartete Zeichen nach dem Array");
        }
        return result;
    }

    private Object[] array() {
        expect('[');
        List<Object> elements = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return elements.toArray();
        }
        while (true) {
            skipWhitespace();
            elements.add(value());
            skipWhitespace();
            char ch = next();
            if (ch == ']') {
                return elements.toArray();
            }
            if (ch != ',') {
                throw error("',' oder ']' erwartet");
            }
        }
    }

    private Object value() {
        char ch = peek();
        if (ch == '[') {
            return array();
        }
        if (ch == '"') {
            return string();
        }
        if (text.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        if (text.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        if (ch == '-' || Character.isDigit(ch)) {
            return number();
        }
        throw error("Wert erwartet");
    }

    private Object number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        String literal = text.substring(start, pos);
        try {
            BigDecimal value = new BigDecimal(literal);
            // Ganzzahlen als Long, damit H2 sie ohne Umweg in INTEGER/BIGINT-Arrays uebernimmt.
            if (literal.indexOf('.') < 0 && literal.indexOf('e') < 0 && literal.indexOf('E') < 0
                    && value.unscaledValue().bitLength() < 64) {
                return value.longValueExact();
            }
            return value;
        } catch (NumberFormatException | ArithmeticException e) {
            throw error("ungueltige Zahl '" + literal + "'");
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char ch = next();
            if (ch == '"') {
                return sb.toString();
            }
            if (ch != '\\') {
                sb.append(ch);
                continue;
            }
            char escaped = next();
            switch (escaped) {
                case '"', '\\', '/' -> sb.append(escaped);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw error("unvollstaendige \\u-Sequenz");
                    }
                    try {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException e) {
                        throw error("ungueltige \\u-Sequenz");
                    }
                    pos += 4;
                }
                default -> throw error("unbekannte Escape-Sequenz \\" + escaped);
            }
        }
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= text.length()) {
            throw error("unerwartetes Ende");
        }
        return text.charAt(pos);
    }

    private char next() {
        char ch = peek();
        pos++;
        return ch;
    }

    private void expect(char expected) {
        if (next() != expected) {
            pos--;
            throw error("'" + expected + "' erwartet");
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Kein gueltiges JSON-Array (" + message + " an Position "
                + pos + "): " + abbreviate(text));
    }

    private static String abbreviate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }
}
