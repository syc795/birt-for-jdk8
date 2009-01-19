/*
 *************************************************************************
 * Copyright (c) 2004 Actuate Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Actuate Corporation  - initial API and implementation
 *  
 *************************************************************************
 */

package org.eclipse.birt.data.engine.impl;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.sql.Blob;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.birt.core.data.DataTypeUtil;
import org.eclipse.birt.core.data.ExpressionUtil;
import org.eclipse.birt.core.exception.BirtException;
import org.eclipse.birt.core.util.IOUtil;
import org.eclipse.birt.data.engine.api.DataEngineContext;
import org.eclipse.birt.data.engine.api.IBaseExpression;
import org.eclipse.birt.data.engine.api.IBaseQueryDefinition;
import org.eclipse.birt.data.engine.api.IBinding;
import org.eclipse.birt.data.engine.api.IQueryResults;
import org.eclipse.birt.data.engine.api.IResultIterator;
import org.eclipse.birt.data.engine.api.IResultMetaData;
import org.eclipse.birt.data.engine.api.IShutdownListener;
import org.eclipse.birt.data.engine.api.ISubqueryDefinition;
import org.eclipse.birt.data.engine.api.querydefn.Binding;
import org.eclipse.birt.data.engine.api.querydefn.GroupDefinition;
import org.eclipse.birt.data.engine.api.querydefn.ScriptExpression;
import org.eclipse.birt.data.engine.core.DataException;
import org.eclipse.birt.data.engine.core.security.FileSecurity;
import org.eclipse.birt.data.engine.executor.ResultClass;
import org.eclipse.birt.data.engine.expression.ExpressionCompilerUtil;
import org.eclipse.birt.data.engine.i18n.ResourceConstants;
import org.eclipse.birt.data.engine.impl.document.IDInfo;
import org.eclipse.birt.data.engine.impl.document.IRDSave;
import org.eclipse.birt.data.engine.impl.document.QueryResultInfo;
import org.eclipse.birt.data.engine.impl.document.RDUtil;
import org.eclipse.birt.data.engine.odi.IResultClass;
import org.eclipse.birt.data.engine.odi.IResultObject;
import org.eclipse.birt.data.engine.script.ScriptEvalUtil;
import org.mozilla.javascript.Scriptable;

/**
 * An iterator on a result set from a prepared and executed report query.
 * Multiple ResultIterator objects could be associated with the same
 * QueryResults object.
 */
public class ResultIterator implements IResultIterator
{
	private RDSaveHelper 			rdSaveHelper;
	private Scriptable 				scope;
	
	protected org.eclipse.birt.data.engine.odi.IResultIterator odiResult;
	
	// needed service
	private IServiceForResultSet 	resultService;
	
	// util to findGroup
	private GroupUtil 				groupUtil;
	
	// util to get row id
	protected RowIDUtil 				rowIDUtil;
	
	// used for evaluate binding column value
	private Map 					boundColumnValueMap = new HashMap( );
	private BindingColumnsEvalUtil 	bindingColumnsEvalUtil;
	
	private boolean isFirstNext = true;
	
	private OutputStream metaOutputStream = null;
	private DataOutputStream rowOutputStream = null;
	
	// log instance
	private static Logger logger = Logger.getLogger( ResultIterator.class.getName( ) );

	private List columnList = null;
	private List preparedList = null;
	
	private int rawIdStartingValue = 0;
	
	private IShutdownListener listener;
	
