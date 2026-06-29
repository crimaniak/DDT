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
package mmrnmhrm.ui.preferences.pages;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import melnorme.lang.ide.core.DeeToolPreferences;
import melnorme.lang.ide.core.LangCore_Actual;
import melnorme.lang.ide.ui.preferences.AbstractCompositePreferencesBlock;
import melnorme.lang.ide.ui.preferences.common.PreferencesPageContext;
import melnorme.util.swt.SWTUtil;
import melnorme.util.swt.components.AbstractGroupWidget;
import melnorme.util.swt.components.fields.CheckBoxField;
import melnorme.util.swt.components.fields.FileTextField;
import melnorme.util.swt.components.fields.TextFieldWidget;
import melnorme.utilbox.fields.IFieldView.FieldListenerRegistration;

public class DeeLspConfigBlock extends AbstractCompositePreferencesBlock {

	public DeeLspConfigBlock(PreferencesPageContext prefContext) {
		super(prefContext);
		addChildWidget(new LspGroup());
	}

	public class LspGroup extends AbstractGroupWidget {

		protected final CheckBoxField enabledField;
		protected final FileTextField pathField;
		protected final TextFieldWidget argsField;
		protected final TextFieldWidget compilerField;
		protected final TextFieldWidget stdlibPathsField;

		private Label statusLabel;
		private FieldListenerRegistration statusReg;

		public LspGroup() {
			super("Language Server (serve-d):", 3);

			enabledField = new CheckBoxField("Use language server instead of embedded parser");
			prefContext.bindToPreference(enabledField, DeeToolPreferences.LSP_ENABLED);
			addChildWidget(enabledField);

			pathField = new FileTextField("Path to serve-d executable:");
			prefContext.bindToPreference(pathField, DeeToolPreferences.LSP_PATH);
			addChildWidget(pathField);

			argsField = new TextFieldWidget("Extra arguments:");
			prefContext.bindToPreference(argsField, DeeToolPreferences.LSP_ARGS);
			addChildWidget(argsField);

			compilerField = new TextFieldWidget("Compiler for serve-d (e.g. ldc2, dmd — leave blank to auto-detect):");
			prefContext.bindToPreference(compilerField, DeeToolPreferences.LSP_DUB_COMPILER);
			addChildWidget(compilerField);

			stdlibPathsField = new TextFieldWidget("Stdlib paths (colon-separated — leave blank to auto-detect):");
			prefContext.bindToPreference(stdlibPathsField, DeeToolPreferences.LSP_STDLIB_PATHS);
			addChildWidget(stdlibPathsField);

			enabledField.addChangeListener(this::updateFieldsEnabledState);
		}

		@Override
		protected void createContents(Composite topControl) {
			super.createContents(topControl); // create all child widgets first
			statusLabel = new Label(topControl, SWT.NONE);
			statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
			statusReg = LangCore_Actual.getLspServer().getStatusField()
					.registerChangeListener(true, () -> SWTUtil.runInSWTThread(this::refreshStatus));
			topControl.addDisposeListener(e -> statusReg.dispose());
		}

		private void refreshStatus() {
			if (statusLabel == null || statusLabel.isDisposed()) return;
			boolean enabled = enabledField.getBooleanFieldValue();
			if (!enabled) {
				statusLabel.setText("");
				return;
			}
			String status = LangCore_Actual.getLspServer().getStatusField().get();
			statusLabel.setText("Status: " + status);
			if ("Connected".equals(status)) {
				statusLabel.setForeground(statusLabel.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
			} else if (status.startsWith("Failed")) {
				statusLabel.setForeground(statusLabel.getDisplay().getSystemColor(SWT.COLOR_RED));
			} else {
				statusLabel.setForeground(statusLabel.getDisplay().getSystemColor(SWT.COLOR_DARK_YELLOW));
			}
			statusLabel.getParent().layout(true, true);
		}

		@Override
		protected void doSetEnabled(boolean enabled) {
			super.doSetEnabled(enabled);
		}

		protected void updateFieldsEnabledState() {
			boolean enabled = enabledField.getBooleanFieldValue();
			pathField.setEnabled(enabled);
			argsField.setEnabled(enabled);
			compilerField.setEnabled(enabled);
			stdlibPathsField.setEnabled(enabled);
			refreshStatus();
		}

	}

}
