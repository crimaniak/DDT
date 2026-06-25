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
package dtool.ast.expressions;

import melnorme.lang.tooling.ast.CommonASTNode;
import melnorme.lang.tooling.ast.IASTVisitor;
import melnorme.lang.tooling.ast.util.ASTCodePrinter;
import melnorme.lang.tooling.ast_actual.ASTNodeTypes;

/** Named argument in a call expression: {@code identifier : expr} (DIP1040, DMD 2.103+) */
public class ExpNamedArgument extends Expression {

	public final String name;
	public final Expression value;

	public ExpNamedArgument(String name, Expression value) {
		this.name = name;
		this.value = parentize(value);
	}

	@Override
	public ASTNodeTypes getNodeType() {
		return ASTNodeTypes.EXP_NAMED_ARGUMENT;
	}

	@Override
	public void visitChildren(IASTVisitor visitor) {
		acceptVisitor(visitor, value);
	}

	@Override
	protected CommonASTNode doCloneTree() {
		return new ExpNamedArgument(name, clone(value));
	}

	@Override
	public void toStringAsCode(ASTCodePrinter cp) {
		cp.append(name);
		cp.append(": ");
		cp.append(value);
	}

}
