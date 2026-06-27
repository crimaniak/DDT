/*******************************************************************************
 * Copyright (c) 2014 Bruno Medeiros and other Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Bruno Medeiros - initial API and implementation
 *******************************************************************************/
package dtool.dub;


import static melnorme.utilbox.core.Assert.AssertNamespace.assertFail;
import static melnorme.utilbox.core.Assert.AssertNamespace.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeoutException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dtool.dub.DubBundle.BundleFile;
import dtool.tests.CommonDToolTest;
import dtool.tests.DToolTestResources;
import melnorme.lang.tooling.BundlePath;
import melnorme.lang.tooling.bundle.DependencyRef;
import melnorme.utilbox.collections.ArrayList2;
import melnorme.utilbox.concurrency.OperationCancellation;
import melnorme.utilbox.core.CommonException;
import melnorme.utilbox.misc.ArrayUtil;
import melnorme.utilbox.misc.Location;
import melnorme.utilbox.misc.StringUtil;
import melnorme.utilbox.process.ExternalProcessHelper;
import melnorme.utilbox.process.ExternalProcessHelper.ExternalProcessResult;

public class CommonDubTest extends CommonDToolTest {
	
	public static final Location DUB_TEST_BUNDLES = DToolTestResources.getTestResourceLoc("dub");	
	
	public static final BundlePath XPTO_BUNDLE_PATH = bundlePath(DUB_TEST_BUNDLES, "XptoBundle");
	
	public CommonDubTest() {
		super();
	}
	
	public static Path path(String str) {
		return Paths.get(str);
	}
	
	public static Path[] paths(String... str) {
		Path[] newArray = new Path[str.length];
		for (int i = 0; i < str.length; i++) {
			newArray[i] = Paths.get(str[i]); 
		}
		return newArray;
	}
	
	
	public static final DubBundleChecker[] IGNORE_DEPS = new DubBundleChecker[0];
	public static final String[] IGNORE_RAW_DEPS = new String[0];
	
	public static final String ERROR_DUB_RETURNED_NON_ZERO = "DUB describe error";
	
	public static class DubBundleChecker extends CommonChecker {
		
		public final Location location;
		public final String bundleName;
		public final String errorMsgStart;
		public final String version;
		public final Path[] sourceFolders;
		public final String[] rawDeps;
		public final DubBundleChecker[] expectedDeps;
		
		public DubBundleChecker(Location location, String bundleName) {
			this(location, bundleName, null, IGNORE_STR, null, IGNORE_RAW_DEPS, IGNORE_DEPS);
		}
		
		public DubBundleChecker(Location location, String bundleName, String errorMsgStart, String version,
				Path[] sourceFolders, String[] rawDeps, DubBundleChecker[] deps) {
			this.location = location;
			this.bundleName = bundleName;
			this.errorMsgStart = errorMsgStart;
			this.version = version;
			this.sourceFolders = sourceFolders;
			this.rawDeps = rawDeps;
			this.expectedDeps = deps;
		}
		
		@Override
		protected boolean isIgnoreArray(Object[] expected){
			return expected == IGNORE_DEPS || expected == IGNORE_ARR || expected == IGNORE_RAW_DEPS;
		}
		
		public boolean isResolvedOnlyError() {
			return errorMsgStart == ERROR_DUB_RETURNED_NON_ZERO;
		}
		
		public void check(DubBundle bundle, boolean isResolved) {
			checkAllExceptDepRefs(bundle, isResolved);
			checkDepRefs(bundle);
		}
		
		protected void checkAllExceptDepRefs(DubBundle bundle, boolean isResolved) {
			checkAreEqual(bundle.getLocation(), location);
			checkAreEqual(bundle.name, bundleName);
			if(isResolvedOnlyError() && !isResolved) {
				// Don't check, error occurs only in resolved bundles
			} else {
				assertExceptionMsgStart(bundle.error, errorMsgStart);
			}
			checkAreEqual(bundle.version, version);
			checkAreEqualArray(bundle.getEffectiveSourceFolders(), ignoreIfNull(sourceFolders));
		}
		
