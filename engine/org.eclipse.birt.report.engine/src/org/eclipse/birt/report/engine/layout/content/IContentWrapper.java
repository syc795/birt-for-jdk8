/*******************************************************************************
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/.
 * 
 * SPDX-License-Identifier: EPL-2.0
 * 
 * Contributors:
 *   See git history
 *******************************************************************************/
package org.eclipse.birt.report.engine.layout.content;

import org.eclipse.birt.report.engine.content.IContent;
import org.eclipse.birt.report.engine.extension.IReportItemExecutor;

public interface IContentWrapper {
	IContent getContent();

	IReportItemExecutor getExecutor();
}
