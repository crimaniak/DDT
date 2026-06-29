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

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.swt.graphics.Image;

import _org.eclipse.dltk.ui.text.completion.AbstractScriptCompletionProposal;
import dtool.ddoc.TextUI;
import melnorme.lang.ide.ui.text.AbstractSimpleLangSourceViewerConfiguration;
import melnorme.lang.ide.ui.text.DocumentationHoverCreator;
import melnorme.lang.tooling.ToolCompletionProposal;
import melnorme.lang.tooling.symbols.INamedElement;
import melnorme.lang.tooling.toolchain.ops.SourceOpContext;
import mmrnmhrm.core.lsp.LspFeatureSupport;

public class DeeContentAssistProposal extends AbstractScriptCompletionProposal {
	
	public final INamedElement namedElement; 
	
	public DeeContentAssistProposal(SourceOpContext sourceOpContext, ToolCompletionProposal proposal, Image image) {
		super(sourceOpContext, proposal, image, null);
		this.namedElement = proposal.getExtraData();
	}
	
	@Override
	public String getProposalInfoString(IProgressMonitor monitor) {
		if (namedElement == null) {
			// LSP-sourced proposal — namedElement not available; use LSP-supplied documentation
			String doc = proposal.getDocumentation();
			if (doc != null && !doc.isEmpty()) {
				return LspFeatureSupport.markdownToHtml(doc);
			}
			String typeLabel = proposal.getTypeLabel();
			if (typeLabel != null && !typeLabel.isEmpty()) {
				return "<code>" + typeLabel.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</code>";
			}
			return null;
		}
		return TextUI.getDDocHTMLRender(namedElement);
	}
	
	@Override
	public IInformationControlCreator getInformationControlCreator() {
		if(informationControlCreator == null) {
			String statusFieldText = AbstractSimpleLangSourceViewerConfiguration.getAdditionalInfoAffordanceString();
			informationControlCreator = new DocumentationHoverCreator().getHoverControlCreator(statusFieldText);
		}
		return informationControlCreator;
	}
	
}