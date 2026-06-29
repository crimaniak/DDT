/*******************************************************************************
 * Copyright (c) 2013 Bruno Medeiros and other Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Bruno Medeiros - initial API and implementation
 *******************************************************************************/
package mmrnmhrm.core.dub_model;

import static melnorme.lang.ide.core.operations.ILangOperationsListener_Default.ProcessStartKind.ENGINE_TOOLS;
import static melnorme.lang.ide.core.utils.TextMessageUtils.headerBIG;
import static melnorme.utilbox.core.Assert.AssertNamespace.assertNotNull;
import static melnorme.utilbox.core.Assert.AssertNamespace.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;

import dtool.dub.DubBundle;
import dtool.dub.DubBundle.DubBundleException;
import dtool.dub.DubBundleDescription;
import dtool.dub.DubDescribeRunner;
import dtool.dub.DubManifestParser;
import dtool.engine.compiler_installs.CompilerInstall;
import dtool.engine.compiler_installs.SearchCompilersOnPathOperation;
import melnorme.lang.ide.core.LangCore;
import melnorme.lang.ide.core.operations.ILangOperationsListener_Default.IToolOperationMonitor;
import melnorme.lang.ide.core.operations.ToolManager;
import melnorme.lang.ide.core.project_model.BundleModelManager;
import melnorme.lang.ide.core.project_model.LangBundleModel;
import melnorme.lang.ide.core.utils.EclipseUtils;
import melnorme.lang.ide.core.utils.ResourceUtils;
import melnorme.lang.ide.core.utils.operation.EclipseAsynchJobAdapter;
import melnorme.lang.ide.core.utils.operation.EclipseAsynchJobAdapter.IRunnableWithJob;
import melnorme.lang.tooling.BundlePath;
import melnorme.lang.tooling.bundle.BundleInfo;
import melnorme.utilbox.concurrency.CancellableTask;
import melnorme.utilbox.concurrency.ITaskAgent;
import melnorme.utilbox.concurrency.OperationCancellation;
import melnorme.utilbox.core.CommonException;
import mmrnmhrm.core.DeeCoreMessages;
import mmrnmhrm.core.dub_model.DeeBundleModelManager.DeeBundleModel;
import mmrnmhrm.core.dub_model.DeeBundleModelManager.WorkspaceModelManagerTask;
import mmrnmhrm.core.engine.DeeToolManager;

/**
 * Updates a {@link DeeBundleModel} when resource changes occur, using 'dub describe'.
 * Also creates problem markers on the Eclipse workspace. 
 */
public class DeeBundleModelManager extends BundleModelManager<DeeBundleModel> {
	
	public static class DeeBundleModel extends LangBundleModel {
		
		@Override
		public BundleInfo setBundleInfo(IProject project, BundleInfo newProjectInfo) {
			return super.setBundleInfo(project, newProjectInfo);
		}
		
	}
	
	/* -----------------  ----------------- */
	
	public static final String DUB_PROBLEM_ID = LangCore.PLUGIN_ID + ".DubProblem";
	
	public DeeBundleModelManager() {
		super(new DeeBundleModel());
	}
	
	public DeeToolManager getProcessManager() {
		return (DeeToolManager) LangCore.getToolManager();
	}
	
	@Override
	protected ManagerResourceListener init_createResourceListener() {
		return new ManagerResourceListener();
	}
	
	@Override
	protected void handleBundleProjectAdded(IProject project) {
		handleBundleManifestChanged(project);
	}
	
	protected void handleBundleManifestChanged(final IProject project) {
		BundleInfo unresolvedProjectInfo = createNewInfo(project);
		getModel().setBundleInfo(project, unresolvedProjectInfo); 
		
		modelAgent.submitTask(new ProjectModelDubDescribeTask(this, project, unresolvedProjectInfo));
	}
	
	@Override
	protected BundleInfo createNewInfo(IProject project) {
		DubBundleDescription unresolvedDescription = readUnresolvedBundleDescription(project);
		
		if(unresolvedDescription.hasErrors()) {
			// Remove the error - we will run `dub describe` anyways, as DUB can parse SDL and
			// other formats that DDT's own manifest reader does not support.
			unresolvedDescription = new DubBundleDescription(unresolvedDescription.getMainBundle(),
				(DubBundleException) null);
		}
		
		/* XXX: Could it be a problem to run a possibly long-running operation here? */
		return createProjectInfo(unresolvedDescription);
	}
	
