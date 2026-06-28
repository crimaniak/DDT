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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import melnorme.lang.ide.core.LangCore;

/**
 * LSP JSON-RPC 2.0 framing over a process's stdin/stdout.
 *
 * Incoming: Content-Length header + blank line + UTF-8 JSON body.
 * Outgoing: same format, sent via {@link #send(JsonObject)}.
 *
 * The reader thread is a daemon and stops when the stream closes or
 * {@link #close()} is called.
 */
public class LspConnection {

	private final OutputStream out;
	private final Thread readerThread;
	private volatile boolean closed = false;

	@SuppressWarnings("deprecation")
	private static final JsonParser PARSER = new JsonParser();

	public LspConnection(InputStream in, OutputStream out, Consumer<JsonObject> handler) {
		this.out = out;
		this.readerThread = new Thread(() -> readerLoop(in, handler), "serve-d reader");
		this.readerThread.setDaemon(true);
		this.readerThread.start();
	}

	private void readerLoop(InputStream in, Consumer<JsonObject> handler) {
		try {
			while (!closed) {
				int contentLength = readHeaders(in);
				byte[] body = readExact(in, contentLength);
				String json = new String(body, StandardCharsets.UTF_8);
				JsonObject msg = PARSER.parse(json).getAsJsonObject();
				handler.accept(msg);
			}
		} catch (IOException e) {
			if (!closed) {
				LangCore.logError("serve-d reader terminated unexpectedly", e);
			}
		}
	}

	private int readHeaders(InputStream in) throws IOException {
		int contentLength = -1;
		String line;
		while (!(line = readLine(in)).isEmpty()) {
			if (line.startsWith("Content-Length:")) {
				contentLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
			}
		}
		if (contentLength < 0) {
			throw new IOException("LSP frame missing Content-Length header");
		}
		return contentLength;
	}

	private String readLine(InputStream in) throws IOException {
		StringBuilder sb = new StringBuilder();
		int c;
		while ((c = in.read()) != -1) {
			if (c == '\r') {
				in.read(); // consume paired \n
				return sb.toString();
			}
			if (c == '\n') return sb.toString();
			sb.append((char) c);
		}
		throw new IOException("serve-d stream closed");
	}

	private byte[] readExact(InputStream in, int count) throws IOException {
		byte[] buf = new byte[count];
		int pos = 0;
		while (pos < count) {
			int n = in.read(buf, pos, count - pos);
			if (n < 0) throw new IOException("serve-d stream closed mid-message");
			pos += n;
		}
		return buf;
	}

	public synchronized void send(JsonObject message) throws IOException {
		byte[] body = message.toString().getBytes(StandardCharsets.UTF_8);
		String header = "Content-Length: " + body.length + "\r\n\r\n";
		out.write(header.getBytes(StandardCharsets.US_ASCII));
		out.write(body);
		out.flush();
	}

	public void close() {
		closed = true;
		readerThread.interrupt();
	}

}