	/**
	 * Constructor for report query (which produces a QueryResults)
	 * 
	 * @param context
	 * @param queryResults
	 * @param query
	 * @param odiResult
	 * @param scope
	 * @throws DataException
	 */
	ResultIterator( IServiceForResultSet rService,
			org.eclipse.birt.data.engine.odi.IResultIterator odiResult,
			Scriptable scope, int rawIdStartingValue ) throws DataException
	{
		Object[] params = {
				rService, odiResult, scope
		};
		logger.entering( ResultIterator.class.getName( ),
				"ResultIterator",
				params );
		assert rService != null
				&& rService.getQueryResults( ) != null && odiResult != null
				&& scope != null;

		this.resultService = rService;
		this.odiResult = odiResult;
		this.scope = scope;
		this.rawIdStartingValue = rawIdStartingValue;
		
		if ( rService.getSession( ).getEngineContext( ).getMode( ) == DataEngineContext.MODE_GENERATION
				|| rService.getSession( ).getEngineContext( ).getMode( ) == DataEngineContext.DIRECT_PRESENTATION )
			this.validateManualBindingExpressions( this.resultService.getQueryDefn( )
					.getBindings( ) );
		if( needCache() && !this.isEmpty( ) )
		{
			try 
			{
				createCacheOutputStream( );
				saveMetaData( );
				IOUtil.writeInt(this.rowOutputStream, this.odiResult.getRowCount());
			} 
			catch (IOException e) 
			{
				throw new DataException( ResourceConstants.CREATE_CACHE_TEMPFILE_ERROR );
			}
		}
		addEngineShutdownListener( );
		
		this.start( );
		prepareBindingColumnsEvalUtil( );
		prepareCurrentRow();
		
		logger.exiting( ResultIterator.class.getName( ), "ResultIterator" );
	}

	/**
	 * 
	 */
	private void addEngineShutdownListener( )
	{
		listener = new IShutdownListener( ) {

			public void dataEngineShutdown( )
			{
				try
				{
					ResultIterator.this.close( );
				}
				catch ( BirtException e )
				{
				}
			}
		};
		this.resultService.getSession( )
				.getEngine( )
				.addShutdownListener( listener );
	}

	/**
	 * 
	 * @param rdSaveHelper
	 * @throws DataException
	 */
	public void setRdSaveHelper( RDSaveHelper rdSaveHelper ) throws DataException
	{
		this.rdSaveHelper = rdSaveHelper;
		prepareBindingColumnsEvalUtil( );
		prepareCurrentRow();
	}
	
	/**
	 * 
	 * @throws FileNotFoundException
	 * @throws DataException 
	 */
	private void createCacheOutputStream( ) throws FileNotFoundException, DataException
	{
		File tmpDir = new File( resultService.getSession( ).getTempDir( ) );
		if (!FileSecurity.fileExist( tmpDir ) || !FileSecurity.fileIsDirectory( tmpDir ))
		{
			FileSecurity.fileMakeDirs( tmpDir );
		}
		metaOutputStream = new BufferedOutputStream( FileSecurity.createFileOutputStream(  ResultSetCacheUtil.getMetaFile( resultService.getSession( ).getTempDir( ),
				resultService.getQueryResults( ).getID( ) ) ),
				1024 );
		rowOutputStream = new DataOutputStream( new BufferedOutputStream( FileSecurity.createFileOutputStream( ResultSetCacheUtil.getDataFile( resultService.getSession( ).getTempDir( ),
				resultService.getQueryResults( ).getID( ) ) ),
				1024 ) );
		File file = ResultSetCacheUtil.getDataFile( resultService.getSession( ).getTempDir( ),
				resultService.getQueryResults( ).getID( ) );
		FileSecurity.fileDeleteOnExit( file );
		file = ResultSetCacheUtil.getMetaFile( resultService.getSession( ).getTempDir( ),
				resultService.getQueryResults( ).getID( ) );
		FileSecurity.fileDeleteOnExit( file ); 
	}
	
	/**
	 * @throws DataException 
	 * @throws IOException 
	 * 
	 */
	private void closeCacheOutputStream( ) throws DataException
	{
		try
		{
			if(rowOutputStream!=null)
			{
				IOUtil.writeInt( rowOutputStream, -1 );
				rowOutputStream.close( );
				rowOutputStream = null;
			}
		}
		catch (IOException e)
		{
			throw new DataException( ResourceConstants.CLOSE_CACHE_TEMPFILE_ERROR );
		}
	}
	
	/**
	 * 
	 * @return
	 */
	private boolean needCache( )
	{
		if( resultService == null || resultService.getQueryDefn( ) == null )
			return false;
		return resultService.getQueryDefn( ).cacheQueryResults();
	}
	

	
	/**
	 * 
	 * @throws DataException
	 * @throws IOException 
	 */
	private void saveMetaData( ) throws DataException, IOException
	{
		List<IBinding> metaMap = new ArrayList<IBinding>( );
		populateDataSetRowMapping( metaMap, odiResult.getResultClass() );
		( (ResultClass) (odiResult.getResultClass()) ).doSave( metaOutputStream, metaMap );
		if(metaOutputStream!=null)
		{
			metaOutputStream.close( );
			metaOutputStream = null;
		}
	}
	
