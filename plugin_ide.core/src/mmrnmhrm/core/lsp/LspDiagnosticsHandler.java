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

import java.net.URI;
import java.net.URISyntaxException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import melnorme.lang.ide.core.LangCore;
import melnorme.lang.ide.core.LangCore_Actual;
import melnorme.lang.ide.core.utils.ResourceUtils;

/**
 * Receives {@code textDocument/publishDiagnostics} notifications from serve-d
 * and converts them to Eclipse {@link IMarker}s under the
 * {@value LangCore_Actual#LSP_PROBLEM_ID} marker type.
 *
 * Old LSP markers for the file are deleted before new ones are created, so
 * an empty diagnostics list clears all previous LSP markers.
 * When serve-d stops, {@link #clearAll()} removes every LSP marker in the
 * workspace so stale errors do not linger.
 */
public class LspDiagnosticsHandler {

	public LspDiagnosticsHandler(LspMessageRouter router) {
		router.registerNotificationHandler("textDocument/publishDiagnostics", this::handleDiagnostics);
	}

	private void handleDiagnostics(JsonObject msg) {
		JsonObject params = msg.getAsJsonObject("params");
		if (params == null) return;

		String uriStr = params.has("uri") ? params.get("uri").getAsString() : null;
		if (uriStr == null) return;

		JsonArray diags = params.has("diagnostics") ? params.getAsJsonArray("diagnostics") : new JsonArray();

		URI fileUri;
		try {
			fileUri = new URI(uriStr);
		} catch (URISyntaxException e) {
			LangCore.logWarning("LSP publishDiagnostics: bad URI: " + uriStr);
			return;
		}

		IFile[] files = ResourceUtils.getWorkspaceRoot().findFilesForLocationURI(fileUri);
		if (files.length == 0) return; // file not in workspace

		IFile file = files[0];
		try {
			applyDiagnostics(file, diags);
		} catch (CoreException e) {
			LangCore.logError("LSP marker update failed for " + uriStr, e);
		}
	}

	private static void applyDiagnostics(IFile file, JsonArray diags) throws CoreException {
		ResourceUtils.getWorkspace().run(new IWorkspaceRunnable() {
			@Override
			public void run(IProgressMonitor monitor) throws CoreException {
				if (!file.exists()) return;

				// Clear old LSP markers AND any embedded source markers for this file.
				// (Embedded markers may have been created before LSP was ready.)
				file.deleteMarkers(LangCore_Actual.LSP_PROBLEM_ID, false, IResource.DEPTH_ZERO);
				file.deleteMarkers(LangCore_Actual.SOURCE_PROBLEM_ID, false, IResource.DEPTH_ZERO);

				for (JsonElement elem : diags) {
					createMarker(file, elem.getAsJsonObject());
				}
			}
		}, file, IWorkspace.AVOID_UPDATE, null);
	}

	private static void createMarker(IFile file, JsonObject diag) throws CoreException {
		IMarker marker = file.createMarker(LangCore_Actual.LSP_PROBLEM_ID);

		// Severity: LSP 1=Error 2=Warning 3=Information 4=Hint → IMarker constants
		int lspSev = diag.has("severity") ? diag.get("severity").getAsInt() : 1;
		marker.setAttribute(IMarker.SEVERITY, lspToMarkerSeverity(lspSev));

		// Line number: LSP 0-based → IMarker 1-based
		JsonObject range = diag.has("range") ? diag.getAsJsonObject("range") : null;
		if (range != null && range.has("start")) {
			int line = range.getAsJsonObject("start").get("line").getAsInt();
			marker.setAttribute(IMarker.LINE_NUMBER, line + 1);
		}

		// Message
		if (diag.has("message")) {
			marker.setAttribute(IMarker.MESSAGE, diag.get("message").getAsString());
		}

		// Location path shown in the Problems view
		marker.setAttribute(IMarker.LOCATION, file.getFullPath().toString());
	}

	private static int lspToMarkerSeverity(int lspSeverity) {
		switch (lspSeverity) {
			case 1:  return IMarker.SEVERITY_ERROR;
			case 2:  return IMarker.SEVERITY_WARNING;
			default: return IMarker.SEVERITY_INFO;
		}
	}

	/** Remove all LSP markers from the entire workspace (called when serve-d stops). */
	public void clearAll() {
		try {
			ResourceUtils.getWorkspace().run(new IWorkspaceRunnable() {
				@Override
				public void run(IProgressMonitor monitor) throws CoreException {
					ResourceUtils.getWorkspaceRoot().deleteMarkers(
							LangCore_Actual.LSP_PROBLEM_ID, false, IResource.DEPTH_INFINITE);
				}
			}, null, IWorkspace.AVOID_UPDATE, null);
		} catch (CoreException e) {
			LangCore.logError("Failed to clear LSP markers", e);
		}
	}

}
