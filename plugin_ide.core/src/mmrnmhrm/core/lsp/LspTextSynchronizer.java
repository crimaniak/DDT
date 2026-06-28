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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import melnorme.lang.ide.core.LangCore;
import melnorme.utilbox.misc.Location;

/**
 * Keeps serve-d's text buffer state in sync with Eclipse's open editors.
 *
 * Call sites (all on SourceModelManager executor or document-listener thread):
 *   - {@link #notifyChange} : document opened for first time or modified
 *   - {@link #notifySave}   : buffer written to disk
 *   - {@link #notifyClose}  : last editor for a file closed
 *   - {@link #reset}        : called when serve-d restarts so open-file state is cleared
 *
 * Full-document sync is used for simplicity (single contentChanges entry with the
 * whole text). serve-d accepts this and does not require incremental changes.
 */
public class LspTextSynchronizer {

	private final LspServer lspServer;
	/** URI → monotonically increasing version. Entry absent = file not open in LSP. */
	private final ConcurrentHashMap<String, AtomicInteger> openFiles = new ConcurrentHashMap<>();

	public LspTextSynchronizer(LspServer lspServer) {
		this.lspServer = lspServer;
	}

	/**
	 * Notify that a document's content has changed (or was just opened).
	 * Sends {@code textDocument/didOpen} on first call for a location,
	 * {@code textDocument/didChange} thereafter.
	 */
	public void notifyChange(Location location, String source) {
		if (location == null) return;
		LspMessageRouter router = readyRouter();
		if (router == null) return;

		String uri = toUri(location);
		AtomicInteger counter = openFiles.computeIfAbsent(uri, k -> new AtomicInteger(0));
		int version = counter.incrementAndGet();

		try {
			if (version == 1) {
				sendDidOpen(router, uri, source, version);
			} else {
				sendDidChange(router, uri, source, version);
			}
		} catch (IOException e) {
			LangCore.logWarning("LSP didOpen/didChange failed for " + uri + ": " + e.getMessage());
		}
	}

	/**
	 * Notify that a document was saved to disk.
	 * Only sent if the file is currently open in LSP.
	 */
	public void notifySave(Location location) {
		if (location == null) return;
		LspMessageRouter router = readyRouter();
		if (router == null) return;

		String uri = toUri(location);
		if (!openFiles.containsKey(uri)) return;

		try {
			JsonObject textDoc = new JsonObject();
			textDoc.addProperty("uri", uri);
			JsonObject params = new JsonObject();
			params.add("textDocument", textDoc);
			router.sendNotification("textDocument/didSave", params);
		} catch (IOException e) {
			LangCore.logWarning("LSP didSave failed for " + uri + ": " + e.getMessage());
		}
	}

	/**
	 * Notify that the last editor for a file has closed.
	 */
	public void notifyClose(Location location) {
		if (location == null) return;
		LspMessageRouter router = readyRouter();
		if (router == null) return;

		String uri = toUri(location);
		if (openFiles.remove(uri) == null) return; // wasn't tracked

		try {
			JsonObject textDoc = new JsonObject();
			textDoc.addProperty("uri", uri);
			JsonObject params = new JsonObject();
			params.add("textDocument", textDoc);
			router.sendNotification("textDocument/didClose", params);
		} catch (IOException e) {
			LangCore.logWarning("LSP didClose failed for " + uri + ": " + e.getMessage());
		}
	}

	/**
	 * Clear all tracked open-file state when serve-d restarts.
	 * The next {@link #notifyChange} for each file will re-send {@code didOpen}.
	 */
	public void reset() {
		openFiles.clear();
	}

	/* -----------------  ----------------- */

	private LspMessageRouter readyRouter() {
		if (!lspServer.isReady()) return null;
		return lspServer.getRouter();
	}

	private static String toUri(Location location) {
		return location.toUri().toString();
	}

	private static void sendDidOpen(LspMessageRouter router, String uri, String source, int version)
			throws IOException {
		JsonObject textDoc = new JsonObject();
		textDoc.addProperty("uri", uri);
		textDoc.addProperty("languageId", "d");
		textDoc.addProperty("version", version);
		textDoc.addProperty("text", source);
		JsonObject params = new JsonObject();
		params.add("textDocument", textDoc);
		router.sendNotification("textDocument/didOpen", params);
	}

	private static void sendDidChange(LspMessageRouter router, String uri, String source, int version)
			throws IOException {
		JsonObject textDocId = new JsonObject();
		textDocId.addProperty("uri", uri);
		textDocId.addProperty("version", version);
		JsonObject change = new JsonObject();
		change.addProperty("text", source); // full-document replacement
		JsonArray changes = new JsonArray();
		changes.add(change);
		JsonObject params = new JsonObject();
		params.add("textDocument", textDocId);
		params.add("contentChanges", changes);
		router.sendNotification("textDocument/didChange", params);
	}

}
