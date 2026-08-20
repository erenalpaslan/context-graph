/**
 * Derives ground truth for set-valued benchmark questions: every call site of a function,
 * resolved by the TypeScript compiler rather than by reading files.
 *
 * Why mechanically. The existing gold set was written by an agent that opened files and cited
 * `path:line` for each fact, which guaranteed that every fact was reachable by opening one known
 * file -- so a baseline with Read and Grep already scores 0.90 on it and no graph tool, however
 * good, has room to show a gain. Ground truth produced by reading cannot ask a question that
 * reading does not answer.
 *
 * The compiler's own resolver is the right source precisely where grep is wrong: it follows
 * aliased imports and re-exports, distinguishes same-named symbols in different scopes, and never
 * matches a name inside a comment or a string. Those are the cases a call graph exists to get
 * right, so a benchmark built on them can finally discriminate.
 *
 * Usage:
 *   node extract-callsites.mjs <projectRoot> <tsconfigRelPath> [minRefs] [maxRefs]
 *
 * Emits JSON on stdout: candidates sorted by reference count, each with the declaration site and
 * the full set of call sites as `path:line` (paths relative to projectRoot, lines 1-based).
 */
import path from "node:path";
import { createRequire } from "node:module";

/**
 * `typescript` is installed repo-locally (`.benchmark-tools/`) rather than globally, and ESM
 * resolution ignores NODE_PATH -- so it is resolved explicitly from there, relative to this file,
 * instead of from whatever directory the script happens to be invoked in (always the corpus repo,
 * which has its own unrelated node_modules).
 */
const requireFrom = createRequire(new URL("../../../.benchmark-tools/package.json", import.meta.url));
const ts = requireFrom("typescript");

const [, , projectRootArg, tsconfigRel = "tsconfig.json", minRefsArg = "6", maxRefsArg = "60"] =
  process.argv;

if (!projectRootArg) {
  console.error("usage: extract-callsites.mjs <projectRoot> [tsconfigRelPath] [minRefs] [maxRefs]");
  process.exit(2);
}

const projectRoot = path.resolve(projectRootArg);
const minRefs = Number(minRefsArg);
const maxRefs = Number(maxRefsArg);
const configPath = path.join(projectRoot, tsconfigRel);

const configFile = ts.readConfigFile(configPath, ts.sys.readFile);
if (configFile.error) {
  console.error("cannot read tsconfig:", ts.flattenDiagnosticMessageText(configFile.error.messageText, "\n"));
  process.exit(1);
}
const parsed = ts.parseJsonConfigFileContent(configFile.config, ts.sys, path.dirname(configPath));

// noEmit / skipLibCheck keep this to a type-resolution pass; we never produce output files.
const options = { ...parsed.options, noEmit: true, skipLibCheck: true };
const rootNames = parsed.fileNames;

const program = ts.createProgram({ rootNames, options });

/**
 * A language service over the same file set. `findReferences` lives here rather than on Program,
 * and it is the same call an editor's "find all references" makes -- which is the point: the
 * ground truth should be what the language itself says, not what a text search guesses.
 */
const servicesHost = {
  getScriptFileNames: () => rootNames,
  getScriptVersion: () => "1",
  getScriptSnapshot: (fileName) => {
    const text = ts.sys.readFile(fileName);
    return text === undefined ? undefined : ts.ScriptSnapshot.fromString(text);
  },
  getCurrentDirectory: () => projectRoot,
  getCompilationSettings: () => options,
  getDefaultLibFileName: (o) => ts.getDefaultLibFilePath(o),
  fileExists: ts.sys.fileExists,
  readFile: ts.sys.readFile,
  readDirectory: ts.sys.readDirectory,
  directoryExists: ts.sys.directoryExists,
  getDirectories: ts.sys.getDirectories,
};
const service = ts.createLanguageService(servicesHost, ts.createDocumentRegistry());

const rel = (abs) => path.relative(projectRoot, abs).split(path.sep).join("/");
const lineOf = (sourceFile, pos) => sourceFile.getLineAndCharacterOfPosition(pos).line + 1;

const isSourceOfInterest = (fileName) =>
  !fileName.includes("node_modules") &&
  !/\.d\.ts$/.test(fileName) &&
  !/(^|\/)(tests?|__tests__|__snapshots__)\//.test(fileName) &&
  !/\.(test|spec)\.[cm]?[jt]sx?$/.test(fileName);

const candidates = [];

for (const sourceFile of program.getSourceFiles()) {
  if (!isSourceOfInterest(sourceFile.fileName)) continue;
  if (!sourceFile.fileName.startsWith(projectRoot)) continue;

  ts.forEachChild(sourceFile, (node) => {
    // Only exported, named function declarations: an unexported local cannot be called from
    // elsewhere, so its call-site set is trivially answerable by reading one file -- exactly the
    // shape of question the existing gold set is already saturated on.
    if (!ts.isFunctionDeclaration(node) || !node.name) return;
    const isExported = node.modifiers?.some((m) => m.kind === ts.SyntaxKind.ExportKeyword);
    if (!isExported) return;

    const name = node.name.text;
    let refs;
    try {
      refs = service.getReferencesAtPosition(sourceFile.fileName, node.name.getStart(sourceFile));
    } catch {
      return;
    }
    if (!refs) return;

    const callSites = new Map();
    for (const r of refs) {
      if (r.isDefinition) continue;
      if (!isSourceOfInterest(r.fileName)) continue;
      const target = program.getSourceFile(r.fileName);
      if (!target) continue;
      const key = `${rel(r.fileName)}:${lineOf(target, r.textSpan.start)}`;
      callSites.set(key, true);
    }

    if (callSites.size < minRefs || callSites.size > maxRefs) return;

    candidates.push({
      symbol: name,
      declaration: `${rel(sourceFile.fileName)}:${lineOf(sourceFile, node.name.getStart(sourceFile))}`,
      referenceCount: callSites.size,
      references: [...callSites.keys()].sort(),
    });
  });
}

candidates.sort((a, b) => b.referenceCount - a.referenceCount);
process.stdout.write(JSON.stringify({ projectRoot: rel(projectRoot) || ".", candidates }, null, 2));
