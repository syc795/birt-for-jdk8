/***********************************************************************
 * Copyright (c) 2004, 2007 Actuate Corporation.
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

package org.eclipse.birt.report.engine.layout.pdf;

import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.birt.core.exception.BirtException;
import org.eclipse.birt.report.engine.content.Dimension;
import org.eclipse.birt.report.engine.content.IContent;
import org.eclipse.birt.report.engine.content.IReportContent;
import org.eclipse.birt.report.engine.content.IStyle;
import org.eclipse.birt.report.engine.content.ITextContent;
import org.eclipse.birt.report.engine.css.engine.value.FloatValue;
import org.eclipse.birt.report.engine.css.engine.value.ListValue;
import org.eclipse.birt.report.engine.css.engine.value.birt.BIRTConstants;
import org.eclipse.birt.report.engine.executor.IReportExecutor;
import org.eclipse.birt.report.engine.extension.IReportItemExecutor;
import org.eclipse.birt.report.engine.extension.ReportItemExecutorBase;
import org.eclipse.birt.report.engine.ir.DimensionType;
import org.eclipse.birt.report.engine.ir.EngineIRConstants;
import org.eclipse.birt.report.engine.ir.MasterPageDesign;
import org.eclipse.birt.report.engine.ir.PageSetupDesign;
import org.eclipse.birt.report.engine.layout.ILayoutManager;
import org.eclipse.birt.report.engine.layout.PDFConstants;
import org.eclipse.birt.report.engine.layout.area.IArea;
import org.eclipse.birt.report.engine.layout.area.impl.AbstractArea;
import org.eclipse.birt.report.engine.layout.area.impl.AreaFactory;
import org.eclipse.birt.report.engine.layout.area.impl.CellArea;
import org.eclipse.birt.report.engine.layout.area.impl.RowArea;
import org.eclipse.birt.report.engine.layout.pdf.font.FontInfo;
import org.w3c.dom.css.CSSPrimitiveValue;
import org.w3c.dom.css.CSSValue;

public abstract class PDFAbstractLM implements ILayoutManager {

	// identy the status of layout manager
	protected final static int STATUS_START = 0;
	protected final static int STATUS_INPROGRESS = 1;
	protected final static int STATUS_END = 3;

	protected int status = STATUS_START;

	protected static Logger logger = Logger.getLogger(PDFAbstractLM.class.getName());

	protected PDFStackingLM parent;

	protected IContent content;

	protected PDFLayoutEngineContext context;

	protected IReportItemExecutor executor;

	/**
	 * identify if current area is the first area generated by content
	 */
	protected boolean isFirst = true;

	/**
	 * identify if current area is the last area generated by content
	 */
	protected boolean isLast = false;

	protected int specifiedWidth = 0;

	protected int specifiedHeight = 0;

	public IContent getContent() {
		return content;
	}

	public PDFAbstractLM(PDFLayoutEngineContext context, PDFStackingLM parent, IContent content,
			IReportItemExecutor executor) {
		this.context = context;
		this.content = content;
		this.parent = parent;
		this.executor = executor;
	}

	/**
	 * layout the content and its children.
	 * 
	 * It can be called in three status:
	 * <ol>
	 * <li>start, the first time it is called. In this status, it first check if it
	 * need page-break-before,
	 * 
	 * <li>inprogress, the second or more time it is called. In this status, it
	 * tries to layout the content and its children to the current page.
	 * 
	 * <li>end, the last time it is called. In this status, it means all the content
	 * has been layout, it is the time to handle the page-break-after.
	 * </ol>
	 */
	public boolean layout() throws BirtException {
		boolean hasNextPage = false;
		switch (status) {
		case STATUS_START:
			// this element is in-visible, just as it doesn't exits.
			// we must traverse all its children (to let the generate
			// engine create all the content).
			if (handleVisibility()) {
				status = STATUS_END;
				return false;
			}
			// we need put it in the new page or there is no
			// space for the content.
			if (handlePageBreakBefore() || checkAvailableSpace()) {
				status = STATUS_INPROGRESS;
				return true;
			}
			// we need continue to execute.
			status = STATUS_INPROGRESS;
		case STATUS_INPROGRESS:
			hasNextPage = layoutChildren();
			if (hasNextPage) {
				// there are still some content to output,
				// return to caller to create the new page.
				if (!hasNextChild()) {
					closeExecutor();
					status = STATUS_END;
				}
				return true;
			}
			// we have finished the content and all its children.
			closeExecutor();
			status = STATUS_END;
			// We need create an extra page for the following elements, so
			// return true for next element.
			if (handlePageBreakAfter()) {
				return true;
			}
		case STATUS_END:
			return false;
		}
		return hasNextPage;
	}

	protected abstract boolean hasNextChild() throws BirtException;

	protected void closeExecutor() throws BirtException {
		if (executor != null) {
			executor.close();
		}
	}

	protected abstract boolean layoutChildren() throws BirtException;

	public boolean isFinished() {
		return status == STATUS_END;
	}

	protected boolean allowPageBreak() {
		return true;
	}

	public PDFStackingLM getParent() {
		return this.parent;
	}

	protected PDFLayoutManagerFactory getFactory() {
		return context.getFactory();
	}

	protected boolean handlePageBreakBefore() {
		if (content == null) {
			return false;
		}
		IStyle style = content.getStyle();
		String pageBreak = style.getPageBreakBefore();
		handlePageBreakBeforeAvoid(pageBreak);
		if (canPageBreak()) {
			return needPageBreakBefore(pageBreak);
		}
		return false;
	}

	protected boolean handlePageBreakAfter() {
		if (content == null) {
			return false;
		}
		IStyle style = content.getStyle();
		String pageBreak = style.getPageBreakAfter();
		handlePageBreakAfterAvoid(pageBreak);
		if (canPageBreak()) {
			return needPageBreakAfter(pageBreak);
		}
		return false;
	}

	protected void handlePageBreakBeforeAvoid(String pageBreak) {
		if (parent instanceof PDFBlockStackingLM) {
			((PDFBlockStackingLM) parent).setKeepWithNext(IStyle.CSS_AVOID_VALUE == pageBreak);
		}
	}

	protected void handlePageBreakAfterAvoid(String pageBreak) {
		if (IStyle.CSS_AVOID_VALUE == pageBreak) {
			if (parent instanceof PDFBlockStackingLM) {
				((PDFBlockStackingLM) parent).setKeepWithNext(true);
			}
		}

	}

	protected boolean canPageBreak() {
		if (!context.allowPageBreak()) {
			return false;
		}

		PDFAbstractLM p = parent;
		while (p != null) {
			if (!p.allowPageBreak()) {
				return false;
			}
			p = p.getParent();
		}
		return true;
	}

	protected boolean needPageBreakBefore(String pageBreak) {
		boolean ret = hasMasterPageChanged();

		if (IStyle.CSS_ALWAYS_VALUE == pageBreak || IStyle.CSS_LEFT_VALUE == pageBreak
				|| IStyle.CSS_RIGHT_VALUE == pageBreak || IStyle.CSS_SOFT_VALUE == pageBreak) {
			return true;
		}

		return ret;
	}

	protected boolean needPageBreakAfter(String pageBreak) {
		if (IStyle.CSS_ALWAYS_VALUE == pageBreak || IStyle.CSS_LEFT_VALUE == pageBreak
				|| IStyle.CSS_RIGHT_VALUE == pageBreak) {
			return true;
		}
		return false;
	}

	private boolean hasMasterPageChanged() {
		if (content == null) {
			return false;
		}
		IStyle style = content.getStyle();
		if (style == null) {
			return false;
		}
		String newMasterPage = style.getMasterPage();
		if (newMasterPage == null || "".equals(newMasterPage)) //$NON-NLS-1$
		{
			return false;
		}
		String masterPage = context.getMasterPage();
		if (!newMasterPage.equalsIgnoreCase(masterPage)) {
			// check if this master exist
			PageSetupDesign pageSetup = content.getReportContent().getDesign().getPageSetup();
			if (pageSetup.getMasterPageCount() > 0) {
				MasterPageDesign masterPageDesign = pageSetup.findMasterPage(newMasterPage);
				if (masterPageDesign != null) {
					context.setMasterPage(newMasterPage);
					return true;
				}
			}
		}
		return false;
	}

	protected MasterPageDesign getMasterPage(IReportContent report) {
		String masterPage = context.getMasterPage();
		MasterPageDesign pageDesign = null;
		if (masterPage != null && !"".equals(masterPage)) //$NON-NLS-1$
		{
			pageDesign = report.getDesign().findMasterPage(masterPage);
			if (pageDesign != null) {
				return pageDesign;
			}
		}
		return getDefaultMasterPage(report);
	}

	protected MasterPageDesign getDefaultMasterPage(IReportContent report) {
		PageSetupDesign pageSetup = report.getDesign().getPageSetup();
		int pageCount = pageSetup.getMasterPageCount();
		if (pageCount > 0) {
			MasterPageDesign pageDesign = pageSetup.getMasterPage(0);
			context.setMasterPage(pageDesign.getName());
			return pageDesign;
		}
		return null;
	}

	protected boolean handleVisibility() throws BirtException {
		if (isHidden()) {
			traverse(executor);
			return true;
		} else {
			return false;
		}
	}

	private void traverse(IReportItemExecutor executor) throws BirtException {
		if (executor != null) {
			while (executor.hasNextChild()) {
				IReportItemExecutor child = (IReportItemExecutor) executor.getNextChild();
				if (child != null) {
					child.execute();
					traverse(child);
					child.close();
				}
			}
		}
	}

	public abstract void autoPageBreak();

	protected abstract void cancelChildren() throws BirtException;

	public void cancel() throws BirtException {
		if (executor != null && status != STATUS_END) {
			cancelChildren();
			executor.close();
		}
	}

	protected void execute(IReportItemExecutor executor, IContent content) throws BirtException {
		if (executor != null) {
			while (executor.hasNextChild()) {
				IReportItemExecutor childExecutor = executor.getNextChild();
				if (childExecutor != null) {
					IContent childContent = childExecutor.execute();
					content.getChildren().add(childContent);
					execute(childExecutor, childContent);
					childExecutor.close();
				}
			}
		}
	}

	protected static class ReportStackingExecutor extends ReportItemExecutorBase {

		IReportExecutor executor;

		public ReportStackingExecutor(IReportExecutor executor) {
			this.executor = executor;
		}

		public void close() throws BirtException {
			executor.close();
		}

		public IContent execute() {
			return null;
		}

		public IReportItemExecutor getNextChild() throws BirtException {
			return executor.getNextChild();
		}

		public boolean hasNextChild() throws BirtException {
			return executor.hasNextChild();
		}

	}

	protected boolean checkAvailableSpace() {
		if (parent != null) {
			int leftHeight = parent.getCurrentMaxContentHeight();
			if (leftHeight < Math.max(PDFConstants.MIN_LAYOUT_HEIGHT, specifiedHeight)) {
				context.setAutoPageBreak(true);
				return true;
			}
		}
		return false;
	}

	protected void removeBoxProperty(IStyle style) {
		removePadding(style);
		removeBorder(style);
		removeMargin(style);
	}

	protected void removePadding(IStyle style) {
		if (style != null) {
			style.setProperty(IStyle.STYLE_PADDING_LEFT, IStyle.NUMBER_0);
			style.setProperty(IStyle.STYLE_PADDING_RIGHT, IStyle.NUMBER_0);
			style.setProperty(IStyle.STYLE_PADDING_TOP, IStyle.NUMBER_0);
			style.setProperty(IStyle.STYLE_PADDING_BOTTOM, IStyle.NUMBER_0);

		}
	}

	protected void removeBorder(IStyle style) {
		if (style != null) {
			style.setProperty(IStyle.STYLE_BORDER_LEFT_WIDTH, IStyle.NUMBER_0);
			style.setProperty(IStyle.STYLE_BORDER_RIGHT_WIDTH, IStyle.NUMBER_0);
			style.setProperty(IStyle.STYLE_BORDER_TOP_WIDTH, IStyle.NUMBER_0);
			style.setProperty(IStyle.STYLE_BORDER_BOTTOM_WIDTH, IStyle.NUMBER_0);
		}
	}

	protected void removeMargin(IStyle style) {
		if (style != null) {
			style.setProperty(IStyle.STYLE_MARGIN_LEFT, IStyle.NUMBER_0);
			style.setProperty(IStyle.STYLE_MARGIN_RIGHT, IStyle.NUMBER_0);
			style.setProperty(IStyle.STYLE_MARGIN_TOP, IStyle.NUMBER_0);
			style.setProperty(IStyle.STYLE_MARGIN_BOTTOM, IStyle.NUMBER_0);
		}
	}

	protected boolean isHidden() {
		if (content != null) {
			IStyle style = content.getComputedStyle();

			boolean outputDisplayNone = context.getOutputDisplayNone();
			if (!outputDisplayNone && IStyle.NONE_VALUE.equals(style.getProperty(IStyle.STYLE_DISPLAY))) {
				return true;
			}
			return isHiddenByVisibility();
		}
		return false;
	}

	protected boolean isHiddenByVisibility() {
		if (content != null) {
			IStyle style = content.getComputedStyle();
			ListValue formats = (ListValue) style.getProperty(IStyle.STYLE_VISIBLE_FORMAT);
			if (formats.getLength() == 0) {
				return false;
			} else {
				String format = context.getFormat();
				for (int i = 0; i < formats.getLength(); i++) {
					String hiddenFormat = formats.item(i).getCssText();
					if (format.equalsIgnoreCase(hiddenFormat)
							|| BIRTConstants.BIRT_ALL_VALUE.equalsIgnoreCase(hiddenFormat)) {
						return true;
					}
				}
				return false;
			}
		}
		return false;
	}

	protected void calculateSpecifiedHeight() {
		if (content != null) {
			int calHeight = getDimensionValue(content.getHeight());
			if (calHeight > 0) {
				this.specifiedHeight = Math.min(calHeight, context.getMaxHeight());
			}
//			if ( calHeight > 0 && calHeight < context.getMaxHeight( ) )
//			{
//				this.specifiedHeight = calHeight;
//			} 
		}
	}

	protected void calculateSpecifiedWidth() {
		if (content != null) {
			int calWidth = getDimensionValue(content.getWidth(), parent.getCurrentMaxContentWidth());
			if (calWidth > 0 && calWidth < context.getMaxWidth()) {
				this.specifiedWidth = calWidth;
			}
		}
	}

	protected void validateBoxProperty(IStyle style, int maxWidth, int maxHeight) {

		// support negative margin
		int leftMargin = getDimensionValue(style.getProperty(IStyle.STYLE_MARGIN_LEFT), maxWidth);
		int rightMargin = getDimensionValue(style.getProperty(IStyle.STYLE_MARGIN_RIGHT), maxWidth);
		int topMargin = getDimensionValue(style.getProperty(IStyle.STYLE_MARGIN_TOP), maxWidth);
		int bottomMargin = getDimensionValue(style.getProperty(IStyle.STYLE_MARGIN_BOTTOM), maxWidth);

		// do not support negative paddding
		int leftPadding = Math.max(0, getDimensionValue(style.getProperty(IStyle.STYLE_PADDING_LEFT), maxWidth));
		int rightPadding = Math.max(0, getDimensionValue(style.getProperty(IStyle.STYLE_PADDING_RIGHT), maxWidth));
		int topPadding = Math.max(0, getDimensionValue(style.getProperty(IStyle.STYLE_PADDING_TOP), maxWidth));
		int bottomPadding = Math.max(0, getDimensionValue(style.getProperty(IStyle.STYLE_PADDING_BOTTOM), maxWidth));
		// border does not support negative value, do not support pencentage
		// dimension
		int leftBorder = Math.max(0, getDimensionValue(style.getProperty(IStyle.STYLE_BORDER_LEFT_WIDTH), 0));
		int rightBorder = Math.max(0, getDimensionValue(style.getProperty(IStyle.STYLE_BORDER_RIGHT_WIDTH), 0));
		int topBorder = Math.max(0, getDimensionValue(style.getProperty(IStyle.STYLE_BORDER_TOP_WIDTH), 0));
		int bottomBorder = Math.max(0, getDimensionValue(style.getProperty(IStyle.STYLE_BORDER_BOTTOM_WIDTH), 0));

		int[] vs = new int[] { rightMargin, leftMargin, rightPadding, leftPadding, rightBorder, leftBorder };
		resolveBoxConflict(vs, maxWidth);

		int[] hs = new int[] { bottomMargin, topMargin, bottomPadding, topPadding, bottomBorder, topBorder };
		resolveBoxConflict(hs, maxHeight);

		style.setProperty(IStyle.STYLE_MARGIN_LEFT, new FloatValue(CSSPrimitiveValue.CSS_NUMBER, vs[1]));
		style.setProperty(IStyle.STYLE_MARGIN_RIGHT, new FloatValue(CSSPrimitiveValue.CSS_NUMBER, vs[0]));
		style.setProperty(IStyle.STYLE_MARGIN_TOP, new FloatValue(CSSPrimitiveValue.CSS_NUMBER, hs[1]));
		style.setProperty(IStyle.STYLE_MARGIN_BOTTOM, new FloatValue(CSSPrimitiveValue.CSS_NUMBER, hs[0]));

		style.setProperty(IStyle.STYLE_PADDING_LEFT, new FloatValue(CSSPrimitiveValue.CSS_NUMBER, vs[3]));
		style.setProperty(IStyle.STYLE_PADDING_RIGHT, new FloatValue(CSSPrimitiveValue.CSS_NUMBER, vs[2]));
		style.setProperty(IStyle.STYLE_PADDING_TOP, new FloatValue(CSSPrimitiveValue.CSS_NUMBER, hs[3]));
		style.setProperty(IStyle.STYLE_PADDING_BOTTOM, new FloatValue(CSSPrimitiveValue.CSS_NUMBER, hs[2]));

		style.setProperty(IStyle.STYLE_BORDER_LEFT_WIDTH, new FloatValue(CSSPrimitiveValue.CSS_NUMBER, vs[5]));
		style.setProperty(IStyle.STYLE_BORDER_RIGHT_WIDTH, new FloatValue(CSSPrimitiveValue.CSS_NUMBER, vs[4]));
		style.setProperty(IStyle.STYLE_BORDER_TOP_WIDTH, new FloatValue(CSSPrimitiveValue.CSS_NUMBER, hs[5]));
		style.setProperty(IStyle.STYLE_BORDER_BOTTOM_WIDTH, new FloatValue(CSSPrimitiveValue.CSS_NUMBER, hs[4]));
	}

	protected int getDimensionValue(String d) {

		if (d == null) {
			return 0;
		}
		try {
			if (d.endsWith("in") || d.endsWith("in")) //$NON-NLS-1$ //$NON-NLS-2$
			{
				return (int) ((Float.valueOf(d.substring(0, d.length() - 2)).floatValue()) * 72000.0f);
			} else if (d.endsWith("cm") || d.endsWith("CM")) //$NON-NLS-1$//$NON-NLS-2$
			{
				return (int) ((Float.valueOf(d.substring(0, d.length() - 2)).floatValue()) * 72000.0f / 2.54f);
			} else if (d.endsWith("mm") || d.endsWith("MM")) //$NON-NLS-1$ //$NON-NLS-2$
			{
				return (int) ((Float.valueOf(d.substring(0, d.length() - 2)).floatValue()) * 7200.0f / 2.54f);
			} else if (d.endsWith("px") || d.endsWith("PX")) //$NON-NLS-1$//$NON-NLS-2$
			{
				return (int) ((Float.valueOf(d.substring(0, d.length() - 2)).floatValue()) / 96.0f * 72000.0f);// set
																												// as
																												// 96dpi
			} else {
				return (int) ((Float.valueOf(d).floatValue()));
			}
		} catch (NumberFormatException ex) {
			logger.log(Level.WARNING, ex.getLocalizedMessage());
			return 0;
		}
	}

	protected int getDimensionValue(DimensionType d) {
		return getDimensionValue(d, 0);
	}

	protected int getDimensionValue(DimensionType d, int referenceLength) {
		if (d == null) {
			return 0;
		}
		try {
			String units = d.getUnits();
			if (units.equals(EngineIRConstants.UNITS_PT) || units.equals(EngineIRConstants.UNITS_CM)
					|| units.equals(EngineIRConstants.UNITS_MM) || units.equals(EngineIRConstants.UNITS_PC)
					|| units.equals(EngineIRConstants.UNITS_IN)) {
				double point = d.convertTo(EngineIRConstants.UNITS_PT) * 1000;
				return (int) point;
			} else if (units.equals(EngineIRConstants.UNITS_PX)) {
				double point = d.getMeasure() / 72.0d * 72000d;
				return (int) point;
			} else if (units.equals(EngineIRConstants.UNITS_PERCENTAGE)) {
				double point = referenceLength * d.getMeasure() / 100.0;
				return (int) point;
			} else if (units.equals(EngineIRConstants.UNITS_EM) || units.equals(EngineIRConstants.UNITS_EX)) {
				double point = referenceLength * d.getMeasure();
				return (int) point;
			}
		} catch (Exception e) {
			logger.log(Level.WARNING, e.getLocalizedMessage());
			return 0;
		}
		return 0;
	}

	protected int getDimensionValue(CSSValue value) {
		return getDimensionValue(value, 0);
	}

	protected int getDimensionValue(CSSValue value, int referenceLength) {
		if (value != null && (value instanceof FloatValue)) {
			FloatValue fv = (FloatValue) value;
			float v = fv.getFloatValue();
			switch (fv.getPrimitiveType()) {
			case CSSPrimitiveValue.CSS_CM:
				return (int) (v * 72000 / 2.54);

			case CSSPrimitiveValue.CSS_IN:
				return (int) (v * 72000);

			case CSSPrimitiveValue.CSS_MM:
				return (int) (v * 7200 / 2.54);

			case CSSPrimitiveValue.CSS_PT:
				return (int) (v * 1000);
			case CSSPrimitiveValue.CSS_NUMBER:
				return (int) v;
			case CSSPrimitiveValue.CSS_PERCENTAGE:

				return (int) (referenceLength * v / 100.0);
			}
		}
		return 0;
	}

	protected PDFTableLM getTableLayoutManager() {
		PDFStackingLM lm = parent;
		while (lm != null && !(lm instanceof PDFTableLM)) {
			lm = lm.getParent();
		}
		if (lm == null) {
			assert (false);
		}
		return (PDFTableLM) lm;
	}

	protected void resolveBoxConflict(int[] vs, int max) {
		int vTotal = 0;
		for (int i = 0; i < vs.length; i++) {
			vTotal += vs[i];
		}
		resolveConflict(vs, max, vTotal, 0);
	}

	private void resolveConflict(int[] values, int maxTotal, int total, int start) {
		int length = values.length - start;
		if (length == 0) {
			return;
		}
		assert (length > 0);
		if (total > maxTotal) {
			int othersTotal = total - values[start];
			if (values[start] > 0) {
				values[start] = 0;
			}
			resolveConflict(values, maxTotal, othersTotal, start + 1);
		}
	}

	protected void removeBottomBorder(RowArea row) {
		Iterator iter = row.getChildren();
		while (iter.hasNext()) {
			CellArea cell = (CellArea) iter.next();
			if (cell != null) {
				IStyle style = cell.getStyle();
				style.setProperty(IStyle.STYLE_BORDER_BOTTOM_WIDTH, IStyle.NUMBER_0);
			}
		}
	}

	public boolean pageBreakBeforeAvoid() {
		if (content == null) {
			return false;
		}
		IStyle style = content.getStyle();
		String pageBreak = style.getPageBreakBefore();
		if (IStyle.CSS_AVOID_VALUE == pageBreak) {
			return true;
		}
		return false;
	}

	public boolean pageBreakAfterAvoid() {
		if (content == null) {
			return false;
		}
		IStyle style = content.getStyle();
		String pageBreak = style.getPageBreakAfter();
		if (IStyle.CSS_AVOID_VALUE == pageBreak) {
			return true;
		}
		return false;
	}

	public boolean pageBreakInsideAvoid() {
		if (content == null) {
			return false;
		}
		IStyle style = content.getStyle();
		String pageBreak = style.getPageBreakInside();
		if (IStyle.CSS_AVOID_VALUE == pageBreak) {
			return true;
		}
		return false;
	}

	public void close() {
		// TODO Auto-generated method stub

	}

	/**
	 * create block text area by text content
	 * 
	 * @param content          the text content
	 * @param text             the text string
	 * @param contentDimension the content dimension
	 * @param isFirst          if this area is the first area of the content
	 * @param isLast           if this area is the last area of the content
	 * @return
	 */
	protected IArea createBlockTextArea(String text, ITextContent content, FontInfo fi, Dimension contentDimension) {
		AbstractArea textArea = (AbstractArea) AreaFactory.createTextArea(content, text, fi);
		textArea.setWidth(Math.min(context.getMaxWidth(), contentDimension.getWidth()));
		textArea.setHeight(Math.min(context.getMaxHeight(), contentDimension.getHeight()));
		return textArea;
	}

}