	/**
	 * Populate the new rsClass object instance
	 * 
	 * @param metaMap
	 * @throws DataException
	 */
	private static void populateDataSetRowMapping( List<IBinding> metaMap, IResultClass rsClass )
			throws DataException
	{
		for ( int i = 0; i < rsClass.getFieldCount( ); i++ )
		{
			IBinding binding = new Binding( rsClass.getFieldName( i + 1 ) );
			binding.setExpression( new ScriptExpression( ExpressionUtil.createJSDataSetRowExpression( rsClass.getFieldName( i + 1 ) ) ) );
			metaMap.add( binding );
		}
	}

	/**
	 * Test if there are column bindings that refer to inexist data set columns.
	 * 
	 * @param exprs
	 * @throws DataException
	 */
	private void validateManualBindingExpressions( Map exprs )
			throws DataException
	{
		Set validDataSetColumnNames = populateValidDataSetColumnNameSet( ); 
		Iterator it = exprs.keySet().iterator();
		while( it.hasNext() )
		{
			Object key = it.next();
			IBaseExpression expr =  ((IBinding)exprs.get(key)).getExpression( );
			List usedDataSetExprs = ExpressionCompilerUtil.extractDataSetColumnExpression( expr );
			for ( int j = 0; j < usedDataSetExprs.size( ); j++ )
			{
				if ( !( validDataSetColumnNames.contains( usedDataSetExprs.get( j ) ) || usedDataSetExprs.get( j )
						.equals( "_rowPosition" ) ) )
					throw new DataException( ResourceConstants.COLUMN_BINDING_REFER_TO_INEXIST_COLUMN,
							new Object[]{
									key,
									usedDataSetExprs.get( j )
							} );
			}
		}
	}

	/**
	 * Populate all valid data set column names and alias.
	 * 
	 * @return
	 * @throws DataException
	 */
	private Set populateValidDataSetColumnNameSet( ) throws DataException
	{
		Set validDataSetColumnNames = new HashSet();
		for( int i = 1; i <= this.odiResult.getResultClass( ).getFieldCount( ); i++ )
		{
			validDataSetColumnNames.add( this.odiResult.getResultClass( ).getFieldName( i ) );
			validDataSetColumnNames.add( this.odiResult.getResultClass( ).getFieldAlias( i ) );
		}
		return validDataSetColumnNames;
	}
	
	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#getScope()
	 */
	public Scriptable getScope( )
	{
		return scope;
	}

	/**
	 * Internal method to start the iterator; must be called before any other
	 * method can be used
	 */
	private void start( ) throws DataException
	{
		// Note that the odiResultIterator currently has its cursor located AT
		// its first row. This iterator starts out with cursor BEFORE first row.
		this.getRdSaveHelper( ).doSaveStart( );
	}
	
	/**
	 * Checks to make sure the iterator has started. Throws exception if it has
	 * not.
	 */
	private void checkStarted( ) throws DataException
	{
		if ( this.odiResult == null )
		{
			DataException e = new DataException( ResourceConstants.RESULT_CLOSED );
			logger.logp( Level.FINE,
					ResultIterator.class.getName( ),
					"checkStarted",
					"ResultIterator has been closed.",
					e );
			throw e;
		}
	}
	
	/*
	 * Returns the QueryResults of this result iterator. A convenience method
	 * for the API consumer.
	 * 
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#getQueryResults()
	 */
	public IQueryResults getQueryResults( )
	{
		return resultService.getQueryResults( );
	}
	
	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#next()
	 */
	public boolean next( ) throws BirtException
	{
		checkStarted( );
		
		if ( this.isEmpty( ) )
		{
			return false;
		}
		
		// This behavior does not follow the convention of JDBC. That is before
		// next is called, there is no current row. but from below code, it can
		// be seen that it is not true in our case.
		if ( this.isFirstNext )
		{
			this.isFirstNext = false;
			return odiResult.getCurrentResult( ) != null;
		}
		else
		{
			if ( hasNextRow( ) )
			{
				this.prepareCurrentRow( );
				return true;
			}
			else
			{
				return false;
			}
		}
	}