		protected void checkDepRefs(DubBundle bundle) {
			if(rawDeps == IGNORE_RAW_DEPS) {
				return;
			}
			assertEquals(bundle.getDependencyRefs().length, rawDeps.length);
			for (int i = 0; i < rawDeps.length; i++) {
				String expRawDep = rawDeps[i];
				DependencyRef depRef = bundle.getDependencyRefs()[i];
				checkAreEqual(depRef.getBundleName(), expRawDep);
			}
		}
		
		public void checkBundleDescription(DubBundleDescription bundleDescription, boolean expectedIsResolved) {
			if(bundleDescription.isResolved() != expectedIsResolved) {
				assertFail(StringUtil.asString(bundleDescription.getError()));
			}
			
			if(!expectedIsResolved) {
				check(bundleDescription.getMainBundle(), expectedIsResolved);
				assertTrue(bundleDescription.hasErrors() ||
					bundleDescription.getBundleDependencies().length == 0);
				return;
			} else {
				checkAllExceptDepRefs(bundleDescription.getMainBundle(), expectedIsResolved);
			}
			
			if(expectedDeps == IGNORE_DEPS) 
				return;
			
			DubBundle[] deps = bundleDescription.getBundleDependencies();
			assertTrue(expectedDeps.length == deps.length);
			
			ArrayList2<DubBundle> depsToCheck = ArrayList2.create(deps);
			ArrayList2<DubBundleChecker> expectedDepsToCheck = ArrayList2.create(expectedDeps);
			
			for (DubBundle dubBundle : depsToCheck) {
				boolean checked = false;
				for (DubBundleChecker dubBundleChecker : expectedDepsToCheck) {
					if(dubBundleChecker.bundleName.equals(dubBundle.getBundleName())) {
						dubBundleChecker.check(dubBundle, true);
						expectedDepsToCheck.remove(dubBundleChecker);
						checked = true;
						break;
					}
				}
				assertTrue(checked);
			}
		}
		
		protected void checkResolvedBundle(DubBundleDescription bundleDescription, String dubDescribeError) {
			assertExceptionContains(bundleDescription.error, dubDescribeError);
			
			boolean isResolved = dubDescribeError == null;
			checkBundleDescription(bundleDescription, isResolved);
		}
		
	}
	
	public static DubBundleChecker main(Location location, String errorMsgStart, String name, 
			String version, Path[] srcFolders, String[] rawDeps, DubBundleChecker... deps) {
		return new DubBundleChecker(location, name, errorMsgStart, version, srcFolders, rawDeps, deps);
	}
	
	public static DubBundleChecker bundle(Location location, String errorMsgStart, String name, 
			String version, Path[] srcFolders) {
		return main(location, errorMsgStart, name, version, srcFolders, IGNORE_RAW_DEPS, IGNORE_DEPS);
	}
	
	public static DubBundleChecker bundle(Location location, String name) {
		return new DubBundleChecker(location, name, null, IGNORE_STR, null, IGNORE_RAW_DEPS, IGNORE_DEPS);
	}
	
	public static DubBundleChecker bundle(String errorMsgStart, String name) {
		return new DubBundleChecker(IGNORE_PATH, name, errorMsgStart, IGNORE_STR, null, IGNORE_RAW_DEPS, IGNORE_DEPS);
	}
	
	public static BundleFile bf(String filePath) {
		return new BundleFile(filePath, false);
	}
	
	public static String[] rawDeps(String... rawDeps) {
		return rawDeps;
	}
	
	protected void checkResolvedBundle(DubBundleDescription bundleDescription, String dubDescribeError, 
			DubBundleChecker mainBundleChecker) {
		mainBundleChecker.checkResolvedBundle(bundleDescription, dubDescribeError);
	}
	
	/* ------------------------------ */
	
	protected String runDubDescribe(BundlePath workingDir) throws Exception {
		ExternalProcessResult processResult = startDubProcess(workingDir, false, "describe")
				.awaitTerminationAndResult(2000, true);
		
		return processResult.getStdOutBytes().toString(StringUtil.UTF8);
	}
	
