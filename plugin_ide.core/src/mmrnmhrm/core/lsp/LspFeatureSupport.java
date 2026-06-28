/*******************************************************************************
 * Copyright (c) 2024 Bruno Medeiros and other Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Bruno Medeiros - initial API and implementation
 *******************************************************************************/
package mmrnmhrm.core.lsp;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dtool.engine.operations.FindDefinitionResult;
import dtool.engine.operations.FindDefinitionResult.FindDefinitionResultEntry;
import melnorme.lang.tooling.CompletionProposalKind;
import melnorme.lang.tooling.ToolCompletionProposal;
import melnorme.lang.tooling.ast.SourceRange;
import melnorme.utilbox.collections.ArrayList2;
import melnorme.utilbox.misc.Location;

/**
 * Static helpers for converting between DDT operation types and LSP JSON messages
 * for code completion, hover, and go-to-definition.
 */
public class LspFeatureSupport {

	private LspFeatureSupport() {}

	/* -----------------  Position Utilities  ----------------- */

	/** Convert a byte-offset in {@code source} to LSP {@code {line, character}} (0-based). */
	public static JsonObject offsetToPosition(String source, int offset) {
		int line = 0, character = 0;
		int limit = Math.min(offset, source.length());
		for (int i = 0; i < limit; i++) {
			if (source.charAt(i) == '\n') {
				line++;
				character = 0;
			} else {
				character++;
			}
		}
		JsonObject pos = new JsonObject();
		pos.addProperty("line", line);
		pos.addProperty("character", character);
		return pos;
	}

	/** Convert LSP {@code {line, character}} (0-based) to a byte-offset in {@code source}. */
	public static int positionToOffset(String source, int line, int character) {
		int currentLine = 0;
		int i = 0;
		while (i < source.length() && currentLine < line) {
			if (source.charAt(i) == '\n') currentLine++;
			i++;
		}
		return Math.min(i + character, source.length());
	}

	/** Offset at which the identifier under (or just before) {@code offset} starts. */
	private static int wordStart(String source, int offset) {
		int i = Math.min(offset, source.length()) - 1;
		while (i >= 0 && isIdentChar(source.charAt(i))) i--;
		return i + 1;
	}

	/** Extract the identifier token that spans (or ends at) {@code offset}. */
	public static String wordAt(String source, int offset) {
		int start = wordStart(source, offset);
		int end = start;
		while (end < source.length() && isIdentChar(source.charAt(end))) end++;
		return source.substring(start, end);
	}

	private static boolean isIdentChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	/* -----------------  Completion  ----------------- */

	/**
	 * Send {@code textDocument/completion} to serve-d and convert the result to DDT proposals.
	 *
	 * @param router ready router
	 * @param uri    file URI (from {@code location.toUri().toString()})
	 * @param source current in-memory content of the file
	 * @param offset cursor offset in the source
	 * @return list of proposals (may be empty)
	 * @throws IOException on transport failure or LSP error response
	 */
	public static ArrayList2<ToolCompletionProposal> requestCompletion(
			LspMessageRouter router, String uri, String source, int offset) throws IOException {

		JsonObject textDocId = new JsonObject();
		textDocId.addProperty("uri", uri);
		JsonObject params = new JsonObject();
		params.add("textDocument", textDocId);
		params.add("position", offsetToPosition(source, offset));

		JsonObject response = router.sendRequest("textDocument/completion", params);
		JsonElement result = response.get("result");
		if (result == null || result.isJsonNull()) return new ArrayList2<>();

		JsonArray items;
		if (result.isJsonArray()) {
			items = result.getAsJsonArray();
		} else {
			// CompletionList { isIncomplete, items }
			JsonObject list = result.getAsJsonObject();
			items = list.has("items") ? list.getAsJsonArray("items") : new JsonArray();
		}

		int defaultRplOffset = wordStart(source, offset);
		int defaultRplLength = offset - defaultRplOffset;

		ArrayList2<ToolCompletionProposal> proposals = new ArrayList2<>();
		for (JsonElement elem : items) {
			if (elem.isJsonObject()) {
				proposals.add(itemToProposal(elem.getAsJsonObject(), source, defaultRplOffset, defaultRplLength));
			}
		}
		return proposals;
	}

