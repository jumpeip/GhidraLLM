package ghidrallm.analysis;

import java.util.*;
import java.util.regex.Pattern;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;

/**
 * Tolerant parser for the model's JSON answer: strips reasoning blocks and markdown fences,
 * finds the first balanced JSON object, accepts several key spellings and sanitizes names.
 */
public final class ResponseParser {

	private static final Set<String> C_KEYWORDS = Set.of("auto", "break", "case", "char",
		"const", "continue", "default", "do", "double", "else", "enum", "extern", "float", "for",
		"goto", "if", "inline", "int", "long", "register", "restrict", "return", "short",
		"signed", "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void",
		"volatile", "while", "bool", "true", "false", "class", "new", "delete", "this",
		"template", "typename", "namespace", "operator", "private", "public", "protected",
		"virtual", "NULL", "nullptr");

	private static final Pattern DEFAULT_FUNC =
		Pattern.compile("^(FUN|SUB|sub|thunk_FUN|LAB|DAT|UNK)_[0-9A-Fa-f]+$");

	/** Ghidra's auto-generated variable names. */
	public static final Pattern DEFAULT_VAR = Pattern.compile(
		"^(param_\\d+|local_\\w+|[a-z]{1,5}Var\\d+|[a-z]{0,5}Stack_?\\w*|in_\\w+|extraout_\\w+|" +
			"unaff_\\w+|stack0x\\w+|Var\\d+|in_stack_\\w+|local_res\\w*)$");

	private ResponseParser() {
	}

	public static RenameSuggestion parse(String text) throws IllegalArgumentException {
		RenameSuggestion s = new RenameSuggestion();
		s.rawResponse = text;
		String json = extractJson(text);
		if (json == null) {
			throw new IllegalArgumentException("No JSON object found in the model response");
		}
		JsonObject o;
		try {
			JsonReader r = new JsonReader(new java.io.StringReader(stripTrailingCommas(json)));
			r.setStrictness(Strictness.LENIENT);
			JsonElement el = JsonParser.parseReader(r);
			if (!el.isJsonObject()) {
				throw new IllegalArgumentException("Model response JSON is not an object");
			}
			o = el.getAsJsonObject();
		}
		catch (JsonParseException e) {
			throw new IllegalArgumentException("Model returned malformed JSON: " + e.getMessage());
		}

		String fn = firstString(o, "function_name", "functionName", "new_function_name", "name");
		if (fn != null) {
			String clean = sanitize(fn);
			if (clean == null || DEFAULT_FUNC.matcher(clean).matches()) {
				s.warnings.add("ignored function name '" + fn + "'");
			}
			else {
				s.functionName = clean;
			}
		}
		s.summary = firstString(o, "summary", "description", "purpose", "comment");
		JsonElement conf = o.get("confidence");
		if (conf != null && conf.isJsonPrimitive()) {
			try {
				double d = conf.getAsDouble();
				s.confidence = d > 1.0 && d <= 100 ? d / 100.0 : d;
			}
			catch (NumberFormatException e) {
				// ignore
			}
		}
		for (String key : new String[] { "renames", "variables", "parameters", "params",
			"locals", "variable_renames" }) {
			collectRenames(o.get(key), s);
		}
		return s;
	}