	/**
	 * clear the preparedList and boundColumnValueMap on next()
	 */
	private void clear( )
	{
		if ( preparedList != null )
			this.preparedList.clear( );
		this.boundColumnValueMap.clear( );
	}
	
	/**
	 * @throws BirtException 
	 * @throws IOException 
	 * 
	 */
	private void saveCurrentRow( ) throws IOException, BirtException
	{
		if( columnList == null )
		{
			columnList = new ArrayList( );
			Iterator keyIterator = boundColumnValueMap.keySet().iterator();
			
			while( keyIterator.hasNext() )
			{
				Object key = keyIterator.next();
				columnList.add(key);
			}
			IOUtil.writeInt(rowOutputStream, columnList.size());
			for( int i=0;i<columnList.size();i++)
			{
				IOUtil.writeObject(rowOutputStream, columnList.get(i));
			}
		}
		IOUtil.writeInt(rowOutputStream, getRowIndex( ));
		IOUtil.writeInt(rowOutputStream, getStartingGroupLevel( ));
		IOUtil.writeInt(rowOutputStream, getEndingGroupLevel( ));
		for (int i = 0; i < columnList.size(); i++)
		{
			IOUtil.writeObject(rowOutputStream, getValue((String) columnList
					.get(i)));
		}
	}
	
