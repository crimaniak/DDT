/*******************************************************************************
 * Copyright (c) 2024 Bruno Medeiros and other Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Bruno Medeiros - initial API and implementation
 *******************************************************************************/
package dtool.dub;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dtool.dub.DubBundle.DubBundleException;
import dtool.dub.DubBundle.DubConfiguration;
import melnorme.lang.tooling.BundlePath;
import melnorme.lang.tooling.bundle.DependencyRef;
import melnorme.utilbox.collections.ArrayList2;
import melnorme.utilbox.misc.ArrayUtil;
import melnorme.utilbox.misc.FileUtil;
import melnorme.utilbox.misc.Location;
import melnorme.utilbox.misc.StringUtil;

/**
 * Parser for dub.sdl manifest files (Simple Declarative Language subset used by DUB).
 * Reuses DubManifestParser's field set and createBundle() so downstream code is unchanged.
 */
public class DubSdlParser extends DubManifestParser {

	public static DubBundle parseDubBundleFromSdl(BundlePath bundlePath, Location manifestLocation) {
		try {
			String source = FileUtil.readStringFromFile(manifestLocation.toFile(), StringUtil.UTF8);
			return new DubSdlParser().parseSdlSource(bundlePath, source);
		} catch(IOException e) {
			return new DubBundle(bundlePath, "<undefined>", new DubBundleException(e));
		}
	}

	protected DubBundle parseSdlSource(BundlePath bundlePath, String source) {
		try {
			List<SdlStatement> statements = tokenize(source);
			applyStatements(statements);
		} catch(DubBundleException e) {
			dubError = e;
		}
		return createBundle(bundlePath, true);
	}

	// ---- statement application ----

	protected void applyStatements(List<SdlStatement> statements) {
		ArrayList<DependencyRef> deps = new ArrayList<>();
		ArrayList2<DubConfiguration> configs = new ArrayList2<>();
		ArrayList<String> srcPaths = null;

		for(SdlStatement stmt : statements) {
			switch(stmt.tag) {
			case "name":
				if(!stmt.values.isEmpty()) bundleName = stmt.values.get(0);
				break;
			case "version":
				if(!stmt.values.isEmpty()) version = stmt.values.get(0);
				break;
			case "targetName":
				if(!stmt.values.isEmpty()) targetName = stmt.values.get(0);
				break;
			case "targetPath":
				if(!stmt.values.isEmpty()) targetPath = stmt.values.get(0);
				break;
			case "sourcePaths":
				if(!stmt.values.isEmpty()) {
					srcPaths = new ArrayList<>(stmt.values);
				}
				break;
			case "importPaths":
				// only use importPaths as fallback when sourcePaths not set
				if(srcPaths == null && !stmt.values.isEmpty()) {
					srcPaths = new ArrayList<>(stmt.values);
				}
				break;
			case "dependency":
				if(!stmt.values.isEmpty()) {
					String depName = stmt.values.get(0);
					String depVersion = stmt.attributes.get("version");
					deps.add(new DependencyRef(depName, depVersion));
				}
				break;
			case "configuration":
				if(!stmt.values.isEmpty()) {
					DubConfiguration cfg = parseConfiguration(stmt);
					if(cfg != null) configs.add(cfg);
				}
				break;
			default:
				break;
			}
		}

		if(srcPaths != null) {
			sourceFolders = ArrayUtil.createFrom(srcPaths, String.class);
		}
		if(!deps.isEmpty()) {
			dependencies = ArrayUtil.createFrom(deps, DependencyRef.class);
		}
		if(!configs.isEmpty()) {
			configurations = configs;
		}
	}

	protected DubConfiguration parseConfiguration(SdlStatement stmt) {
		String cfgName = stmt.values.get(0);
		String cfgTargetType = null;
		String cfgTargetName = null;
		String cfgTargetPath = null;

		for(SdlStatement child : stmt.children) {
			switch(child.tag) {
			case "targetType":
				if(!child.values.isEmpty()) cfgTargetType = child.values.get(0);
				break;
			case "targetName":
				if(!child.values.isEmpty()) cfgTargetName = child.values.get(0);
				break;
			case "targetPath":
				if(!child.values.isEmpty()) cfgTargetPath = child.values.get(0);
				break;
			default:
				break;
			}
		}
		return new DubConfiguration(cfgName, cfgTargetType, cfgTargetName, cfgTargetPath);
	}

	// ---- SDL tokenizer ----

	protected static class SdlStatement {
		String tag;
		List<String> values = new ArrayList<>();
		Map<String, String> attributes = new LinkedHashMap<>();
		List<SdlStatement> children = new ArrayList<>();
	}

