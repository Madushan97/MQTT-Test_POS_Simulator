package com.hnb.wireme.tms.simulator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.Map;

/** Pretty-printing for the UI's payload/response panes. */
final class Json {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();
    private static final Gson COMPACT = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private Json() {
    }

    static String pretty(Object value) {
        return PRETTY.toJson(value);
    }

    /** Re-indents JSON text; anything that isn't a JSON object/array is returned unchanged. */
    static String pretty(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        if (!(t.startsWith("{") || t.startsWith("["))) {
            return text;
        }
        try {
            return PRETTY.toJson(JsonParser.parseString(t));
        } catch (RuntimeException e) {
            return text;
        }
    }

    /** A JSON object as a map, or null when the text isn't one. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        try {
            JsonElement e = JsonParser.parseString(text);
            return e.isJsonObject() ? COMPACT.fromJson(e, Map.class) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
