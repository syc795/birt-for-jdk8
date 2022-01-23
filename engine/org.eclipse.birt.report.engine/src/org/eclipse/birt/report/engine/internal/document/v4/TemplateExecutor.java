/*******************************************************************************
 * Copyright (c) 2004 Actuate Corporation.
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/.
 * 
 * SPDX-License-Identifier: EPL-2.0
 * 
 *
 * Contributors:
 *  Actuate Corporation  - initial API and implementation
 *******************************************************************************/

package org.eclipse.birt.report.engine.internal.document.v4;

import org.eclipse.birt.report.engine.content.IContent;

public class TemplateExecutor extends ReportItemExecutor {

	/**
	 * constructor
	 * 
	 * @param context the excutor context
	 * @param visitor the report executor visitor
	 */
	public TemplateExecutor(ExecutorManager manager) {
		super(manager, ExecutorManager.TEMPLATEITEM);
	}

	protected IContent doCreateContent() {
		throw new IllegalStateException("can not re-generate content for template item");
	}

	protected void doExecute() throws Exception {

	}

}
