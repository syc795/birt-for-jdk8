/***********************************************************************
 * Copyright (c) 2009 Actuate Corporation.
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/.
 * 
 * SPDX-License-Identifier: EPL-2.0
 * 
 *
 * Contributors:
 * Actuate Corporation - initial API and implementation
 ***********************************************************************/
package org.eclipse.birt.report.engine.nLayout.area.impl;

import org.eclipse.birt.report.engine.nLayout.area.IAreaVisitor;
import org.eclipse.birt.report.engine.nLayout.area.ITemplateArea;
import org.eclipse.birt.report.engine.nLayout.area.style.TextStyle;

public class TemplateArea extends TextArea implements ITemplateArea {
	protected int type;

	public TemplateArea(String text, TextStyle style, int type) {
		super(text, style);
		this.type = type;
	}

	public void accept(IAreaVisitor visitor) {
		visitor.visitAutoText(this);
	}

	public int getType() {
		return type;
	}
}
