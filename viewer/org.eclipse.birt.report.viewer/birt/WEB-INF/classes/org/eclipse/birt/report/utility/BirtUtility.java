/*************************************************************************************
 * Copyright (c) 2004 Actuate Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Actuate Corporation - Initial implementation.
 ************************************************************************************/

package org.eclipse.birt.report.utility;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.eclipse.birt.report.IBirtConstants;
import org.eclipse.birt.report.context.BaseAttributeBean;
import org.eclipse.birt.report.engine.api.IEngineTask;

/**
 * Utilities for Birt Report Service
 * 
 */
public class BirtUtility
{

	/**
	 * add current task in http session
	 * 
	 * @param request
	 * @param task
	 */
	public static void addTask( HttpServletRequest request, IEngineTask task )
	{
		if ( request == null || task == null )
			return;

		// get task id
		BaseAttributeBean attrBean = (BaseAttributeBean) request
				.getAttribute( IBirtConstants.ATTRIBUTE_BEAN );
		if ( attrBean == null )
			return;

		String taskid = attrBean.getTaskId( );

		// get task map
		HttpSession session = request.getSession( true );
		Map map = (Map) session.getAttribute( IBirtConstants.TASK_MAP );
		if ( map == null )
		{
			map = new HashMap( );
			session.setAttribute( IBirtConstants.TASK_MAP, map );
		}

		// add task
		synchronized ( map )
		{
			if ( taskid != null )
				map.put( taskid, task );
		}
	}
}
