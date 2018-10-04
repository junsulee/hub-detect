/**
 * hub-detect
 *
 * Copyright (C) 2018 Black Duck Software, Inc.
 * http://www.blackducksoftware.com/
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.blackducksoftware.integration.hub.detect.workflow.project;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.blackducksoftware.integration.hub.detect.DetectInfo;
import com.blackducksoftware.integration.hub.detect.configuration.DetectConfiguration;
import com.blackducksoftware.integration.hub.detect.configuration.DetectProperty;
import com.blackducksoftware.integration.hub.detect.exception.DetectUserFriendlyException;
import com.blackducksoftware.integration.hub.detect.exitcode.ExitCodeType;
import com.blackducksoftware.integration.hub.detect.workflow.codelocation.BdioCodeLocation;
import com.blackducksoftware.integration.hub.detect.workflow.codelocation.CodeLocationNameManager;
import com.blackducksoftware.integration.hub.detect.workflow.codelocation.DetectCodeLocation;
import com.blackducksoftware.integration.hub.detect.workflow.codelocation.DetectCodeLocationManager;
import com.blackducksoftware.integration.hub.detect.workflow.codelocation.DetectCodeLocationResult;
import com.blackducksoftware.integration.hub.detect.workflow.codelocation.FileNameUtils;
import com.synopsys.integration.blackduck.summary.Result;
import com.synopsys.integration.hub.bdio.SimpleBdioFactory;
import com.synopsys.integration.hub.bdio.graph.DependencyGraph;
import com.synopsys.integration.hub.bdio.graph.MutableDependencyGraph;
import com.synopsys.integration.hub.bdio.model.Forge;
import com.synopsys.integration.hub.bdio.model.SimpleBdioDocument;
import com.synopsys.integration.hub.bdio.model.SpdxCreator;
import com.synopsys.integration.hub.bdio.model.dependency.Dependency;
import com.synopsys.integration.hub.bdio.model.externalid.ExternalId;
import com.synopsys.integration.hub.bdio.model.externalid.ExternalIdFactory;
import com.synopsys.integration.util.IntegrationEscapeUtil;

public class BdioManager {
    private final Logger logger = LoggerFactory.getLogger(BdioManager.class);

    private final DetectInfo detectInfo;
    private final SimpleBdioFactory simpleBdioFactory;
    private final IntegrationEscapeUtil integrationEscapeUtil;
    private final CodeLocationNameManager codeLocationNameManager;
    private final DetectConfiguration detectConfiguration;
    private DetectCodeLocationManager codeLocationManager;

    public BdioManager(final DetectInfo detectInfo, final SimpleBdioFactory simpleBdioFactory, final IntegrationEscapeUtil integrationEscapeUtil, final CodeLocationNameManager codeLocationNameManager,
            final DetectConfiguration detectConfiguration) {
        this.detectInfo = detectInfo;
        this.simpleBdioFactory = simpleBdioFactory;
        this.integrationEscapeUtil = integrationEscapeUtil;
        this.codeLocationNameManager = codeLocationNameManager;
        this.detectConfiguration = detectConfiguration;
    }

    public BdioResult createBdioFiles(String aggregateName, String projectName, String projectVersion, List<DetectCodeLocation> codeLocations) throws DetectUserFriendlyException {
        final List<File> bdioFiles = new ArrayList<>();
        if (StringUtils.isBlank(aggregateName)) {
            final DetectCodeLocationResult codeLocationResult = codeLocationManager.process(codeLocations, projectName, projectVersion);
            //codeLocationResult.getFailedBomToolGroupTypes().forEach(it -> bomToolResults.put(it, Result.FAILURE));

            final List<File> createdBdioFiles = createBdioFiles(codeLocationResult.getBdioCodeLocations(), projectName, projectVersion);
            bdioFiles.addAll(createdBdioFiles);

            return new BdioResult(codeLocationResult.getBdioCodeLocations(), codeLocationResult.getFailedBomToolGroupTypes(), codeLocationResult.getCodeLocationNames());
            //reportManager.codeLocationsCompleted(searchResult.getBomToolEvaluations(), codeLocationResult.getCodeLocationNames());
        } else {
            final File aggregateBdioFile = createAggregateBdioFile(codeLocations, projectName, projectVersion);
            bdioFiles.add(aggregateBdioFile);
        }

        return new BdioResult(null, null, null);
    }

    public List<File> createBdioFiles(final List<BdioCodeLocation> bdioCodeLocations, final String projectName, final String projectVersion) throws DetectUserFriendlyException {
        final List<File> bdioFiles = new ArrayList<>();
        for (final BdioCodeLocation bdioCodeLocation : bdioCodeLocations) {
            final SimpleBdioDocument simpleBdioDocument = createSimpleBdioDocument(bdioCodeLocation.codeLocationName, projectName, projectVersion, bdioCodeLocation.codeLocation);

            final File outputFile = new File(detectConfiguration.getProperty(DetectProperty.DETECT_BDIO_OUTPUT_PATH), bdioCodeLocation.bdioName);
            if (outputFile.exists()) {
                final boolean deleteSuccess = outputFile.delete();
                logger.debug(String.format("%s deleted: %b", outputFile.getAbsolutePath(), deleteSuccess));
            }
            writeBdioFile(outputFile, simpleBdioDocument);
            bdioFiles.add(outputFile);
        }

        return bdioFiles;
    }

    private DependencyGraph createAggregateDependencyGraph(final List<DetectCodeLocation> codeLocations) {
        final MutableDependencyGraph aggregateDependencyGraph = simpleBdioFactory.createMutableDependencyGraph();

        for (final DetectCodeLocation detectCodeLocation : codeLocations) {
            final Dependency codeLocationDependency = createAggregateDependency(detectCodeLocation);
            aggregateDependencyGraph.addChildrenToRoot(codeLocationDependency);
            aggregateDependencyGraph.addGraphAsChildrenToParent(codeLocationDependency, detectCodeLocation.getDependencyGraph());
        }

        return aggregateDependencyGraph;
    }

    private Dependency createAggregateDependency(final DetectCodeLocation codeLocation) {
        String name = null;
        String version = null;
        try {
            name = codeLocation.getExternalId().name;
            version = codeLocation.getExternalId().version;
        } catch (final Exception e) {
            logger.warn("Failed to get name or version to use in the wrapper for a code location.", e);
        }
        final ExternalId original = codeLocation.getExternalId();
        final String sourcePath = codeLocation.getSourcePath();
        final String bomToolType = codeLocation.getBomToolGroupType().toString();
        final String relativePath = FileNameUtils.relativize(detectConfiguration.getProperty(DetectProperty.DETECT_SOURCE_PATH), sourcePath);
        final List<String> externalIdPieces = new ArrayList<>();
        externalIdPieces.addAll(Arrays.asList(original.getExternalIdPieces()));
        externalIdPieces.add(relativePath);
        externalIdPieces.add(bomToolType);
        final String[] pieces = externalIdPieces.toArray(new String[externalIdPieces.size()]);
        return new Dependency(name, version, new ExternalIdFactory().createModuleNamesExternalId(original.forge, pieces));
    }

    public File createAggregateBdioFile(final List<DetectCodeLocation> codeLocations, final String projectName, final String projectVersion) throws DetectUserFriendlyException {
        final DependencyGraph aggregateDependencyGraph = createAggregateDependencyGraph(codeLocations);

        final SimpleBdioDocument aggregateBdioDocument = createAggregateSimpleBdioDocument(projectName, projectVersion, aggregateDependencyGraph);
        final String filename = String.format("%s.jsonld", integrationEscapeUtil.escapeForUri(detectConfiguration.getProperty(DetectProperty.DETECT_BOM_AGGREGATE_NAME)));
        final File aggregateBdioFile = new File(detectConfiguration.getProperty(DetectProperty.DETECT_BDIO_OUTPUT_PATH), filename);
        if (aggregateBdioFile.exists()) {
            final boolean deleteSuccess = aggregateBdioFile.delete();
            logger.debug(String.format("%s deleted: %b", aggregateBdioFile.getAbsolutePath(), deleteSuccess));
        }

        writeBdioFile(aggregateBdioFile, aggregateBdioDocument);

        return aggregateBdioFile;
    }

    private void writeBdioFile(final File outputFile, final SimpleBdioDocument simpleBdioDocument) throws DetectUserFriendlyException {
        try {
            simpleBdioFactory.writeSimpleBdioDocumentToFile(outputFile, simpleBdioDocument);
            logger.info(String.format("BDIO Generated: %s", outputFile.getAbsolutePath()));
        } catch (final IOException e) {
            throw new DetectUserFriendlyException(e.getMessage(), e, ExitCodeType.FAILURE_GENERAL_ERROR);
        }
    }

    private SimpleBdioDocument createAggregateSimpleBdioDocument(final String projectName, final String projectVersionName, final DependencyGraph dependencyGraph) {
        final ExternalId projectExternalId = simpleBdioFactory.createNameVersionExternalId(new Forge("/", "/", ""), projectName, projectVersionName);
        final String codeLocationName = codeLocationNameManager.createAggregateCodeLocationName(projectName, projectVersionName);
        return createSimpleBdioDocument(codeLocationName, projectName, projectVersionName, projectExternalId, dependencyGraph);
    }

    private SimpleBdioDocument createSimpleBdioDocument(final String codeLocationName, final String projectName, final String projectVersionName, final DetectCodeLocation detectCodeLocation) {
        final ExternalId projectExternalId = detectCodeLocation.getExternalId();
        final DependencyGraph dependencyGraph = detectCodeLocation.getDependencyGraph();

        return createSimpleBdioDocument(codeLocationName, projectName, projectVersionName, projectExternalId, dependencyGraph);
    }

    private SimpleBdioDocument createSimpleBdioDocument(final String codeLocationName, final String projectName, final String projectVersionName, final ExternalId projectExternalId, final DependencyGraph dependencyGraph) {
        final SimpleBdioDocument simpleBdioDocument = simpleBdioFactory.createSimpleBdioDocument(codeLocationName, projectName, projectVersionName, projectExternalId, dependencyGraph);

        final String hubDetectVersion = detectInfo.getDetectVersion();
        final SpdxCreator hubDetectCreator = SpdxCreator.createToolSpdxCreator("HubDetect", hubDetectVersion);
        simpleBdioDocument.billOfMaterials.creationInfo.setPrimarySpdxCreator(hubDetectCreator);

        return simpleBdioDocument;
    }

}