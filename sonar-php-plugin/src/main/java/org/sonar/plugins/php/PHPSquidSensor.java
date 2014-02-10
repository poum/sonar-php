/*
 * SonarQube PHP Plugin
 * Copyright (C) 2010 Codehaus Sonar Plugins
 * dev@sonar.codehaus.org
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
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.php;

import com.google.common.collect.Lists;
import com.sonar.sslr.api.Grammar;
import com.sonar.sslr.squid.AstScanner;
import com.sonar.sslr.squid.SquidAstVisitor;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.checks.AnnotationCheckFactory;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.PersistenceMode;
import org.sonar.api.measures.RangeDistributionBuilder;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.resources.InputFileUtils;
import org.sonar.api.resources.Project;
import org.sonar.api.rules.Violation;
import org.sonar.php.PHPAstScanner;
import org.sonar.php.PHPConfiguration;
import org.sonar.php.api.PHPMetric;
import org.sonar.php.checks.CheckList;
import org.sonar.plugins.php.api.Php;
import org.sonar.squid.api.CheckMessage;
import org.sonar.squid.api.SourceCode;
import org.sonar.squid.api.SourceFile;
import org.sonar.squid.api.SourceFunction;
import org.sonar.squid.indexer.QueryByParent;
import org.sonar.squid.indexer.QueryByType;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class PHPSquidSensor implements Sensor {

  private static final Number[] FUNCTIONS_DISTRIB_BOTTOM_LIMITS = {1, 2, 4, 6, 8, 10, 12};
  private static final Number[] FILES_DISTRIB_BOTTOM_LIMITS = {0, 5, 10, 20, 30, 60, 90};

  private Project project;
  private SensorContext context;
  private AstScanner<Grammar> scanner;
  private RulesProfile profile;
  private AnnotationCheckFactory annotationCheckFactory;

  public PHPSquidSensor(RulesProfile profile) {
    this.profile = profile;
    this.annotationCheckFactory = AnnotationCheckFactory.create(profile, CheckList.REPOSITORY_KEY, CheckList.getChecks());
  }

  @Override
  public boolean shouldExecuteOnProject(Project project) {
    return Php.KEY.equals(project.getLanguageKey());
  }

  @Override
  public void analyse(Project project, SensorContext context) {
    this.project = project;
    this.context = context;

    List<SquidAstVisitor<Grammar>> visitors = getCheckVisitors();
    this.scanner = PHPAstScanner.create(createConfiguration(), visitors.toArray(new SquidAstVisitor[visitors.size()]));
    scanner.scanFiles(getProjectMainFiles());

    save(scanner.getIndex().search(new QueryByType(SourceFile.class)));
  }

  private void save(Collection<SourceCode> squidSourceFiles) {
    for (SourceCode squidSourceFile : squidSourceFiles) {
      SourceFile squidFile = (SourceFile) squidSourceFile;
      org.sonar.api.resources.File sonarFile = org.sonar.api.resources.File.fromIOFile(new java.io.File(squidFile.getKey()), project);

      saveFilesComplexityDistribution(sonarFile, squidFile);
      saveFunctionsComplexityDistribution(sonarFile, squidFile);
      saveFileMeasures(sonarFile, squidFile);
      saveViolations(sonarFile, squidFile);
    }
  }

  private void saveFileMeasures(org.sonar.api.resources.File sonarFile, SourceFile squidFile) {
    context.saveMeasure(sonarFile, CoreMetrics.FILES, squidFile.getDouble(PHPMetric.FILES));
    context.saveMeasure(sonarFile, CoreMetrics.LINES, squidFile.getDouble(PHPMetric.LINES));
    context.saveMeasure(sonarFile, CoreMetrics.NCLOC, squidFile.getDouble(PHPMetric.LINES_OF_CODE));
    context.saveMeasure(sonarFile, CoreMetrics.COMMENT_LINES, squidFile.getDouble(PHPMetric.COMMENT_LINES));
    context.saveMeasure(sonarFile, CoreMetrics.CLASSES, squidFile.getDouble(PHPMetric.CLASSES));
    context.saveMeasure(sonarFile, CoreMetrics.FUNCTIONS, squidFile.getDouble(PHPMetric.FUNCTIONS));
    context.saveMeasure(sonarFile, CoreMetrics.STATEMENTS, squidFile.getDouble(PHPMetric.STATEMENTS));
    context.saveMeasure(sonarFile, CoreMetrics.COMPLEXITY, squidFile.getDouble(PHPMetric.COMPLEXITY));
  }

  private void saveFunctionsComplexityDistribution(org.sonar.api.resources.File sonarFile, SourceFile squidFile) {
    Collection<SourceCode> squidFunctionsInFile = scanner.getIndex().search(new QueryByParent(squidFile), new QueryByType(SourceFunction.class));
    RangeDistributionBuilder complexityDistribution = new RangeDistributionBuilder(CoreMetrics.FUNCTION_COMPLEXITY_DISTRIBUTION, FUNCTIONS_DISTRIB_BOTTOM_LIMITS);
    for (SourceCode squidFunction : squidFunctionsInFile) {
      complexityDistribution.add(squidFunction.getDouble(PHPMetric.COMPLEXITY));
    }
    context.saveMeasure(sonarFile, complexityDistribution.build().setPersistenceMode(PersistenceMode.MEMORY));
  }

  private void saveFilesComplexityDistribution(org.sonar.api.resources.File sonarFile, SourceFile squidFile) {
    RangeDistributionBuilder complexityDistribution = new RangeDistributionBuilder(CoreMetrics.FILE_COMPLEXITY_DISTRIBUTION, FILES_DISTRIB_BOTTOM_LIMITS);
    complexityDistribution.add(squidFile.getDouble(PHPMetric.COMPLEXITY));
    context.saveMeasure(sonarFile, complexityDistribution.build().setPersistenceMode(PersistenceMode.MEMORY));
  }

  private void saveViolations(org.sonar.api.resources.File sonarFile, SourceFile squidFile) {
    Collection<CheckMessage> messages = squidFile.getCheckMessages();
    if (messages != null) {
      for (CheckMessage message : messages) {
        Violation violation = Violation.create(annotationCheckFactory.getActiveRule(message.getCheck()), sonarFile)
          .setLineId(message.getLine())
          .setMessage(message.getText(Locale.ENGLISH));
        context.saveViolation(violation);
      }
    }
  }

  private PHPConfiguration createConfiguration() {
    return new PHPConfiguration(project.getFileSystem().getSourceCharset());
  }

  private Collection<File> getProjectMainFiles() {
    return InputFileUtils.toFiles(project.getFileSystem().mainFiles(Php.KEY));
  }

  private List<SquidAstVisitor<Grammar>> getCheckVisitors() {
    return Lists.newArrayList(annotationCheckFactory.getChecks());
  }
}