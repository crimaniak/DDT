/*******************************************************************************
 * Copyright (c) 2015 Bruno Medeiros and other Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Bruno Medeiros - initial API and implementation
 *******************************************************************************/
package mmrnmhrm.ui.editor.codeassist;

import java.io.IOException;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.swt.graphics.Image;

import melnorme.lang.ide.core.LangCore;
import melnorme.lang.ide.core.LangCore_Actual;
import melnorme.lang.ide.core.utils.ResourceUtils;
import melnorme.lang.ide.ui.text.completion.LangCompletionProposalComputer;
import melnorme.lang.tooling.ToolCompletionProposal;
import melnorme.lang.tooling.common.ops.IOperationMonitor.NullOperationMonitor;
import melnorme.lang.tooling.toolchain.ops.OperationSoftFailure;
import melnorme.lang.tooling.toolchain.ops.SourceOpContext;
import melnorme.lang.utils.concurrency.TimeoutCancelMonitor;
import melnorme.utilbox.collections.ArrayList2;
import melnorme.utilbox.collections.Indexable;
import melnorme.utilbox.concurrency.ICancelMonitor;
import melnorme.utilbox.concurrency.OperationCancellation;
import melnorme.utilbox.core.CommonException;
import melnorme.utilbox.misc.Location;
import mmrnmhrm.core.engine.DeeLanguageEngine;
import mmrnmhrm.core.lsp.LspFeatureSupport;
import mmrnmhrm.core.lsp.LspServer;

public class DeeCompletionProposalComputer extends LangCompletionProposalComputer {
	
	protected DeeLanguageEngine dtoolclient = DeeLanguageEngine.getDefault();
	
	public DeeCompletionProposalComputer() {
	}
	
	@Override
	protected Indexable<ToolCompletionProposal> doComputeProposals(SourceOpContext sourceContext, ICancelMonitor cm)
			throws CommonException, OperationCancellation, OperationSoftFailure {

		// Try LSP backend first (only if serve-d advertised completionProvider)
		LspServer lspServer = LangCore_Actual.getLspServer();
		if (lspServer.isReady() && lspServer.hasCapability("completionProvider")) {
			try {
				Location fileLocation = sourceContext.getFileLocation();
				String uri = LspFeatureSupport.fileUri(fileLocation);
				ArrayList2<ToolCompletionProposal> proposals = LspFeatureSupport.requestCompletion(
						lspServer.getRouter(), uri, sourceContext.getSource(), sourceContext.getOffset());
				return proposals;
			} catch (IOException | CommonException e) {
				LangCore.logWarning("LSP completion failed, falling back to embedded: " + e.getMessage());
			}
		}

		// Embedded engine fallback
		Location editoInputFile = sourceContext.getFileLocation();
		IProject project = ResourceUtils.getProjectFromMemberLocation(editoInputFile);
		String dubPath = LangCore.settings().SDK_LOCATION.getValue(project).toString();
		int timeoutMillis = ((TimeoutCancelMonitor) cm).getTimeoutMillis();
		return dtoolclient.new CodeCompletionOperation(editoInputFile, timeoutMillis, sourceContext.getOffset(), dubPath)
			.runEngineOperation(new NullOperationMonitor(cm))
			.convertToCompletionResult();
	}
	
	/* -----------------  ----------------- */
	
	@Override
	protected ICompletionProposal adaptToolProposal(SourceOpContext sourceOpContext, ToolCompletionProposal proposal) {
		Image image = getImage(proposal);
		return new DeeContentAssistProposal(sourceOpContext, proposal, image);
	}
	
}