	protected DubBundleDescription readUnresolvedBundleDescription(final IProject project) {
		java.nio.file.Path location = project.getLocation().toFile().toPath();
		BundlePath bundlePath = BundlePath.create(location);
		DubBundle unresolvedBundle = DubManifestParser.parseDubBundleFromLocation2(bundlePath);
		if(unresolvedBundle == null) {
			// Can happen if using SDL format, which we don't know how to parse. Provide dummy bundle
			String message = "Cannot read bundle (is SDL format instead of JSON?";
			unresolvedBundle = new DubBundle(
				bundlePath, "(UNKNOWN)", new DubBundleException(message), null, null, 
				null, null, null, null, null, null
			);
		}
		
		return new DubBundleDescription(unresolvedBundle);
	}
	
	protected final void updateProjectInfo(IProject project, BundleInfo oldInfo, 
			DubBundleDescription dubBundleDescription) {
		getModel().updateProjectInfo(project, oldInfo, createProjectInfo(dubBundleDescription));
	}
	
	/* ----------------------------------- */
	
	protected static class SearchCompilersOnPathOperation_Eclipse extends SearchCompilersOnPathOperation {
		@Override
		protected void handleWarning(String message) {
			LangCore.logWarning(message);
		}
	}

	public static CompilerInstall detectPreferredCompiler() {
		return new SearchCompilersOnPathOperation_Eclipse()
				.searchForCompilersInDefaultPathEnvVars()
				.getPreferredInstall();
	}

	protected BundleInfo createProjectInfo(DubBundleDescription dubBundleDescription) {
		CompilerInstall compilerInstall = new SearchCompilersOnPathOperation_Eclipse().
				searchForCompilersInDefaultPathEnvVars().getPreferredInstall();
		
		return new BundleInfo(compilerInstall, dubBundleDescription);
	}
	
	/**
	 * Await for all pending resolutions to complete.
	 * Of use mainly for test code.
	 */
	public void syncPendingUpdates() throws InterruptedException {
		modelAgent.waitForPendingTasks();
	}
	
	/** WARNING: this API is intended to be used for tests only */
	public ITaskAgent internal_getModelAgent() {
		return modelAgent;
	}
	
	public static IMarker[] getDubErrorMarkers(IProject project) throws CoreException {
		return project.findMarkers(DUB_PROBLEM_ID, true, IResource.DEPTH_ONE);
	}
	
	protected abstract class WorkspaceModelManagerTask extends CancellableTask{
		
		protected final DeeBundleModelManager workspaceModelManager;
		
		public WorkspaceModelManagerTask() {
			this.workspaceModelManager = DeeBundleModelManager.this;
		}
		
		protected void logInternalError(Throwable t) {
			LangCore.logInternalError(t);
		}
		
	}
	
}


class ProjectModelDubDescribeTask extends ProjectUpdateBuildpathTask implements IRunnableWithJob {
	
	protected final IProject project;
	protected final BundleInfo unresolvedProjectInfo;
	protected final DubBundleDescription unresolvedDescription;
	
	protected ProjectModelDubDescribeTask(DeeBundleModelManager dubModelManager, IProject project, 
			BundleInfo unresolvedProjectInfo) {
		super(dubModelManager);
		this.project = project;
		this.unresolvedProjectInfo = unresolvedProjectInfo;
		unresolvedDescription = unresolvedProjectInfo.getBundleDesc();
	}
	
	protected DeeToolManager getProcessManager() {
		return workspaceModelManager.getProcessManager();
	}
	
	@Override
	protected void doRun() {
		
			try {
				ResourceUtils.getWorkspace().run(new IWorkspaceRunnable() {
					
					@Override
					public void run(IProgressMonitor monitor) throws CoreException {
						if(project.exists() == false) {
							return;
						}
						deleteDubMarkers(project);
						
						if(unresolvedDescription.hasErrors()) {
							setDubErrorMarker(project, unresolvedDescription.getError());
						}
					}
				}, project, 0, null);
				
			} catch (CoreException ce) {
				logInternalError(ce);
			}
			
			if(unresolvedDescription.hasErrors()) {
				// don't run `dub describe` if there was critical errors, just let the markers be updated
				return;
			}
		
		try {
			//
			Job job = EclipseAsynchJobAdapter.runUnderAsynchJob(getNameForJob(), this);
			// Note: we await the job to finish, so that the describe task is synchronous to the bundle resolution
			// this is so that DeeBundleModelManager.syncPendingUpdates() works.
			// Ideally this would be written in a more elegant way, with Future's etc.
			job.join();
		} catch (InterruptedException e) {
			return;
		}
	}
	