	/**
	 * @return
	 * @throws DataException 
	 */
	protected boolean hasNextRow( ) throws DataException
	{
		return this.odiResult.next( );
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#isEmpty()
	 */
	public boolean isEmpty() throws DataException
	{
		return this.odiResult.getRowCount( ) == 0;
	}
	
	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#getRowId()
	 */
	public int getRowId( ) throws BirtException
	{
		checkStarted( );
		
		if ( rowIDUtil == null )
			rowIDUtil = new RowIDUtil( );
		
		if ( this.rowIDUtil.getMode( this.odiResult ) == RowIDUtil.MODE_NORMAL )
			return this.odiResult.getCurrentResultIndex( )
					+ this.rawIdStartingValue;
		else
		{
			IResultObject ob = this.odiResult.getCurrentResult( );
			if ( ob == null )
				return -1;
			else
				return ( (Integer) ob.getFieldValue( rowIDUtil.getRowIdPos( ) ) ).intValue( );
		}
	}
	
	/**
	 * 
	 * @return
	 */
	int getRawIdStartingValue( )
	{
		return this.rawIdStartingValue;
	}
	
	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#getRowIndex()
	 */
	public int getRowIndex( ) throws BirtException
	{
		checkStarted( );
		return odiResult.getCurrentResultIndex( );
	}
	
	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#moveTo(int)
	 */
	public void moveTo( int rowIndex ) throws BirtException
	{
		checkStarted( );
		
		if ( this.isFirstNext )
		{
			next( );
		}

		int currRowIndex = odiResult.getCurrentResultIndex( );
		
		if ( rowIndex < 0 || ( rowIndex >= this.odiResult.getRowCount( ) && this.odiResult.getRowCount()!= -1) )
			throw new DataException( ResourceConstants.INVALID_ROW_INDEX,
					new Integer( rowIndex ) );
		else if ( rowIndex < currRowIndex )
			throw new DataException( ResourceConstants.BACKWARD_SEEK_ERROR );
		else if ( rowIndex == currRowIndex )
			return;

		int gapRows = rowIndex - currRowIndex;
		for ( int i = 0; i < gapRows; i++ )
			this.next( );
	}
	
	/**
	 * @return save util used in report document GENERATION time
	 * @throws DataException 
	 */
	private RDSaveHelper getRdSaveHelper( ) throws DataException
	{
		if ( this.rdSaveHelper == null )
		{
			IDInfo id = null;
			if( this.resultService.getQueryDefn( ) instanceof ISubqueryDefinition )
			{
			    id = new IDInfo( null,
						this.resultService.getQueryDefn( ).getName( ) ); 
			}	
			else
			{
				id = new IDInfo( this.resultService.getQueryResults( ).getID( ) );
			}
			rdSaveHelper = new RDSaveHelper( this.resultService.getSession( ).getEngineContext( ),
					this.resultService.getQueryDefn( ),
					this.odiResult,
					id
					);
		}
		
		return this.rdSaveHelper;
	}
	
	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#getValue(java.lang.String)
	 */
	public Object getValue( String exprName ) throws BirtException
	{
		checkStarted( );
		
		logger.logp( Level.FINER,
				ResultIterator.class.getName( ),
				"getValue",
				"get of value binding column: " + LogUtil.toString( exprName ) );
		
		if ( !this.boundColumnValueMap.containsKey( exprName ) )
		{
			// If there is no value for this specified binding name, evaluate it
			// firstly if resultService contains this binding column
			if ( this.resultService.getBindingExpr( exprName ) != null )
			{
				return prepareBindingColumn( exprName );
			}
			throw new DataException( ResourceConstants.INVALID_BOUND_COLUMN_NAME,
					exprName );
		}
		Object exprValue = boundColumnValueMap.get( exprName );
		if ( exprValue instanceof BirtException )
			throw (BirtException) exprValue;
		return exprValue;
	}
	
	/**
	 * Evaluate the specified column binding in case of its value still not
	 * calculate yet.
	 * 
	 * @param exprName
	 * @return
	 * @throws DataException
	 */
	private Object prepareBindingColumn( String exprName ) throws DataException
	{
		assert bindingColumnsEvalUtil != null;
		if ( this.preparedList == null )
		{
			preparedList = new ArrayList( );
		}
		else if ( this.preparedList.contains( exprName ) )
		{
			return new DataException( ResourceConstants.COLUMN_BINDING_CYCLE,
					exprName );
		}
		this.preparedList.add( exprName );
		Object value = bindingColumnsEvalUtil.evaluateValue( exprName );
		boundColumnValueMap.put( exprName, value );
		return value;
	}
	
	/**
	 * @throws DataException
	 */
	private void prepareCurrentRow( ) throws DataException
	{
		clear( );
		
		bindingColumnsEvalUtil.getColumnsValue( boundColumnValueMap );

		if ( needCache( ) && !this.isEmpty( ) )
		{
			try
			{
				saveCurrentRow( );
			}
			catch ( IOException e )
			{
				throw new DataException( ResourceConstants.WRITE_CACHE_TEMPFILE_ERROR );
			}
			catch ( BirtException e )
			{
				throw DataException.wrap( e );
			}
		}
		
	}

	protected void prepareBindingColumnsEvalUtil( ) throws DataException
	{
		this.bindingColumnsEvalUtil = new BindingColumnsEvalUtil( this.odiResult,
				this.scope,
				this.resultService.getSession( ).getEngineContext( ).getScriptContext( ),
				this.getRdSaveHelper( ),
				this.resultService.getAllBindingExprs( ),
				this.resultService.getAllAutoBindingExprs( ) );
	}
	
	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#getBoolean(java.lang.String)
	 */
	public Boolean getBoolean( String name ) throws BirtException
	{
		return DataTypeUtil.toBoolean( getValue( name ) );
	}

	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#getInteger(java.lang.String)
	 */
	public Integer getInteger( String name ) throws BirtException
	{
		return DataTypeUtil.toInteger( getValue( name ) );
	}

	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#getDouble(java.lang.String)
	 */
	public Double getDouble( String name ) throws BirtException
	{
		return DataTypeUtil.toDouble( getValue( name ) );
	}
	
	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#getString(java.lang.String)
	 */
	public String getString( String name ) throws BirtException
	{
		return DataTypeUtil.toString( getValue( name ) );
	}
	
	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#getBigDecimal(java.lang.String)
	 */
	public BigDecimal getBigDecimal( String name ) throws BirtException
	{
		return DataTypeUtil.toBigDecimal( getValue( name ) );
	}
	
	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#getDate(java.lang.String)
	 */
	public Date getDate( String name ) throws BirtException
	{
		return DataTypeUtil.toDate( getValue( name ) );
	}
	
	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#getBlob(java.lang.String)
	 */
	public Blob getBlob( String name ) throws BirtException
	{
		return DataTypeUtil.toBlob( getValue( name ) );
	}
	
	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#getBytes(java.lang.String)
	 */
	public byte[] getBytes( String name ) throws BirtException
	{
		return DataTypeUtil.toBytes( getValue( name ) );
	}
	
	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#skipToEnd(int)
	 */
	public void skipToEnd( int groupLevel ) throws BirtException
	{
		checkStarted( );
		goThroughGapRows( groupLevel );
		logger.logp( Level.FINER,
				ResultIterator.class.getName( ),
				"skipToEnd",
				"skipping rows to the last row in the current group" );
	}

	/**
	 * 
	 * @param groupLevel
	 * @throws DataException
	 * @throws BirtException
	 */
	protected void goThroughGapRows( int groupLevel ) throws DataException,
			BirtException
	{
		//try to keep all gap row when doing skip
		while ( groupLevel < odiResult.getEndingGroupLevel( )
				&& odiResult.getEndingGroupLevel( ) != 0 && next( ) );
	}

	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#getStartingGroupLevel()
	 */
	public int getStartingGroupLevel( ) throws DataException
	{
		return odiResult.getStartingGroupLevel( );
	}

	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#getEndingGroupLevel()
	 */
	public int getEndingGroupLevel( ) throws DataException
	{
		return odiResult.getEndingGroupLevel( );
	}

	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#getSecondaryIterator(java.lang.String,
	 *      org.mozilla.javascript.Scriptable)
	 */
	public IResultIterator getSecondaryIterator( String subQueryName,
			Scriptable subScope ) throws DataException
	{
		checkStarted( );
		
		IQueryResults results = resultService.execSubquery( odiResult,
				subQueryName,
				subScope );
		logger.logp( Level.FINE,
				ResultIterator.class.getName( ),
				"getSecondaryIterator",
				"Returns the secondary result specified by a SubQuery" );

		IResultIterator resultIt;
		try
		{
			resultIt = results.getResultIterator( );
		}
		catch ( BirtException e )
		{
			throw DataException.wrap( e );
		}
		
		if ( resultIt instanceof ResultIterator )
		{
			this.getRdSaveHelper( ).processForSubQuery( this.getQueryResults( )
					.getID( ),
					(ResultIterator) resultIt,
					subQueryName );
		}
		return resultIt;
	}
	
	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#getResultMetaData()
	 */
	public IResultMetaData getResultMetaData( ) throws DataException
	{
		try
		{
			return new ColumnBindingMetaData( this.resultService.getQueryDefn( ),
					odiResult == null ? null : odiResult.getResultClass( ) );
		}
		finally
		{
			logger.logp( Level.FINE,
					ResultIterator.class.getName( ),
					"getResultMetaData",
					"Returns the result metadata" );
		}
	}
	
	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#close()
	 */
	public void close( ) throws BirtException
	{
		if ( odiResult == null )
			return;
		
		this.resultService.getSession( ).getEngine( ).removeListener( listener );
		if ( this.getRdSaveHelper( ).needsSaveToDoc( ) )
		{
			// save all gap row
			while ( this.next( ) );
			// save results when needs
			this.getRdSaveHelper( ).doSaveFinish( );
		}

		if ( needCache() && !this.isEmpty( ))
		{
			while( this.next() );
			closeCacheOutputStream( );
		}
		if ( odiResult != null )
				odiResult.close( );

		odiResult = null;
		resultService = null;
		logger.logp( Level.FINE,
				ResultIterator.class.getName( ),
				"close",
				"a ResultIterator is closed" );
	}

