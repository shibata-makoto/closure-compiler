/*
 * Copyright 2011 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkState;

import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Finds all references to global symbols in a different output chunk and add ES Module imports and
 * exports for them.
 *
 * <pre><code>
 * // chunk1.js
 * var a = 1;
 * function b() { return a }
 * </code></pre>
 *
 * <pre><code>
 * // chunk2.js
 * console.log(a);
 * </code></pre>
 *
 * becomes
 *
 * <pre><code>
 * // chunk1.js
 * var a = 1;
 * var b = function b() { return a };
 * export {a};
 * </code></pre>
 *
 * <pre><code>
 * // chunk2.js
 * import {a} from './chunk1.js';
 * console.log(a);
 * </code></pre>
 *
 * This allows splitting code into es modules that depend on each other's symbols, without using a
 * global namespace or polluting the global scope.
 */
final class ConvertChunksToESModules implements CompilerPass {
  private final AbstractCompiler compiler;
  private final Map<JSModule, Set<String>> crossChunkExports = new LinkedHashMap<>();
  private final Map<JSModule, Map<JSModule, Set<String>>> crossChunkImports = new LinkedHashMap<>();

  static final DiagnosticType ASSIGNMENT_TO_IMPORT =
      DiagnosticType.error(
          "JSC_IMPORT_ASSIGN", "Imported symbol \"{0}\" in chunk \"{1}\" cannot be assigned");

  static final DiagnosticType UNABLE_TO_COMPUTE_RELATIVE_PATH =
      DiagnosticType.error(
          "JSC_UNABLE_TO_COMPUTE_RELATIVE_PATH",
          "Unable to compute relative import path from \"{0}\" to \"{1}\"");