	private static ToolCompletionProposal itemToProposal(
			JsonObject item, String source, int defaultRplOffset, int defaultRplLength) {

		String label = item.has("label") ? item.get("label").getAsString() : "";
		String insertText = item.has("insertText") ? item.get("insertText").getAsString() : label;
		String detail = item.has("detail") ? item.get("detail").getAsString() : null;

		int lspKind = item.has("kind") ? item.get("kind").getAsInt() : 0;

		int rplOffset = defaultRplOffset;
		int rplLength = defaultRplLength;

		// Use textEdit range when provided — it is the canonical replacement span
		if (item.has("textEdit") && !item.get("textEdit").isJsonNull()) {
			JsonObject edit = item.getAsJsonObject("textEdit");
			if (edit.has("range")) {
				JsonObject range = edit.getAsJsonObject("range");
				JsonObject start = range.getAsJsonObject("start");
				int startOffset = positionToOffset(source,
						start.get("line").getAsInt(), start.get("character").getAsInt());
				JsonObject end = range.getAsJsonObject("end");
				int endOffset = positionToOffset(source,
						end.get("line").getAsInt(), end.get("character").getAsInt());
				rplOffset = startOffset;
				rplLength = endOffset - startOffset;
			}
			if (edit.has("newText")) {
				insertText = edit.get("newText").getAsString();
			}
		}

		String documentation = null;
		if (item.has("documentation") && !item.get("documentation").isJsonNull()) {
			JsonElement doc = item.get("documentation");
			if (doc.isJsonPrimitive()) {
				documentation = doc.getAsString();
			} else if (doc.isJsonObject() && doc.getAsJsonObject().has("value")) {
				documentation = doc.getAsJsonObject().get("value").getAsString();
			}
		}

		return new ToolCompletionProposal(
				rplOffset, rplLength, insertText, label,
				lspKindToProposalKind(lspKind), null,
				detail, null, documentation);
	}

	private static CompletionProposalKind lspKindToProposalKind(int lspKind) {
		switch (lspKind) {
			case 2:  return CompletionProposalKind.FUNCTION;    // Method
			case 3:  return CompletionProposalKind.FUNCTION;    // Function
			case 4:  return CompletionProposalKind.CONSTRUCTOR; // Constructor
			case 5:  return CompletionProposalKind.VARIABLE;    // Field
			case 6:  return CompletionProposalKind.VARIABLE;    // Variable
			case 7:  return CompletionProposalKind.CLASS;       // Class
			case 8:  return CompletionProposalKind.INTERFACE;   // Interface
			case 9:  return CompletionProposalKind.MODULEDEC;   // Module
			case 10: return CompletionProposalKind.VARIABLE;    // Property
			case 13: return CompletionProposalKind.ENUM;        // Enum
			case 14: return CompletionProposalKind.KEYWORD;     // Keyword
			case 19: return CompletionProposalKind.PACKAGE;     // Folder
			case 20: return CompletionProposalKind.VARIABLE;    // EnumMember
			case 21: return CompletionProposalKind.VARIABLE;    // Constant
			case 22: return CompletionProposalKind.STRUCT;      // Struct
			case 25: return CompletionProposalKind.TYPE;        // TypeParameter
			default: return CompletionProposalKind.UNKNOWN;
		}
	}

	/* -----------------  Hover  ----------------- */

	/**
	 * Send {@code textDocument/hover} to serve-d and return the result as HTML.
	 *
	 * @return HTML string, or {@code null} if serve-d returns a null result
	 * @throws IOException on transport failure or LSP error response
	 */
	public static String requestHover(
			LspMessageRouter router, String uri, String source, int offset) throws IOException {

		JsonObject textDocId = new JsonObject();
		textDocId.addProperty("uri", uri);
		JsonObject params = new JsonObject();
		params.add("textDocument", textDocId);
		params.add("position", offsetToPosition(source, offset));

		JsonObject response = router.sendRequest("textDocument/hover", params);
		JsonElement result = response.get("result");
		if (result == null || result.isJsonNull()) return null;
		if (!result.isJsonObject()) return null;

		JsonObject hover = result.getAsJsonObject();
		if (!hover.has("contents")) return null;

		JsonElement contents = hover.get("contents");
		String text = extractContentsText(contents);
		if (text == null || text.isEmpty()) return null;

		return markdownToHtml(text);
	}

	private static String extractContentsText(JsonElement contents) {
		if (contents.isJsonPrimitive()) {
			return contents.getAsString();
		}
		if (contents.isJsonArray()) {
			// Deprecated array form — join all elements
			StringBuilder sb = new StringBuilder();
			for (JsonElement e : contents.getAsJsonArray()) {
				String piece = extractContentsText(e);
				if (piece != null && !piece.isEmpty()) {
					if (sb.length() > 0) sb.append("\n\n");
					sb.append(piece);
				}
			}
			return sb.toString();
		}
		// MarkupContent { kind, value }
		JsonObject mc = contents.getAsJsonObject();
		return mc.has("value") ? mc.get("value").getAsString() : "";
	}