	protected List<SdlStatement> tokenize(String source) throws DubBundleException {
		source = stripBlockComments(source);
		List<String> logicalLines = buildLogicalLines(source);
		int[] cursor = {0};
		return parseBlock(logicalLines, cursor);
	}

	protected String stripBlockComments(String source) {
		StringBuilder sb = new StringBuilder(source.length());
		int i = 0;
		while(i < source.length()) {
			if(i + 1 < source.length() && source.charAt(i) == '/' && source.charAt(i + 1) == '*') {
				i += 2;
				while(i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
					if(source.charAt(i) == '\n') sb.append('\n');
					i++;
				}
				i += 2;
			} else {
				sb.append(source.charAt(i));
				i++;
			}
		}
		return sb.toString();
	}

	protected List<String> buildLogicalLines(String source) {
		String[] rawLines = source.split("\n", -1);
		List<String> result = new ArrayList<>();
		StringBuilder buf = new StringBuilder();

		for(String raw : rawLines) {
			// strip // line comment (outside quoted strings)
			int commentAt = lineCommentIndex(raw);
			String line = (commentAt >= 0) ? raw.substring(0, commentAt) : raw;

			// right-trim
			int end = line.length();
			while(end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
			line = line.substring(0, end);

			if(line.endsWith("\\")) {
				buf.append(line, 0, line.length() - 1).append(' ');
			} else {
				buf.append(line);
				String logical = buf.toString().trim();
				if(!logical.isEmpty()) result.add(logical);
				buf = new StringBuilder();
			}
		}
		if(buf.length() > 0) {
			String logical = buf.toString().trim();
			if(!logical.isEmpty()) result.add(logical);
		}
		return result;
	}

	protected int lineCommentIndex(String line) {
		boolean inString = false;
		for(int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if(c == '"') {
				inString = !inString;
			} else if(!inString && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
				return i;
			}
		}
		return -1;
	}

	protected List<SdlStatement> parseBlock(List<String> lines, int[] cursor) {
		List<SdlStatement> result = new ArrayList<>();
		while(cursor[0] < lines.size()) {
			String line = lines.get(cursor[0]);
			if(line.equals("}")) {
				cursor[0]++;
				return result;
			}
			cursor[0]++;

			boolean opensBlock = line.endsWith("{");
			String stmtLine = opensBlock ? line.substring(0, line.lastIndexOf('{')).trim() : line;

			SdlStatement stmt = parseSingleStatement(stmtLine);
			if(stmt == null) continue;

			if(opensBlock) {
				stmt.children.addAll(parseBlock(lines, cursor));
			}
			result.add(stmt);
		}
		return result;
	}

	protected SdlStatement parseSingleStatement(String line) {
		if(line.isEmpty()) return null;

		SdlStatement stmt = new SdlStatement();
		int i = 0;
		int len = line.length();

		// read tag
		int tagStart = i;
		while(i < len && !Character.isWhitespace(line.charAt(i))) i++;
		stmt.tag = line.substring(tagStart, i);
		if(stmt.tag.isEmpty()) return null;

		// skip whitespace
		while(i < len && Character.isWhitespace(line.charAt(i))) i++;

		// parse values and attributes
		while(i < len) {
			char c = line.charAt(i);
			if(c == '"') {
				// quoted string value
				i++;
				StringBuilder val = new StringBuilder();
				while(i < len && line.charAt(i) != '"') {
					if(line.charAt(i) == '\\' && i + 1 < len) {
						i++;
						val.append(line.charAt(i));
					} else {
						val.append(line.charAt(i));
					}
					i++;
				}
				if(i < len) i++; // closing "
				stmt.values.add(val.toString());
			} else if(Character.isLetter(c) || c == '_') {
				// attribute: key="value"
				int keyStart = i;
				while(i < len && line.charAt(i) != '=' && !Character.isWhitespace(line.charAt(i))) i++;
				String key = line.substring(keyStart, i);
				if(i < len && line.charAt(i) == '=') {
					i++; // skip =
					if(i < len && line.charAt(i) == '"') {
						i++; // opening "
						StringBuilder val = new StringBuilder();
						while(i < len && line.charAt(i) != '"') {
							if(line.charAt(i) == '\\' && i + 1 < len) {
								i++;
								val.append(line.charAt(i));
							} else {
								val.append(line.charAt(i));
							}
							i++;
						}
						if(i < len) i++; // closing "
						stmt.attributes.put(key, val.toString());
					}
				}
			} else {
				i++; // skip unknown
			}
		}
		return stmt;
	}

}
