// Decompiled by Jad v1.5.8f. Copyright 2001 Pavel Kouznetsov.
// Jad home page: http://www.kpdus.com/jad.html
// Decompiler options: packimports(3) 
// Source File Name:   ReportAdvancedLauncherTab.java

package org.eclipse.birt.report.debug.ui.launching;

import java.util.TreeMap;
import java.util.TreeSet;

import org.eclipse.birt.report.debug.internal.ui.launcher.IReportLauncherSettings;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.ui.ILaunchConfigurationTab;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.internal.core.plugin.WorkspacePluginModelBase;
import org.eclipse.pde.internal.ui.PDEPlugin;
import org.eclipse.pde.internal.ui.PDEPluginImages;
import org.eclipse.pde.internal.ui.elements.DefaultContentProvider;
import org.eclipse.pde.internal.ui.elements.NamedElement;
import org.eclipse.pde.internal.ui.launcher.AbstractLauncherTab;
import org.eclipse.pde.internal.ui.launcher.LauncherUtils;
import org.eclipse.pde.internal.ui.util.SWTUtil;
import org.eclipse.pde.internal.ui.wizards.ListUtil;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.help.WorkbenchHelp;

/**
 * add comment here
 *  
 */

public class ReportAdvancedLauncherTab extends AbstractLauncherTab
		implements
			ILaunchConfigurationTab,
			IReportLauncherSettings
{

	private static final String FILEDELIMETER = "|";
	private Label fUseListRadio;
	private CheckboxTreeViewer fPluginTreeViewer;
	private Label fVisibleLabel;
	private NamedElement fWorkspacePlugins;
	private IProject fWorkspaceModels[];
	private Button fDefaultsButton;
	private int fNumExternalChecked;
	private int fNumWorkspaceChecked;
	private Image fImage;
	private boolean fShowFeatures;
	private Button fSelectAllButton;
	private Button fDeselectButton;

	class PluginContentProvider extends DefaultContentProvider
			implements
				ITreeContentProvider
	{

		public boolean hasChildren( Object parent )
		{
			return !( parent instanceof IProject );
		}

		public Object[] getChildren( Object parent )
		{
			if ( parent == fWorkspacePlugins )
				return fWorkspaceModels;
			else
				return new Object[0];
		}

		public Object getParent( Object child )
		{
			return null;
		}

		public Object[] getElements( Object input )
		{
			return ( new Object[]{fWorkspacePlugins} );
		}

		PluginContentProvider( )
		{
			super( );
		}
	}

	public ReportAdvancedLauncherTab( )
	{
		this( true );
	}

	/**
	 * @param showFeatures
	 */
	public ReportAdvancedLauncherTab( boolean showFeatures )
	{
		fNumExternalChecked = 0;
		fNumWorkspaceChecked = 0;
		fShowFeatures = true;
		fShowFeatures = showFeatures;
		PDEPlugin.getDefault( ).getLabelProvider( ).connect( this );
		fImage = PDEPluginImages.DESC_REQ_PLUGINS_OBJ.createImage( );
		fWorkspaceModels = ResourcesPlugin.getWorkspace( ).getRoot( )
				.getProjects( );
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.debug.ui.ILaunchConfigurationTab#dispose()
	 */
	public void dispose( )
	{
		PDEPlugin.getDefault( ).getLabelProvider( ).disconnect( this );
		fImage.dispose( );
		super.dispose( );
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.debug.ui.ILaunchConfigurationTab#createControl(org.eclipse.swt.widgets.Composite)
	 */
	public void createControl( Composite parent )
	{
		Composite composite = new Composite( parent, 0 );
		composite.setLayout( new GridLayout( ) );
		fUseListRadio = new Label( composite, 16 );
		fUseListRadio.setText( "Choose project to launch from the list" );
		createPluginList( composite );
		hookListeners( );
		setControl( composite );
		Dialog.applyDialogFont( composite );
		WorkbenchHelp.setHelp( composite,
				"org.eclipse.pde.doc.user.launcher_advanced" );
	}

	/**
	 *  
	 */
	private void hookListeners( )
	{
		SelectionAdapter adapter = new SelectionAdapter( )
		{

			public void widgetSelected( SelectionEvent e )
			{
				useDefaultChanged( );
			}
		};

		fDefaultsButton.addSelectionListener( new SelectionAdapter( )
		{

			public void widgetSelected( SelectionEvent e )
			{
				computeInitialCheckState( );
				updateStatus( );
			}
		} );

		fSelectAllButton.addSelectionListener( new SelectionAdapter( )
		{

			public void widgetSelected( SelectionEvent e )
			{
				toggleGroups( true );
				updateStatus( );
			}
		} );

		fDeselectButton.addSelectionListener( new SelectionAdapter( )
		{

			public void widgetSelected( SelectionEvent e )
			{
				toggleGroups( false );
				updateStatus( );
			}
		} );

	}

	/**
	 * @param select
	 */
	protected void toggleGroups( boolean select )
	{
		handleGroupStateChanged( fWorkspacePlugins, select );
	}

	private void useDefaultChanged( )
	{
		adjustCustomControlEnableState( true );
		updateStatus( );
	}

	/**
	 * @param enable
	 */
	private void adjustCustomControlEnableState( boolean enable )
	{
		fVisibleLabel.setVisible( enable );
		fPluginTreeViewer.getTree( ).setVisible( enable );
		fDefaultsButton.setVisible( enable );
		fSelectAllButton.setVisible( enable );
		fDeselectButton.setVisible( enable );
	}

	/**
	 * @param parent
	 */
	private void createPluginList( Composite parent )
	{
		Composite composite = new Composite( parent, 0 );
		GridLayout layout = new GridLayout( );
		layout.numColumns = 2;
		composite.setLayout( layout );
		composite.setLayoutData( new GridData( 1808 ) );
		fVisibleLabel = new Label( composite, 0 );
		GridData gd = new GridData( );
		gd.horizontalSpan = 2;
		fVisibleLabel.setLayoutData( gd );
		fVisibleLabel.setText( "Availiable project" );
		createPluginViewer( composite );
		createButtonContainer( composite );
	}

	private void computeSubset( )
	{
		Object checked[] = fPluginTreeViewer.getCheckedElements( );
		TreeMap map = new TreeMap( );
		for ( int i = 0; i < checked.length; i++ )
			if ( checked[i] instanceof IProject )
			{
				IProject model = (IProject) checked[i];
				addPluginAndDependencies( model, map );
			}

		checked = map.values( ).toArray( );
		fPluginTreeViewer.setCheckedElements( map.values( ).toArray( ) );
		fNumExternalChecked = 0;
		fNumWorkspaceChecked = 0;
		for ( int i = 0; i < checked.length; i++ )
			if ( checked[i] instanceof WorkspacePluginModelBase )
				fNumWorkspaceChecked++;
			else
				fNumExternalChecked++;

		adjustGroupState( );
	}

	private void addPluginAndDependencies( IProject model, TreeMap map )
	{
		if ( model == null )
			return;
		String id = model.getName( );
		if ( map.containsKey( id ) )
		{
			return;
		}
		else
		{
			map.put( id, model );
			return;
		}
	}

	private void adjustGroupState( )
	{
		fPluginTreeViewer.setChecked( fWorkspacePlugins,
				fNumWorkspaceChecked > 0 );
		fPluginTreeViewer.setGrayed( fWorkspacePlugins,
				fNumWorkspaceChecked > 0
						&& fNumWorkspaceChecked < fWorkspaceModels.length );
	}

	/**
	 * @param composite
	 */
	private void createPluginViewer( Composite composite )
	{
		fPluginTreeViewer = new CheckboxTreeViewer( composite, 2048 );
		fPluginTreeViewer.setContentProvider( new PluginContentProvider( ) );
		fPluginTreeViewer.setLabelProvider( PDEPlugin.getDefault( )
				.getLabelProvider( ) );
		fPluginTreeViewer.setAutoExpandLevel( 2 );
		fPluginTreeViewer.addCheckStateListener( new ICheckStateListener( )
		{

			public void checkStateChanged( final CheckStateChangedEvent event )
			{
				Object element = event.getElement( );
				if ( element instanceof IPluginModelBase )
				{
					handleCheckStateChanged( (IPluginModelBase) element, event
							.getChecked( ) );
				}
				else
				{
					handleGroupStateChanged( element, event.getChecked( ) );
				}
				updateLaunchConfigurationDialog( );
			}
		} );
		fPluginTreeViewer.setSorter( new ListUtil.PluginSorter( )
		{

			public int category( Object obj )
			{
				if ( obj == fWorkspacePlugins )
					return -1;
				return 0;
			}
		} );
		fPluginTreeViewer.getTree( ).setLayoutData( new GridData( 1808 ) );
		Image pluginsImage = PDEPlugin.getDefault( ).getLabelProvider( ).get(
				PDEPluginImages.DESC_REQ_PLUGINS_OBJ );
		fWorkspacePlugins = new NamedElement( PDEPlugin
				.getResourceString( "AdvancedLauncherTab.workspacePlugins" ),
				pluginsImage );
	}

	/**
	 * @param parent
	 */
	private void createButtonContainer( Composite parent )
	{
		Composite composite = new Composite( parent, 0 );
		GridLayout layout = new GridLayout( );
		layout.marginHeight = layout.marginWidth = 0;
		composite.setLayout( layout );
		composite.setLayoutData( new GridData( 1040 ) );
		fSelectAllButton = new Button( composite, 8 );
		fSelectAllButton.setText( PDEPlugin
				.getResourceString( "AdvancedLauncherTab.selectAll" ) );
		fSelectAllButton.setLayoutData( new GridData( 770 ) );
		SWTUtil.setButtonDimensionHint( fSelectAllButton );
		fDeselectButton = new Button( composite, 8 );
		fDeselectButton.setText( PDEPlugin
				.getResourceString( "AdvancedLauncherTab.deselectAll" ) );
		fDeselectButton.setLayoutData( new GridData( 768 ) );
		SWTUtil.setButtonDimensionHint( fDeselectButton );
		fDefaultsButton = new Button( composite, 8 );
		fDefaultsButton.setText( PDEPlugin
				.getResourceString( "AdvancedLauncherTab.defaults" ) );
		fDefaultsButton.setLayoutData( new GridData( 768 ) );
		SWTUtil.setButtonDimensionHint( fDefaultsButton );
	}

	private void initWorkspacePluginsState( ILaunchConfiguration config )
			throws CoreException
	{
		fNumWorkspaceChecked = fWorkspaceModels.length;
		fPluginTreeViewer.setSubtreeChecked( fWorkspacePlugins, true );
		TreeSet deselected = LauncherUtils.parseDeselectedWSIds( config );
		for ( int i = 0; i < fWorkspaceModels.length; i++ )
			if ( deselected.contains( fWorkspaceModels[i].getName( ) )
					&& fPluginTreeViewer
							.setChecked( fWorkspaceModels[i], false ) )
				fNumWorkspaceChecked--;

		if ( fNumWorkspaceChecked == 0 )
			fPluginTreeViewer.setChecked( fWorkspacePlugins, false );
		fPluginTreeViewer.setGrayed( fWorkspacePlugins,
				fNumWorkspaceChecked > 0
						&& fNumWorkspaceChecked < fWorkspaceModels.length );
	}

	/**
	 * @param config
	 * @throws CoreException
	 */
	private void initExternalPluginsState( ILaunchConfiguration config )
			throws CoreException
	{
		fNumExternalChecked = 0;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.debug.ui.ILaunchConfigurationTab#initializeFrom(org.eclipse.debug.core.ILaunchConfiguration)
	 */
	public void initializeFrom( ILaunchConfiguration config )
	{
		try
		{
			if ( fPluginTreeViewer.getInput( ) == null )
			{
				fPluginTreeViewer.setUseHashlookup( true );
				fPluginTreeViewer.setInput( PDEPlugin.getDefault( ) );
				fPluginTreeViewer.reveal( fWorkspacePlugins );
			}
			initWorkspacePluginsState( config );
			initExternalPluginsState( config );
		}
		catch ( CoreException e )
		{
			PDEPlugin.logException( e );
		}
		adjustCustomControlEnableState( true );
		updateStatus( );
	}

	private void computeInitialCheckState( )
	{
		TreeSet wtable = new TreeSet( );
		fNumWorkspaceChecked = 0;
		fNumExternalChecked = 0;
		for ( int i = 0; i < fWorkspaceModels.length; i++ )
		{
			IProject model = fWorkspaceModels[i];
			fNumWorkspaceChecked++;
			String id = model.getName( );
			if ( id != null )
				wtable.add( model.getName( ) );
		}

		fPluginTreeViewer.setSubtreeChecked( fWorkspacePlugins, true );
		adjustGroupState( );
	}

	/**
	 * @param model
	 * @param checked
	 */
	private void handleCheckStateChanged( IPluginModelBase model,
			boolean checked )
	{
		if ( model.getUnderlyingResource( ) == null )
		{
			if ( checked )
			{
				fNumExternalChecked += 1;
			}
			else
			{
				fNumExternalChecked -= 1;
			}
		}
		else
		{
			if ( checked )
			{
				fNumWorkspaceChecked += 1;
			}
			else
			{
				fNumWorkspaceChecked -= 1;
			}
		}
		adjustGroupState( );
	}

	/**
	 * @param group
	 * @param checked
	 */
	private void handleGroupStateChanged( Object group, boolean checked )
	{
		fPluginTreeViewer.setSubtreeChecked( group, checked );
		fPluginTreeViewer.setGrayed( group, false );
		if ( group == fWorkspacePlugins )
			fNumWorkspaceChecked = checked ? fWorkspaceModels.length : 0;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.debug.ui.ILaunchConfigurationTab#setDefaults(org.eclipse.debug.core.ILaunchConfigurationWorkingCopy)
	 */
	public void setDefaults( ILaunchConfigurationWorkingCopy config )
	{
		if ( fShowFeatures )
		{
			config.setAttribute( "default", true );
			config.setAttribute( "usefeatures", false );
		}
		else
		{
			config.setAttribute( "default", true );
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.debug.ui.ILaunchConfigurationTab#performApply(org.eclipse.debug.core.ILaunchConfigurationWorkingCopy)
	 */
	public void performApply( ILaunchConfigurationWorkingCopy config )
	{
		StringBuffer wbuf = new StringBuffer( );
		for ( int i = 0; i < fWorkspaceModels.length; i++ )
		{
			IProject model = fWorkspaceModels[i];
			String path = model.getLocation( ).toOSString( );
			if ( fPluginTreeViewer.getChecked( model ) )
				wbuf.append( "|" + path );
		}

		config.setAttribute( "importproject", wbuf.toString( ) );
		config.setAttribute( "clearws", true );
		config.setAttribute( "askclear", false );
		config.setAttribute( "location0", LauncherUtils.getDefaultWorkspace( ) );
	}

	private void updateStatus( )
	{
		updateStatus( validate( ) );
	}

	private IStatus validate( )
	{
		return AbstractLauncherTab.createStatus( 0, "" );
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.debug.ui.ILaunchConfigurationTab#getName()
	 */
	public String getName( )
	{
		return "Projects";
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.debug.ui.ILaunchConfigurationTab#getImage()
	 */
	public Image getImage( )
	{
		return fImage;
	}

}