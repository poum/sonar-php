/*
 * SonarQube PHP Plugin
 * Copyright (C) 2010-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.php;

import com.google.common.collect.ImmutableList;
import com.sonar.sslr.api.typed.ActionParser;
import org.sonar.api.measures.FileLinesContext;
import org.sonar.php.api.CharsetAwareVisitor;
import org.sonar.php.highlighter.SyntaxHighlighterVisitor;
import org.sonar.php.highlighter.SymbolHighlighter;
import org.sonar.php.highlighter.SymbolHighlightingData;
import org.sonar.php.highlighter.SyntaxHighlightingData;
import org.sonar.php.highlighter.SourceFileOffsets;
import org.sonar.php.metrics.FileMeasures;
import org.sonar.php.metrics.MetricsVisitor;
import org.sonar.php.parser.PHPParserBuilder;
import org.sonar.php.tree.symbols.SymbolTableImpl;
import org.sonar.plugins.php.api.symbols.SymbolTable;
import org.sonar.plugins.php.api.tree.CompilationUnitTree;
import org.sonar.plugins.php.api.tree.Tree;
import org.sonar.plugins.php.api.visitors.Issue;
import org.sonar.plugins.php.api.visitors.PHPCheck;

import java.io.File;
import java.nio.charset.Charset;
import java.util.List;

public class PHPAnalyzer {

  private final ActionParser<Tree> parser;
  private final ImmutableList<PHPCheck> checks;
  private final Charset charset;

  private CompilationUnitTree currentFileTree;
  private File currentFile;
  private SymbolTable currentFileSymbolTable;
  private SourceFileOffsets currentFileOffsets;

  public PHPAnalyzer(Charset charset, ImmutableList<PHPCheck> checks) {
    this.parser = PHPParserBuilder.createParser(charset);
    this.checks = checks;
    this.charset = charset;

    for (PHPCheck check : checks) {
      if (check instanceof CharsetAwareVisitor) {
        ((CharsetAwareVisitor) check).setCharset(charset);
      }
      check.init();
    }
  }

  public void nextFile(File file) {
    currentFile = file;
    currentFileTree = (CompilationUnitTree) parser.parse(file);
    currentFileSymbolTable = SymbolTableImpl.create(currentFileTree);
    currentFileOffsets = new SourceFileOffsets(currentFile, charset);
  }

  public List<Issue> analyze() {
    ImmutableList.Builder<Issue> issuesBuilder = ImmutableList.builder();
    for (PHPCheck check : checks) {
      issuesBuilder.addAll(check.analyze(currentFile, currentFileTree, currentFileSymbolTable));
    }

    return issuesBuilder.build();
  }

  public FileMeasures computeMeasures(FileLinesContext fileLinesContext) {
    return new MetricsVisitor().getFileMeasures(currentFile, currentFileTree, fileLinesContext);
  }

  public List<SyntaxHighlightingData> getSyntaxHighlighting() {
    return SyntaxHighlighterVisitor.getHighlightData(currentFileTree, currentFileOffsets);
  }

  public List<SymbolHighlightingData> getSymbolHighlighting() {
    return SymbolHighlighter.getHighlightData(currentFileSymbolTable, currentFileOffsets);
  }
}