	/**
	 * @return
	 */
	public org.eclipse.birt.data.engine.odi.IResultIterator getOdiResult( )
	{
		return odiResult;
	}

	/*
	 * @see org.eclipse.birt.data.engine.api.IResultIterator#findGroup(java.lang.Object[])
	 */
	public boolean findGroup( Object[] groupKeyValues ) throws BirtException
	{
		if ( groupUtil == null )
			groupUtil = new GroupUtil( this.resultService.getQueryDefn( ), this );
		return groupUtil.findGroup( groupKeyValues );
	}

	/**
	 * Util class to findGroup
	 */
	private class GroupUtil
	{
		private IBaseQueryDefinition queryDefn;
		private ResultIterator resultIterator;
		
		/**
		 * @param query
		 * @param resultIterator
		 */
		private GroupUtil( IBaseQueryDefinition queryDefn,
				ResultIterator resultIterator )
		{
			this.queryDefn = queryDefn;
			this.resultIterator = resultIterator;
		}
		
		/**
		 * @param query
		 * @param resultIterator
		 * @param groupKeyValues
		 * @return
		 * @throws BirtException
		 */
		public boolean findGroup( Object[] groupKeyValues )
				throws BirtException
		{
			org.eclipse.birt.data.engine.odi.IResultIterator odiResult = resultIterator.getOdiResult( );
			
			List groups = queryDefn.getGroups( );
			if ( groupKeyValues.length > groups.size( ) )
				throw new DataException( ResourceConstants.INCORRECT_GROUP_KEY_VALUES );

			GroupDefinition group = null;

			String[] columnNames = new String[groupKeyValues.length];

			for ( int i = 0; i < columnNames.length; i++ )
			{
				group = (GroupDefinition) groups.get( i );

				columnNames[i] = getGroupKeyExpression( group );
			}

			// Return to first row.
			odiResult.first( 0 );
			if ( odiResult.getCurrentResult( ) == null )
				return false;
			do
			{
				for ( int i = 0; i < columnNames.length; i++ )
				{
					if ( groupKeyValuesEqual( odiResult,
							groupKeyValues,
							columnNames,
							i ) )
					{
						if ( i == columnNames.length - 1 )
							return true;
					}
					else
					{
						// because group level is 1-based. We should use "i+1"
						// to indicate current group.
						resultIterator.skipToEnd( i + 1 );
						break;
					}
				}
			} while ( odiResult.next( ) );

			return false;
		}

