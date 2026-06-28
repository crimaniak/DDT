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
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import melnorme.lang.ide.core.DeeToolPreferences;
import melnorme.lang.ide.core.LangCore;
import melnorme.utilbox.fields.Field;
import melnorme.utilbox.fields.IFieldView;
import melnorme.utilbox.fields.IFieldView.FieldListenerRegistration;

/**
 * Manages the serve-d language server subprocess.
 *
 * Lifecycle:
 *   1. Created by {@link mmrnmhrm.core.engine.DeeLanguageServerHandler}.
 *   2. Starts serve-d automatically if LSP_ENABLED preference is true.
 *   3. Restarts when LSP_ENABLED or LSP_PATH preferences change.
 *   4. Stopped and cleaned up in {@link #dispose()}.
 *
 * {@link #isReady()} returns true only after the LSP initialize handshake completes.
 */
public class LspServer {

	private volatile boolean ready = false;
	private volatile Process process;
	private volatile LspConnection connection;
	private volatile LspMessageRouter router;
	private volatile LspDiagnosticsHandler diagnosticsHandler;
	private volatile JsonObject serverCapabilities = new JsonObject();

	private LspTextSynchronizer textSync;

	private final Field<String> statusField = new Field<>("Not running");

	private final FieldListenerRegistration enabledListener;
	private final FieldListenerRegistration pathListener;

	public LspServer() {
		IFieldView<Boolean> enabledField = DeeToolPreferences.LSP_ENABLED.asField();
		IFieldView<String> pathField = DeeToolPreferences.LSP_PATH.asField();

		enabledListener = enabledField.registerChangeListener(false, this::onSettingsChanged);
		pathListener = pathField.registerChangeListener(false, this::onSettingsChanged);

		if (Boolean.TRUE.equals(enabledField.get())) {
			startAsync();
		}
	}

	public boolean isReady() {
		return ready;
	}

	public LspMessageRouter getRouter() {
		return router;
	}

	public IFieldView<String> getStatusField() {
		return statusField;
	}

	/** Returns true if serve-d advertised the given top-level capability key. */
	public boolean hasCapability(String key) {
		return serverCapabilities.has(key);
	}

	/** Wire in the text synchronizer so it can be reset when serve-d restarts. */
	public void setTextSynchronizer(LspTextSynchronizer textSync) {
		this.textSync = textSync;
	}

	private void onSettingsChanged() {
		stopServer();
		if (Boolean.TRUE.equals(DeeToolPreferences.LSP_ENABLED.get())) {
			startAsync();
		}
	}

	private void startAsync() {
		Thread t = new Thread(this::doStart, "serve-d starter");
		t.setDaemon(true);
		t.start();
	}

	private synchronized void doStart() {
		if (ready) return; // already started

		String path = DeeToolPreferences.LSP_PATH.get().trim();
		if (path.isEmpty()) path = "serve-d"; // fall back to PATH lookup

		String argsStr = DeeToolPreferences.LSP_ARGS.get().trim();
		List<String> cmd = new ArrayList<>();
		cmd.add(path);
		if (!argsStr.isEmpty()) {
			for (String arg : argsStr.split("\\s+")) {
				if (!arg.isEmpty()) cmd.add(arg);
			}
		}

		statusField.setFieldValue("Starting…");

		try {
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(false); // keep stderr separate (serve-d logs there)
			Process proc = pb.start();
			this.process = proc;

			// Two-phase init to break the router↔connection cycle:
			// router needs connection to send; connection needs router.handleMessage to dispatch.
			startStderrLogger(proc);

			LspMessageRouter r = new LspMessageRouter();
			LspConnection conn = new LspConnection(proc.getInputStream(), proc.getOutputStream(),
					r::handleMessage, this::onReaderDisconnected);
			r.setConnection(conn);
			this.connection = conn;
			this.router = r;

			JsonObject caps = sendInitialize(r);
			this.serverCapabilities = caps != null ? caps : new JsonObject();
			sendNotification(r, "initialized", new JsonObject());
			sendCompilerConfiguration(r);
			this.diagnosticsHandler = new LspDiagnosticsHandler(r);
			ready = true;
			boolean hasCompletion = this.serverCapabilities.has("completionProvider");
			statusField.setFieldValue(hasCompletion ? "Connected" : "Connected (no completion)");
			LangCore.logInfo("serve-d connected" + (hasCompletion ? "" : " (DCD not available)"));

		} catch (IOException e) {
			LangCore.logError("Failed to start serve-d (" + path + "): " + e.getMessage(), e);
			statusField.setFieldValue("Failed: " + e.getMessage());
			ready = false;
		}
	}

