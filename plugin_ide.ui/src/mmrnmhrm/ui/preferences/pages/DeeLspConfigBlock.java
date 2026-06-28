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

import melnorme.lang.ide.core.DeeToolPreferences;
import melnorme.lang.ide.ui.preferences.AbstractCompositePreferencesBlock;
import melnorme.lang.ide.ui.preferences.common.PreferencesPageContext;
import melnorme.util.swt.components.AbstractGroupWidget;
import melnorme.util.swt.components.fields.CheckBoxField;
import melnorme.util.swt.components.fields.FileTextField;
import melnorme.util.swt.components.fields.TextFieldWidget;

public class DeeLspConfigBlock extends AbstractCompositePreferencesBlock {

	public DeeLspConfigBlock(PreferencesPageContext prefContext) {
		super(prefContext);
		addChildWidget(new LspGroup());
	}

	public class LspGroup extends AbstractGroupWidget {

		protected final CheckBoxField enabledField;
		protected final FileTextField pathField;
		protected final TextFieldWidget argsField;

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

			enabledField.addChangeListener(this::updateFieldsEnabledState);
		}

		protected void updateFieldsEnabledState() {
			boolean enabled = enabledField.getBooleanFieldValue();
			pathField.setEnabled(enabled);
			argsField.setEnabled(enabled);
		}

	}

}
