/*******************************************************************************
 * Copyright (c) 2016 Bruno Medeiros and other Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Bruno Medeiros - initial API and implementation
 *******************************************************************************/
package mmrnmhrm.core.engine;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import dtool.dub.DubBundle;
import dtool.dub.DubBundleDescription;
import melnorme.lang.ide.core.LangCore;
import melnorme.lang.ide.core.engine.ILanguageServerHandler;
import melnorme.lang.ide.core.project_model.IProjectModelListener;
import melnorme.lang.ide.core.project_model.UpdateEvent;
import melnorme.lang.tooling.bundle.BundleInfo;
import melnorme.utilbox.fields.IFieldView.FieldListenerRegistration;
import melnorme.utilbox.misc.Location;
import mmrnmhrm.core.lsp.LspServer;
import mmrnmhrm.core.lsp.LspTextSynchronizer;

public class DeeLanguageServerHandler implements ILanguageServerHandler  {

	protected final DeeLanguageEngine languageEngine = new DeeLanguageEngine();
	protected final LspServer lspServer;
	protected final LspTextSynchronizer lspTextSync;

	private final IProjectModelListener<BundleInfo> bundleListener;
	private final FieldListenerRegistration statusListener;
	private volatile boolean bundleListenerRegistered = false;

	public DeeLanguageServerHandler() {
		lspServer = new LspServer();
		lspTextSync = new LspTextSynchronizer(lspServer);
		lspServer.setTextSynchronizer(lspTextSync);

		bundleListener = (UpdateEvent<BundleInfo> event) -> {
			if (event.newProjectInfo2 != null && lspServer.isReady()) {
				pushImportPathsForProject(event.project, event.newProjectInfo2);
			}
		};

		// Register the bundle listener and push existing paths the first time LSP connects.
		// Done lazily here (not in the constructor) because bundleManager is not yet
		// initialized when DeeLanguageServerHandler is constructed — it is assigned one
		// line after createLanguageServerHandler() returns in AbstractLangCore.<init>.
		statusListener = lspServer.getStatusField().registerChangeListener(false, () -> {
			String status = lspServer.getStatusField().get();
			if (status != null && status.startsWith("Connected") && lspServer.isReady()) {
				if (!bundleListenerRegistered) {
					LangCore.getBundleModel().addListener(bundleListener);
					bundleListenerRegistered = true;
				}
				pushAllProjectPaths();
			}
		});
	}

	private void pushAllProjectPaths() {
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			BundleInfo info = LangCore.getBundleModel().getBundleInfo(project);
			if (info != null && !info.hasErrors()) {
				pushImportPathsForProject(project, info);
			}
		}
	}

	private void pushImportPathsForProject(IProject project, BundleInfo bundleInfo) {
		if (bundleInfo.hasErrors() || !bundleInfo.isResolved()) return;

		List<String> paths = new ArrayList<>();
		DubBundleDescription bundleDesc = bundleInfo.getBundleDesc();

		for (Location loc : bundleDesc.getMainBundle().getEffectiveImportFolders_AbsolutePath()) {
			paths.add(loc.toPathString());
		}
		for (DubBundle dep : bundleDesc.getBundleDependencies()) {
			if (dep.hasErrors()) continue;
			for (Location loc : dep.getEffectiveImportFolders_AbsolutePath()) {
				paths.add(loc.toPathString());
			}
		}

		if (!paths.isEmpty()) {
			lspServer.sendProjectImportPaths(paths);
		}
	}

	@Override
	public void dispose() {
		statusListener.dispose();
		if (bundleListenerRegistered) {
			LangCore.getBundleModel().removeListener(bundleListener);
		}
		lspServer.dispose();
	}

	public DeeLanguageEngine getLanguageEngine() {
		return languageEngine;
	}

	public LspServer getLspServer() {
		return lspServer;
	}

	public LspTextSynchronizer getLspTextSync() {
		return lspTextSync;
	}

}