	/** Returns the serverCapabilities object from the initialize response, or null on error. */
	private JsonObject sendInitialize(LspMessageRouter r) throws IOException {
		JsonObject params = new JsonObject();

		// processId — lets serve-d exit if we crash
		String jvmName = ManagementFactory.getRuntimeMXBean().getName(); // "pid@host"
		try {
			params.addProperty("processId", Integer.parseInt(jvmName.split("@")[0]));
		} catch (NumberFormatException e) {
			params.addProperty("processId", (Integer) null);
		}

		// rootUri — intentionally null. If we pass the Eclipse workspace folder, serve-d
		// treats it as the sole D project, runs "dub describe" there (no dub.json →
		// no import paths), and never discovers the real per-file project roots.
		// With null, serve-d enters per-file detection mode: when a D file is opened it
		// walks up to the nearest dub.json and initialises a proper project instance.
		params.add("rootUri", com.google.gson.JsonNull.INSTANCE);

		JsonObject textDocCaps = new JsonObject();

		JsonObject syncCaps = new JsonObject();
		syncCaps.addProperty("dynamicRegistration", false);
		syncCaps.addProperty("didSave", true);
		textDocCaps.add("synchronization", syncCaps);

		JsonObject diagCaps = new JsonObject();
		diagCaps.addProperty("relatedInformation", false);
		textDocCaps.add("publishDiagnostics", diagCaps);

		JsonObject completionCaps = new JsonObject();
		completionCaps.addProperty("dynamicRegistration", false);
		JsonObject completionItemCaps = new JsonObject();
		completionItemCaps.addProperty("snippetSupport", false);
		completionCaps.add("completionItem", completionItemCaps);
		textDocCaps.add("completion", completionCaps);

		JsonObject hoverCaps = new JsonObject();
		hoverCaps.addProperty("dynamicRegistration", false);
		JsonArray hoverFormats = new JsonArray();
		hoverFormats.add("plaintext");
		hoverFormats.add("markdown");
		hoverCaps.add("contentFormat", hoverFormats);
		textDocCaps.add("hover", hoverCaps);

		JsonObject definitionCaps = new JsonObject();
		definitionCaps.addProperty("dynamicRegistration", false);
		definitionCaps.addProperty("linkSupport", false);
		textDocCaps.add("definition", definitionCaps);

		JsonObject caps = new JsonObject();
		caps.add("textDocument", textDocCaps);
		params.add("capabilities", caps);

		JsonObject response = r.sendRequest("initialize", params);
		if (response != null && response.has("result")) {
			JsonObject result = response.getAsJsonObject("result");
			if (result.has("capabilities")) {
				return result.getAsJsonObject("capabilities");
			}
		}
		return null;
	}

	private void sendNotification(LspMessageRouter r, String method, JsonObject params) {
		try {
			r.sendNotification(method, params);
		} catch (IOException e) {
			LangCore.logWarning("serve-d notification '" + method + "' failed: " + e.getMessage());
		}
	}

	private void sendCompilerConfiguration(LspMessageRouter r) {
		// Push compiler settings so serve-d's stdlib auto-detection uses ldc2.
		// Without this, serve-d defaults to "dmd" which is not installed here.
		// serve-d reads workspace/didChangeConfiguration settings.d.dubCompiler
		// and passes it to autoDetectStdlibPaths(), which then finds /etc/ldc2.conf.
		JsonObject dConfig = new JsonObject();
		dConfig.addProperty("dubCompiler", "ldc2");
		dConfig.addProperty("dmdPath", "ldmd2");
		JsonObject settings = new JsonObject();
		settings.add("d", dConfig);
		JsonObject params = new JsonObject();
		params.add("settings", settings);
		sendNotification(r, "workspace/didChangeConfiguration", params);
	}

	private static void startStderrLogger(Process proc) {
		Thread t = new Thread(() -> {
			try (java.io.BufferedReader br = new java.io.BufferedReader(
					new java.io.InputStreamReader(proc.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8))) {
				String line;
				while ((line = br.readLine()) != null) {
					LangCore.logInfo("serve-d: " + line);
				}
			} catch (java.io.IOException e) {
				// process gone, nothing to do
			}
		}, "serve-d stderr");
		t.setDaemon(true);
		t.start();
	}

	private void onReaderDisconnected() {
		// Called from the reader thread — run cleanup on a separate thread to avoid
		// re-entering the lock from the thread being stopped.
		Thread t = new Thread(() -> {
			LangCore.logInfo("serve-d disconnected, cleaning up");
			stopServer();
		}, "serve-d cleanup");
		t.setDaemon(true);
		t.start();
	}

	private synchronized void stopServer() {
		ready = false;
		statusField.setFieldValue("Not running");

		if (diagnosticsHandler != null) {
			diagnosticsHandler.clearAll();
			diagnosticsHandler = null;
		}

		if (textSync != null) {
			textSync.reset(); // clear stale open-file state before serve-d exits
		}

		if (router != null) {
			try {
				router.sendNotification("exit", null);
			} catch (IOException e) {
				// best effort
			}
			router = null;
		}

		if (connection != null) {
			connection.close();
			connection = null;
		}

		if (process != null) {
			process.destroyForcibly();
			process = null;
		}
	}

	public void dispose() {
		enabledListener.dispose();
		pathListener.dispose();
		stopServer();
	}

}