  /**
   * Constructor for the ConvertChunksToESModules compiler pass.
   *
   * @param compiler The JSCompiler, for reporting code changes.
   */
  ConvertChunksToESModules(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public void process(Node externs, Node root) {
    // Find global names that are used in more than one chunk. Those that
    // are have to have import and export statements added.
    NodeTraversal.traverse(compiler, root, new FindCrossChunkReferences());

    // Force every output chunk to parse as an ES Module. If a chunk has no imports and
    // no exports, add an empty export list to generate an empty export statement:
    // example: export {};
    for (JSModule chunk : compiler.getModuleGraph().getAllModules()) {
      if (!crossChunkExports.containsKey(chunk)
          && !crossChunkImports.containsKey(chunk)
          && !chunk.getInputs().isEmpty()) {
        crossChunkExports.put(chunk, new LinkedHashSet<>());
      }
    }

    convertChunkSourcesToModules();
    addExportStatements();
    addImportStatements();
  }

  /**
   * Move all code in a chunk into the first input and mark it as an ESModule. At this point in the
   * compilation, all input files should be scripts.
   */
  private void convertChunkSourcesToModules() {
    for (JSModule chunk : compiler.getModuleGraph().getAllModules()) {
      if (chunk.getInputs().isEmpty()) {
        continue;
      }

      CompilerInput firstInput = null;
      for (CompilerInput input : chunk.getInputs()) {
        Node astRoot = input.getAstRoot(compiler);
        FeatureSet scriptFeatures = NodeUtil.getFeatureSetOfScript(astRoot);
        checkState(!scriptFeatures.contains(FeatureSet.ES6_MODULES));
        if (firstInput == null) {
          firstInput = input;
          scriptFeatures = scriptFeatures.union(FeatureSet.ES6_MODULES);
          astRoot.putProp(Node.FEATURE_SET, scriptFeatures);
          Node moduleBody = new Node(Token.MODULE_BODY);
          moduleBody.useSourceInfoFrom(astRoot);
          moduleBody.addChildrenToFront(astRoot.removeChildren());
          astRoot.addChildToFront(moduleBody);
          compiler.reportChangeToEnclosingScope(moduleBody);
        } else {
          Node firstInputAstRoot = firstInput.getAstRoot(compiler);
          FeatureSet firstInputScriptFeatures = NodeUtil.getFeatureSetOfScript(firstInputAstRoot);
          FeatureSet combinedFeatureSet =
              firstInputScriptFeatures.union(NodeUtil.getFeatureSetOfScript(astRoot));
          astRoot.putProp(Node.FEATURE_SET, combinedFeatureSet);
          Node moduleBody = firstInputAstRoot.getFirstChild();
          checkState(moduleBody != null && moduleBody.isModuleBody());
          moduleBody.addChildrenToBack(astRoot.removeChildren());
          compiler.reportChangeToEnclosingScope(firstInputAstRoot);
          compiler.reportChangeToChangeScope(astRoot);
        }
      }
    }
  }

  /** Add export statements to chunks */
  private void addExportStatements() {
    for (Map.Entry<JSModule, Set<String>> jsModuleExports : crossChunkExports.entrySet()) {
      CompilerInput firstInput = jsModuleExports.getKey().getInput(0);
      Node moduleBody = firstInput.getAstRoot(compiler).getFirstChild();
      checkState(moduleBody != null && moduleBody.isModuleBody());
      Node exportSpecs = new Node(Token.EXPORT_SPECS);
      for (String name : jsModuleExports.getValue()) {
        Node exportSpec = new Node(Token.EXPORT_SPEC);
        exportSpec.addChildToFront(IR.name(name));
        exportSpec.addChildToFront(IR.name(name));
        exportSpec.putIntProp(Node.IS_SHORTHAND_PROPERTY, 1);
        exportSpecs.addChildToBack(exportSpec);
      }
      Node export = IR.export(exportSpecs).useSourceInfoFromForTree(moduleBody);
      moduleBody.addChildToBack(export);
      compiler.reportChangeToEnclosingScope(moduleBody);
    }
  }

  private static String getChunkName(JSModule chunk) {
    return chunk.getName() + ".js";
  }

  /** Add import statements to chunks */
  private void addImportStatements() {
    for (Map.Entry<JSModule, Map<JSModule, Set<String>>> chunkImportsEntry :
        crossChunkImports.entrySet()) {
      ArrayList<Node> importStatements = new ArrayList<>();
      JSModule importingChunk = chunkImportsEntry.getKey();
      CompilerInput firstInput = importingChunk.getInput(0);
      Node moduleBody = firstInput.getAstRoot(compiler).getFirstChild();
      checkState(moduleBody != null && moduleBody.isModuleBody());

      // For each distinct chunk where a referenced symbol is defined, create an import statement
      // referencing the names.
      for (Map.Entry<JSModule, Set<String>> importsByChunk :
          chunkImportsEntry.getValue().entrySet()) {
        Node importSpecs = new Node(Token.IMPORT_SPECS);
        for (String name : importsByChunk.getValue()) {
          Node importSpec = new Node(Token.IMPORT_SPEC);
          importSpec.addChildToFront(IR.name(name));
          importSpec.addChildToFront(IR.name(name));
          importSpec.putIntProp(Node.IS_SHORTHAND_PROPERTY, 1);
          importSpecs.addChildToBack(importSpec);
        }
        Node importStatement = new Node(Token.IMPORT);
        JSModule exportingChunk = importsByChunk.getKey();
        String importPath = getChunkName(exportingChunk);
        try {
          importPath =
              this.relativePath(getChunkName(importingChunk), getChunkName(exportingChunk));
        } catch (IllegalArgumentException e) {
          compiler.report(
              JSError.make(
                  moduleBody,
                  UNABLE_TO_COMPUTE_RELATIVE_PATH,
                  getChunkName(importingChunk),
                  getChunkName(exportingChunk)));
        }
        importStatement.addChildToFront(IR.string(importPath));
        importStatement.addChildToFront(importSpecs);
        importStatement.addChildToFront(IR.empty());
        importStatement.useSourceInfoFromForTree(moduleBody);
        importStatements.add(0, importStatement);
      }
      for (Node importStatement : importStatements) {
        moduleBody.addChildToFront(importStatement);
      }
      compiler.reportChangeToEnclosingScope(moduleBody);
    }
  }

  /** Find names in a module that are defined in a different module. */
  private class FindCrossChunkReferences extends AbstractPostOrderCallback {
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isName()) {
        String name = n.getString();
        if ("".equals(name)) {
          return;
        }
        Scope s = t.getScope();
        Var v = s.getVar(name);
        if (v == null || !v.isGlobal()) {
          return;
        }
        CompilerInput input = v.getInput();
        if (input == null) {
          return;
        }
        // Compare the chunk where the variable is declared to the current
        // chunk. If they are different, the variable is used across modules.
        JSModule definingChunk = input.getModule();
        JSModule referencingChunk = t.getModule();
        if (definingChunk != referencingChunk) {
          if (NodeUtil.isLhsOfAssign(n)) {
            t.report(n, ASSIGNMENT_TO_IMPORT, n.getString(), getChunkName(referencingChunk));
          }

          // Mark the chunk where the name is declared as needing an export for this name
          Set<String> namesToExport =
              crossChunkExports.computeIfAbsent(
                  definingChunk, (JSModule k) -> new LinkedHashSet<>());
          namesToExport.add(name);

          // Add an import for this name to this module from the source module
          Map<JSModule, Set<String>> namesToImportByModule =
              crossChunkImports.computeIfAbsent(
                  referencingChunk, (JSModule k) -> new LinkedHashMap<>());
          Set<String> importsForModule =
              namesToImportByModule.computeIfAbsent(
                  definingChunk, (JSModule k) -> new LinkedHashSet<>());
          importsForModule.add(name);
        }
      }
    }
  }

  /**
   * Calculate the relative path between two URI paths. To remain compliant with ES Module loading
   * restrictions, paths must always begin with a "./", "../" or "/" or they are otherwise treated
   * as a bare module specifier.
   *
   * <p>TODO(ChadKillingsworth): This method likely has use cases beyond this class and should be
   * moved.
   */
  private static String relativePath(String fromUriPath, String toUriPath) {
    Path fromPath = Paths.get(fromUriPath);
    Path toPath = Paths.get(toUriPath);
    Path fromFolder = fromPath.getParent();

    // if the from URIs are simple names without paths, they are in the same folder
    // example: m0.js
    if (fromFolder == null) {
      return "./" + toUriPath;
    }

    String calculatedPath = fromFolder.relativize(toPath).toString();
    if (calculatedPath.startsWith(".") || calculatedPath.startsWith("/")) {
      return calculatedPath;
    }
    return "./" + calculatedPath;
  }
}