	private static void collectRenames(JsonElement el, RenameSuggestion s) {
		if (el == null || el.isJsonNull()) {
			return;
		}
		if (el.isJsonArray()) {
			for (JsonElement e : el.getAsJsonArray()) {
				if (!e.isJsonObject()) {
					continue;
				}
				JsonObject r = e.getAsJsonObject();
				String oldName = firstString(r, "old", "old_name", "from", "original", "name");
				String newName = firstString(r, "new", "new_name", "to", "rename", "suggested");
				addRename(s, oldName, newName);
			}
		}
		else if (el.isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
				if (e.getValue().isJsonPrimitive()) {
					addRename(s, e.getKey(), e.getValue().getAsString());
				}
			}
		}
	}

	private static void addRename(RenameSuggestion s, String oldName, String newName) {
		if (oldName == null || newName == null) {
			return;
		}
		oldName = oldName.trim();
		String clean = sanitize(newName);
		if (clean == null || clean.equals(oldName) || DEFAULT_VAR.matcher(clean).matches()) {
			if (clean == null || !clean.equals(oldName)) {
				s.warnings.add("ignored rename " + oldName + " -> " + newName);
			}
			return;
		}
		s.renames.putIfAbsent(oldName, clean);
	}

	private static String firstString(JsonObject o, String... keys) {
		for (String k : keys) {
			JsonElement e = o.get(k);
			if (e != null && e.isJsonPrimitive()) {
				String v = e.getAsString();
				if (v != null && !v.isBlank()) {
					return v.trim();
				}
			}
		}
		return null;
	}

	/** Turns a model-proposed name into a valid identifier, or null if hopeless. */
	public static String sanitize(String name) {
		if (name == null) {
			return null;
		}
		String n = name.trim();
		// "Namespace::method" -> keep the last component
		int ns = n.lastIndexOf("::");
		if (ns >= 0) {
			n = n.substring(ns + 2);
		}
		int paren = n.indexOf('(');
		if (paren > 0) {
			n = n.substring(0, paren);
		}
		n = n.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_{2,}", "_");
		n = n.replaceAll("^_+(?=[A-Za-z])", "");
		if (n.isEmpty() || n.matches("_*")) {
			return null;
		}
		if (Character.isDigit(n.charAt(0))) {
			n = "_" + n;
		}
		if (n.length() > 64) {
			n = n.substring(0, 64).replaceAll("_+$", "");
		}
		if (C_KEYWORDS.contains(n)) {
			n = n + "_";
		}
		return n;
	}

	/** Returns the first balanced {...} JSON object in the text, or null. */
	public static String extractJson(String text) {
		if (text == null) {
			return null;
		}
		String t = text.replaceAll("(?s)<think>.*?</think>", "")
				.replaceAll("(?s)<thinking>.*?</thinking>", "");
		// prefer the contents of a ```json fence if present
		java.util.regex.Matcher m =
			Pattern.compile("(?s)```(?:json|JSON)?\\s*(\\{.*?\\})\\s*```").matcher(t);
		if (m.find()) {
			String inner = balanced(m.group(1), 0);
			if (inner != null) {
				return inner;
			}
		}
		int start = t.indexOf('{');
		while (start >= 0) {
			String b = balanced(t, start);
			if (b != null) {
				return b;
			}
			start = t.indexOf('{', start + 1);
		}
		return null;
	}

	/** Removes commas that directly precede '}' or ']' (outside of strings). */
	static String stripTrailingCommas(String json) {
		StringBuilder out = new StringBuilder(json.length());
		boolean inStr = false;
		boolean esc = false;
		char quote = 0;
		for (int i = 0; i < json.length(); i++) {
			char ch = json.charAt(i);
			if (inStr) {
				out.append(ch);
				if (esc) {
					esc = false;
				}
				else if (ch == '\\') {
					esc = true;
				}
				else if (ch == quote) {
					inStr = false;
				}
				continue;
			}
			if (ch == '"' || ch == '\'') {
				inStr = true;
				quote = ch;
			}
			else if (ch == ',') {
				int j = i + 1;
				while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
					j++;
				}
				if (j < json.length() && (json.charAt(j) == '}' || json.charAt(j) == ']')) {
					continue; // drop it
				}
			}
			out.append(ch);
		}
		return out.toString();
	}

	private static String balanced(String t, int start) {
		int depth = 0;
		boolean inStr = false;
		boolean esc = false;
		for (int i = start; i < t.length(); i++) {
			char ch = t.charAt(i);
			if (inStr) {
				if (esc) {
					esc = false;
				}
				else if (ch == '\\') {
					esc = true;
				}
				else if (ch == '"') {
					inStr = false;
				}
				continue;
			}
			if (ch == '"') {
				inStr = true;
			}
			else if (ch == '{') {
				depth++;
			}
			else if (ch == '}') {
				depth--;
				if (depth == 0) {
					return t.substring(start, i + 1);
				}
			}
		}
		return null;
	}
}