	protected String getNameForJob() {
		return "Running 'dub describe' on project: " + project.getName();
	}
	
	protected void deleteDubMarkers(IProject project) throws CoreException {
		IMarker[] markers = DeeBundleModelManager.getDubErrorMarkers(project);
		for (IMarker marker : markers) {
			marker.delete();
		}
	}
	
	protected void setDubErrorMarker(IProject project, DubBundleException error) throws CoreException {
		setDubErrorMarker(project, error.getExtendedMessage());
	}
	
	protected void setDubErrorMarker(IProject project, String message) throws CoreException {
		IMarker dubMarker = project.createMarker(DeeBundleModelManager.DUB_PROBLEM_ID);
		dubMarker.setAttribute(IMarker.MESSAGE, message);
		dubMarker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
	}
	
	@Override
	public void runUnderEclipseJob(IProgressMonitor monitor) {
		assertNotNull(monitor);
		try {
			try {
				resolveProjectOperation(monitor);
			} catch(CoreException e) {
				throw EclipseUtils.createCommonException(e);
			}
		} catch(OperationCancellation ce) {
			return;
		} catch(CommonException ce) {
			try {
				EclipseUtils.getWorkspace().run(new IWorkspaceRunnable() {
					@Override
					public void run(IProgressMonitor monitor) throws CoreException {
						if(project.exists() == false) {
							return;
						}
						setProjectDubError(project, ce);
					}
				}, null, 0, monitor);
				
			} catch (CoreException e) {
				logInternalError(ce);
			}
		}
	}
	
	protected ToolManager getToolManager() {
		return LangCore.getToolManager();
	}
	
	protected Void resolveProjectOperation(IProgressMonitor pm) 
			throws CoreException, CommonException, OperationCancellation {
		IPath projectLocation = project.getLocation();
		if(projectLocation == null) {
			return null; // Project no longer exists, or not stored in the local filesystem.
		}
		
		Path projectDir = projectLocation.toFile().toPath();

		if (isSubpackageOfParent(projectDir)) {
			LangCore.logInfo("Skipping 'dub describe' for " + project.getName() +
				": directory is a declared subpackage of parent dub project");
			return null;
		}

		BundlePath bundlePath = BundlePath.create(projectDir);

		String dubPath = getToolManager().getSDKToolPath(project).toString();
		
		IToolOperationMonitor opMonitor = getToolManager().startNewOperation(ENGINE_TOOLS, true, false);
		opMonitor.writeInfoMessage(
			headerBIG(MessageFormat.format(DeeCoreMessages.RunningDubDescribe, project.getName())));
		
		// TODO: add --skip-registry to dub command
		
		final DubBundleDescription describedBundle = new DubDescribeRunner(bundlePath, dubPath, true)
			.runDescribeOperation();
		
		if(describedBundle.hasErrors()) {
			throw new CommonException("DUB describe error: ", describedBundle.getError());
		}
		
		DubBundleDescription bundleDesc = getEffectiveBundleDescription(describedBundle);
		
		ResourceUtils.getWorkspace().run(new IWorkspaceRunnable() {
			@Override
			public void run(IProgressMonitor monitor) throws CoreException {
				if(project.exists() == false) {
					return;
				}
				assertTrue(!bundleDesc.hasErrors());
				deleteDubMarkers(project);
				
				workspaceModelManager.updateProjectInfo(project, unresolvedProjectInfo, bundleDesc);
				project.refreshLocal(1, monitor);
			}
		}, null, 0, pm);
		
		return null;
	}
	
	protected DubBundleDescription getEffectiveBundleDescription(final DubBundleDescription describedBundle) {
		// Because `dub describe` does not supply configuration info, we take the described bundle 
		// and add to it the configuration info from the previous, parsed description.
		
		DubBundle mainBundle = describedBundle.getMainBundle();
		
		mainBundle = new DubBundle(
			mainBundle.getBundlePath(), 
			mainBundle.getBundleName(), 
			mainBundle.error, 
			mainBundle.version, 
			mainBundle.srcFolders, 
			mainBundle.getEffectiveSourceFolders(), 
			mainBundle.bundleFiles, 
			mainBundle.getDependencyRefs(), 
			mainBundle.getTargetName(), 
			mainBundle.getTargetPath(), 
			unresolvedDescription.getMainBundle().getConfigurations().toArrayList() // add configs here
		);
		
		return new DubBundleDescription(mainBundle, describedBundle.getBundleDependencies());
	}
	