	public static ExternalProcessHelper startDubProcess(BundlePath bundlePath, 
			boolean redirectStdErr, String... arguments) 
			throws IOException {
		String[] command = ArrayUtil.prepend(testsDubPath(), arguments);
		ProcessBuilder pb = new ProcessBuilder(command);
		if(bundlePath != null) {
			pb.directory(bundlePath.getLocation().toFile());
		}
		pb.redirectErrorStream(redirectStdErr);
		return new ExternalProcessHelper(pb);
	}
	
	public static void dubAddPath(Location packageRootDir) throws CommonException {
		String packageRootDirStr = packageRootDir.toString();
		System.out.println(":::: Adding DUB package root path: " + packageRootDirStr);
		modifyLocalPackages(packageRootDirStr, true);
	}

	public static void dubRemovePath(Location packageRootDir) throws CommonException {
		String packageRootDirStr = packageRootDir.toString();
		System.out.println(":::: Removing DUB package root path: " + packageRootDirStr);
		modifyLocalPackages(packageRootDirStr, false);
	}

	// DUB 1.40+ replaces local-packages.json on each `dub add-path` call instead of appending.
	// We directly manipulate the JSON file to properly support multiple registered paths.
	@SuppressWarnings("deprecation")
	private static void modifyLocalPackages(String path, boolean add) throws CommonException {
		Path localPackagesFile = Paths.get(System.getProperty("user.home"), ".dub", "packages", "local-packages.json");
		JsonArray array = new JsonArray();
		if(Files.exists(localPackagesFile)) {
			try {
				String content = new String(Files.readAllBytes(localPackagesFile), StandardCharsets.UTF_8).trim();
				if(!content.isEmpty()) {
					array = new JsonParser().parse(content).getAsJsonArray();
				}
			} catch(IOException e) {
				// start with empty array if file is unreadable
			}
		}
		if(add) {
			boolean found = false;
			for(JsonElement elem : array) {
				if(elem.isJsonObject() && path.equals(elem.getAsJsonObject().get("path").getAsString())) {
					found = true;
					break;
				}
			}
			if(!found) {
				JsonObject entry = new JsonObject();
				entry.addProperty("name", "*");
				entry.addProperty("path", path);
				array.add(entry);
			}
		} else {
			JsonArray filtered = new JsonArray();
			for(JsonElement elem : array) {
				if(!elem.isJsonObject() || !path.equals(elem.getAsJsonObject().get("path").getAsString())) {
					filtered.add(elem);
				}
			}
			array = filtered;
		}
		try {
			Files.createDirectories(localPackagesFile.getParent());
			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			Files.write(localPackagesFile, gson.toJson(array).getBytes(StandardCharsets.UTF_8));
		} catch(IOException e) {
			throw new CommonException("Failed to write local-packages.json: " + e.getMessage());
		}
	}
	
	public static void runDubCommand(int timeout, String... arguments) throws CommonException {
		try {
			ExternalProcessHelper processHelper = doRunDubCommand(timeout, arguments);
			assertTrue(processHelper.getProcess().exitValue() == 0);
			if(processHelper.getProcess().exitValue() != 0) {
				throw new CommonException("Exit value not zero");
			}
		} catch (TimeoutException | InterruptedException | OperationCancellation | IOException | CommonException e) {
			throw new CommonException("Failed to run DUB command: " + StringUtil.collToString(arguments, " "));
		}
	}
	
	public static ExternalProcessHelper doRunDubCommand(int timeout, String... arguments)
			throws IOException, InterruptedException, TimeoutException, OperationCancellation {
		ExternalProcessHelper processHelper = startDubProcess(null, true, arguments);
		ExternalProcessResult result = processHelper.awaitTerminationAndResult(timeout, true);
		System.out.println(result.getStdOutBytes().toString(StringUtil.UTF8));
		System.err.println(result.getStdErrBytes().toString(StringUtil.UTF8));
		return processHelper;
	}
	
	public static void runDubList() {
		System.out.println(":::: -------- `dub list`");
		try {
			CommonDubTest.doRunDubCommand(3000, "list");
		} catch(IOException | InterruptedException | OperationCancellation e) {
			throw melnorme.utilbox.core.ExceptionAdapter.unchecked(e);
		} catch(TimeoutException e) {
			assertFail(e.toString());
		}
	}
	
}