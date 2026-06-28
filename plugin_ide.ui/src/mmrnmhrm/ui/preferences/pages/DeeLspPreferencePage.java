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

import melnorme.lang.ide.ui.preferences.common.AbstractPreferencesBlockPrefPage;
import melnorme.lang.ide.ui.preferences.common.PreferencesPageContext;

public class DeeLspPreferencePage extends AbstractPreferencesBlockPrefPage {

	public DeeLspPreferencePage() {
		super();
	}

	@Override
	protected DeeLspConfigBlock init_createPreferencesBlock(PreferencesPageContext prefContext) {
		return new DeeLspConfigBlock(prefContext);
	}

	@Override
	protected String getHelpId() {
		return null;
	}

}