	/**
	 * Returns true if {@code projectDir} is declared as a {@code subPackage} in the parent
	 * directory's dub manifest (dub.sdl or dub.json). When this is the case, running
	 * "dub describe" inside the subpackage directory fails because it lacks the parent's
	 * dependency selections and context.
	 */
	private static boolean isSubpackageOfParent(Path projectDir) {
		Path parentDir = projectDir.getParent();
		if (parentDir == null) return false;
		String dirName = projectDir.getFileName().toString();

		for (String sdlName : new String[]{"dub.sdl", "dub.recipe"}) {
			Path sdlPath = parentDir.resolve(sdlName);
			if (Files.isRegularFile(sdlPath)) {
				try {
					String content = new String(Files.readAllBytes(sdlPath), StandardCharsets.UTF_8);
					if (sdlListsSubpackage(content, dirName)) return true;
				} catch (IOException e) { /* ignore, treat as not a subpackage */ }
			}
		}

		Path jsonPath = parentDir.resolve("dub.json");
		if (Files.isRegularFile(jsonPath)) {
			try {
				String content = new String(Files.readAllBytes(jsonPath), StandardCharsets.UTF_8);
				if (jsonListsSubpackage(content, dirName)) return true;
			} catch (IOException e) { /* ignore */ }
		}

		return false;
	}

	private static boolean sdlListsSubpackage(String sdlContent, String dirName) {
		for (String line : sdlContent.split("\\r?\\n")) {
			String trimmed = line.trim();
			if (!trimmed.toLowerCase().startsWith("subpackage")) continue;
			// Extract first quoted value: subPackage "http"
			int q1 = trimmed.indexOf('"');
			int q2 = trimmed.lastIndexOf('"');
			if (q1 >= 0 && q2 > q1) {
				String val = trimmed.substring(q1 + 1, q2);
				// Match "http" or "some/path/http"
				if (val.equals(dirName) || val.endsWith("/" + dirName) || val.endsWith("\\" + dirName)) {
					return true;
				}
			}
		}
		return false;
	}

	@SuppressWarnings("deprecation")
	private static boolean jsonListsSubpackage(String jsonContent, String dirName) {
		try {
			JsonElement root = new JsonParser().parse(jsonContent);
			if (!root.isJsonObject()) return false;
			JsonObject obj = root.getAsJsonObject();
			if (!obj.has("subPackages")) return false;
			JsonElement sp = obj.get("subPackages");
			if (!sp.isJsonArray()) return false;
			JsonArray arr = sp.getAsJsonArray();
			for (JsonElement elem : arr) {
				if (elem.isJsonPrimitive()) {
					String val = elem.getAsString();
					if (val.equals(dirName) || val.endsWith("/" + dirName) || val.endsWith("\\" + dirName)) {
						return true;
					}
				} else if (elem.isJsonObject()) {
					// Inline subpackage object with optional "path" field
					JsonObject sub = elem.getAsJsonObject();
					if (sub.has("path")) {
						String val = sub.get("path").getAsString();
						if (val.equals(dirName) || val.endsWith("/" + dirName) || val.endsWith("\\" + dirName)) {
							return true;
						}
					}
				}
			}
		} catch (Exception e) { /* malformed JSON, ignore */ }
		return false;
	}

	protected void setProjectDubError(IProject project, CommonException ce) throws CoreException {
		
		DubBundleException dubError = new DubBundleException(ce.getMessage(), ce.getCause());
		
		DubBundle main = unresolvedDescription.getMainBundle();
		DubBundleDescription bundleDesc = new DubBundleDescription(main, dubError);
		BundleInfo newProjectInfo = new BundleInfo(unresolvedProjectInfo.getCompilerInstall(), bundleDesc);
		workspaceModelManager.getModel().setBundleInfo(project, newProjectInfo);
		
		setDubErrorMarker(project, dubError);
	}
	
}

abstract class ProjectUpdateBuildpathTask extends WorkspaceModelManagerTask {
	
	protected ProjectUpdateBuildpathTask(DeeBundleModelManager dubModelManager) {
		dubModelManager.super();
	}
	
}