		/**
		 * @param groupKeyValues
		 * @param columnExprs
		 * @param i
		 * @return
		 * @throws BirtException
		 */
		private boolean groupKeyValuesEqual(
				org.eclipse.birt.data.engine.odi.IResultIterator odiResult,
				Object[] groupKeyValues, String[] columnExprs, int i )
				throws BirtException
		{
			Object fieldValue = null;
			
	
				fieldValue = ScriptEvalUtil.evalExpr( new ScriptExpression( columnExprs[i] ),
					resultService.getSession( ).getEngineContext( ).getScriptContext( ),
					ResultIterator.this.scope,
					org.eclipse.birt.core.script.ScriptExpression.defaultID,
					0 );
			

			boolean retValue = false;
			if ( fieldValue == groupKeyValues[i] )
			{
				retValue = true;
			}
			else if ( fieldValue != null && groupKeyValues[i] != null )
			{
				if ( fieldValue.getClass( )
						.equals( groupKeyValues[i].getClass( ) ) )
				{
					retValue = equal( fieldValue, groupKeyValues[i] );
				}
				else
				{
					Object convertedOb = DataTypeUtil.convert( groupKeyValues[i],
							fieldValue.getClass( ) );
					retValue = equal( fieldValue, convertedOb );
				}
			}

			return retValue;
		}

		/**
		 * @param value1
		 * @param value2
		 * @return
		 */
		private boolean equal( Object value1, Object value2 )
		{
			//The Date object should be processed individually 
			if( value1 instanceof Date && value2 instanceof Date)
				return ((Date)value1).getTime() == ((Date)value2).getTime();
			else
			    return  value1.equals( value2 );
		}

		/**
		 * The method which extracts column name from group definition.
		 * 
		 * @param group
		 * @return
		 */
		private String getGroupKeyExpression( GroupDefinition group )
		{
			String columnName;
			if ( group.getKeyColumn( ) != null )
			{
				columnName = ExpressionUtil.createJSRowExpression( group.getKeyColumn( ) );
			}
			else
			{
				columnName = group.getKeyExpression( );
			}	
		
			return columnName;
		}
	}
	
	/**
	 * Util class to help ResultIterator to save data into report document
	 */
	class RDSaveHelper
	{
		// context info
		private DataEngineContext context;
		private IBaseQueryDefinition queryDefn;

		// odi result
		private org.eclipse.birt.data.engine.odi.IResultIterator odiResult;

		// id wrapper
		private IDInfo idInfo;
		
		// report document save and load instance
		private IRDSave rdSave;
		
		// init flag
		private boolean isBasicSaved;
		
		/**
		 * @param context
		 * @param queryDefn
		 * @param odiResult
		 * @param idInfo
		 */
		RDSaveHelper( DataEngineContext context, IBaseQueryDefinition queryDefn,
				org.eclipse.birt.data.engine.odi.IResultIterator odiResult,
				IDInfo idInfo )
		{
			this.context = context;
			this.queryDefn = queryDefn;
			this.odiResult = odiResult;
			this.idInfo = idInfo;
		}