	/** Lightweight markdown → HTML for hover display (handles the common subset serve-d emits). */
	static String markdownToHtml(String md) {
		if (md == null || md.isEmpty()) return "";

		// Escape HTML special chars first so our replacements don't get double-escaped
		String s = md.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");

		// Fenced code blocks
		s = s.replaceAll("(?s)```[^\n]*\n(.*?)```", "<pre>$1</pre>");
		// Inline code
		s = s.replaceAll("`([^`\n]+)`", "<code>$1</code>");
		// Bold
		s = s.replaceAll("\\*\\*([^*\n]+)\\*\\*", "<b>$1</b>");
		// Italic
		s = s.replaceAll("\\*([^*\n]+)\\*", "<i>$1</i>");
		// Headings → bold paragraph
		s = s.replaceAll("(?m)^#{1,6}[ \\t]+(.+)$", "<b>$1</b>");
		// Horizontal rules
		s = s.replaceAll("(?m)^[-*]{3,}$", "<hr/>");
		// Paragraph breaks and line breaks
		s = s.replace("\n\n", "<br><br>").replace("\n", "<br>");

		return s;
	}

	/* -----------------  Go-to-Definition  ----------------- */

	/**
	 * Send {@code textDocument/definition} to serve-d and convert the result to a
	 * {@link FindDefinitionResult}.
	 *
	 * @return result, or {@code null} if serve-d returns null / empty
	 * @throws IOException on transport failure or LSP error response
	 */
	public static FindDefinitionResult requestDefinition(
			LspMessageRouter router, String uri, String source, int offset) throws IOException {

		JsonObject textDocId = new JsonObject();
		textDocId.addProperty("uri", uri);
		JsonObject params = new JsonObject();
		params.add("textDocument", textDocId);
		params.add("position", offsetToPosition(source, offset));

		JsonObject response = router.sendRequest("textDocument/definition", params);
		JsonElement result = response.get("result");
		if (result == null || result.isJsonNull()) return null;

		// result can be Location | Location[] | LocationLink[] | null
		List<JsonObject> locations = new ArrayList<>();
		if (result.isJsonArray()) {
			for (JsonElement e : result.getAsJsonArray()) {
				if (e.isJsonObject()) locations.add(e.getAsJsonObject());
			}
		} else if (result.isJsonObject()) {
			locations.add(result.getAsJsonObject());
		}

		if (locations.isEmpty()) return null;

		String symbolName = wordAt(source, offset);
		if (symbolName.isEmpty()) symbolName = "symbol";

		List<FindDefinitionResultEntry> entries = new ArrayList<>();
		for (JsonObject loc : locations) {
			FindDefinitionResultEntry entry = locationToEntry(loc, symbolName);
			if (entry != null) entries.add(entry);
		}

		if (entries.isEmpty()) return null;
		return new FindDefinitionResult(entries, null, null);
	}

	private static FindDefinitionResultEntry locationToEntry(JsonObject loc, String symbolName) {
		// Supports both Location {uri, range} and LocationLink {targetUri, targetSelectionRange}
		String fileUri;
		JsonObject range;
		if (loc.has("uri")) {
			fileUri = loc.get("uri").getAsString();
			range = loc.has("range") ? loc.getAsJsonObject("range") : null;
		} else if (loc.has("targetUri")) {
			fileUri = loc.get("targetUri").getAsString();
			range = loc.has("targetSelectionRange") ? loc.getAsJsonObject("targetSelectionRange")
					: loc.has("targetRange") ? loc.getAsJsonObject("targetRange") : null;
		} else {
			return null;
		}

		URI uri;
		try {
			uri = new URI(fileUri);
		} catch (Exception e) {
			return null;
		}

		Location fileLoc;
		try {
			fileLoc = Location.fromAbsolutePath(Paths.get(uri));
		} catch (Exception e) {
			return null;
		}

		SourceRange sourceRange = readSourceRange(fileLoc, range);
		return new FindDefinitionResultEntry(symbolName, false, fileLoc, sourceRange);
	}

	private static SourceRange readSourceRange(Location fileLoc, JsonObject range) {
		if (range == null) return new SourceRange(0, 0);
		JsonObject start = range.has("start") ? range.getAsJsonObject("start") : null;
		if (start == null) return new SourceRange(0, 0);
		int line = start.has("line") ? start.get("line").getAsInt() : 0;
		int character = start.has("character") ? start.get("character").getAsInt() : 0;
		try {
			byte[] bytes = Files.readAllBytes(fileLoc.toPath());
			String source = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
			int offset = positionToOffset(source, line, character);
			return new SourceRange(offset, 0);
		} catch (Exception e) {
			return new SourceRange(0, 0);
		}
	}

}
