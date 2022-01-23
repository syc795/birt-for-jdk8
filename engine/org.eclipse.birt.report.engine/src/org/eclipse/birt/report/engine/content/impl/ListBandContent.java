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

package org.eclipse.birt.report.engine.content.impl;

import org.eclipse.birt.core.exception.BirtException;
import org.eclipse.birt.report.engine.content.IContent;
import org.eclipse.birt.report.engine.content.IContentVisitor;
import org.eclipse.birt.report.engine.content.IListBandContent;
import org.eclipse.birt.report.engine.content.IReportContent;

public class ListBandContent extends AbstractBandContent implements IListBandContent {

	ListBandContent(IListBandContent listBand) {
		super(listBand);
	}

	ListBandContent(IReportContent report) {
		super(report);
	}

	public int getContentType() {
		return LIST_BAND_CONTENT;
	}

	public Object accept(IContentVisitor visitor, Object value) throws BirtException {
		return visitor.visitListBand(this, value);
	}

	protected IContent cloneContent() {
		return new ListBandContent(this);
	}
}