		/**
		 * @param name
		 * @param value
		 * @throws DataException
		 */
		void doSaveExpr( Map valueMap ) throws DataException
		{
			doSave( valueMap, false );
		}

		/**
		 * @throws DataException
		 */
		void doSaveFinish( ) throws DataException
		{
			doSave( null, true );
		}

		/**
		 * @throws DataException
		 */
		void doSaveStart( ) throws DataException
		{
			if ( needsSaveToDoc( ) == false )
				return;

			this.getRdSave( ).saveStart( );
		}
		
		/**
		 * @throws DataException
		 * 
		 */
		private void doSave( Map valueMap, boolean finish )
				throws DataException
		{
			if ( needsSaveToDoc( ) == false )
				return;

			if ( isBasicSaved == false )
			{
				isBasicSaved = true;
				this.getRdSave( ).saveResultIterator( this.odiResult,
						this.idInfo.getGroupLevel( ),
						this.idInfo.getSubQueryInfo( ) );
			}

			if ( finish == false )
				this.rdSave.saveExprValue( odiResult.getCurrentResultIndex( ),
						valueMap );
			else
			{
				//TODO:enhance me
				//Save the whole result set, the rows that have never be
				//read will be saved as null value.
				this.rdSave.saveFinish( odiResult.getRowCount() - 1 );
			}
		}

		/**
		 * 
		 * @return
		 * @throws DataException
		 */
		private IRDSave getRdSave( ) throws DataException
		{
			if( this.rdSave == null )
				this.rdSave = RDUtil.newSave( this.context,
					this.queryDefn,
					odiResult.getRowCount( ),
					new QueryResultInfo( this.idInfo.getQueryResultID( ),
							this.idInfo.getsubQueryName( ),
							this.idInfo.getsubQueryIndex( ) ) );
			return this.rdSave;
		}
		
		/**
		 * @return
		 */
		private boolean needsSaveToDoc( )
		{
			if ( this.odiResult == null )
				return false;
			
			if ( context == null
					|| context.getMode( ) == DataEngineContext.DIRECT_PRESENTATION
					|| context.getMode( ) == DataEngineContext.MODE_PRESENTATION )
				return false;
			if ( context.getDocWriter( ) == null )
				return false;

			return true;
		}
		
		/**
		 * @param resultIt
		 * @param subQueryName
		 * @throws DataException
		 */
		private void processForSubQuery( String parentQueryID,
				ResultIterator resultIt, String subQueryName )
				throws DataException
		{
			if ( needsSaveToDoc( ) == false )
				return;

			QueryResults results = (QueryResults) resultIt.getQueryResults( );

			// set query result id
			results.setID( idInfo.buildSubQueryID( parentQueryID ) );

			if ( ( (ISubqueryDefinition) resultIt.resultService.getQueryDefn( ) ).applyOnGroup( ) )
				// init RDSave util of sub query
				resultIt.setRdSaveHelper( new RDSaveHelper( resultIt.resultService.getSession().getEngineContext(),
						resultIt.resultService.getQueryDefn( ),
						resultIt.odiResult,
						new IDInfo( resultIt.getQueryResults( ).getID( ),
								subQueryName,
								results.getGroupLevel( ),
								odiResult.getCurrentGroupIndex( results.getGroupLevel( ) ),
								odiResult.getGroupStartAndEndIndex( results.getGroupLevel( ) ) ) ) );
			else
				resultIt.setRdSaveHelper( new RDSaveHelper( resultIt.resultService.getSession().getEngineContext(),
						resultIt.resultService.getQueryDefn( ),
						resultIt.odiResult,
						new IDInfo( resultIt.getQueryResults( ).getID( ),
								subQueryName,
								1,
								odiResult.getCurrentResultIndex( ),
								IDInfo.getSpecialSubQueryInfo( odiResult.getRowCount( ) ) ) ) );
		}
	}

	public boolean isBeforeFirst( ) throws BirtException
	{
		return !isEmpty( ) && this.isFirstNext;
	}

	public boolean isFirst( ) throws BirtException
	{
		return !isEmpty( ) && getRowIndex( ) == 0;
	}
	
}
