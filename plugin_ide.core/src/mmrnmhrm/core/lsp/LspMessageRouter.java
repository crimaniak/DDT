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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.google.gson.JsonObject;

import melnorme.lang.ide.core.LangCore;

/**
 * Routes JSON-RPC 2.0 messages over an {@link LspConnection}.
 *
 * Incoming responses are matched to pending {@link CompletableFuture}s by id.
 * Incoming notifications are dispatched to registered {@link Consumer} handlers.
 */
public class LspMessageRouter {

	private static final int TIMEOUT_SECONDS = 10;

	private LspConnection connection;
	private final AtomicInteger nextId = new AtomicInteger(1);
	private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
	private final Map<String, Consumer<JsonObject>> notificationHandlers = new ConcurrentHashMap<>();

	public LspMessageRouter() {
	}

	public void setConnection(LspConnection connection) {
		this.connection = connection;
	}

	public void registerNotificationHandler(String method, Consumer<JsonObject> handler) {
		notificationHandlers.put(method, handler);
	}

	/** Called by {@link LspConnection}'s reader thread for every incoming message. */
	public void handleMessage(JsonObject msg) {
		if (msg.has("id") && !msg.get("id").isJsonNull() && msg.has("result")) {
			// Successful response
			int id = msg.get("id").getAsInt();
			CompletableFuture<JsonObject> fut = pending.remove(id);
			if (fut != null) fut.complete(msg);

		} else if (msg.has("id") && !msg.get("id").isJsonNull() && msg.has("error")) {
			// Error response
			int id = msg.get("id").getAsInt();
			CompletableFuture<JsonObject> fut = pending.remove(id);
			if (fut != null) fut.completeExceptionally(
					new IOException("LSP error: " + msg.get("error")));

		} else if (msg.has("method") && !msg.has("id")) {
			// Notification
			String method = msg.get("method").getAsString();
			Consumer<JsonObject> handler = notificationHandlers.get(method);
			if (handler != null) {
				try {
					handler.accept(msg);
				} catch (Exception e) {
					LangCore.logError("LSP notification handler error [" + method + "]", e);
				}
			}
		}
	}

	/**
	 * Send a request and block until the response arrives (up to {@value #TIMEOUT_SECONDS}s).
	 *
	 * @return the full response JsonObject (contains "result")
	 */
	public JsonObject sendRequest(String method, JsonObject params) throws IOException {
		int id = nextId.getAndIncrement();
		CompletableFuture<JsonObject> fut = new CompletableFuture<>();
		pending.put(id, fut);

		JsonObject msg = new JsonObject();
		msg.addProperty("jsonrpc", "2.0");
		msg.addProperty("id", id);
		msg.addProperty("method", method);
		if (params != null) msg.add("params", params);
		connection.send(msg);

		try {
			return fut.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			pending.remove(id);
			throw new IOException("LSP request timed out: " + method, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			pending.remove(id);
			throw new IOException("LSP request interrupted: " + method, e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof IOException) throw (IOException) cause;
			throw new IOException("LSP request failed: " + method, cause);
		}
	}

	/** Send a notification (fire-and-forget, no response expected). */
	public void sendNotification(String method, JsonObject params) throws IOException {
		JsonObject msg = new JsonObject();
		msg.addProperty("jsonrpc", "2.0");
		msg.addProperty("method", method);
		if (params != null) msg.add("params", params);
		connection.send(msg);
	}

}
