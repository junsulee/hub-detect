package com.blackducksoftware.integration.hub.detect.extraction.bomtool.sbt;

import org.springframework.stereotype.Component;

import com.blackducksoftware.integration.hub.detect.extraction.strategy.Strategy;
import com.blackducksoftware.integration.hub.detect.extraction.strategy.StrategyProvider;
import com.blackducksoftware.integration.hub.detect.model.BomToolType;

@Component
public class SbtStrategyProvider extends StrategyProvider {

    static final String BUILD_SBT_FILENAME = "build.sbt";

    @SuppressWarnings("rawtypes")
    @Override
    public void init() {

        final Strategy buildSbtStrategy = newStrategyBuilder(SbtResolutionCacheContext.class, SbtResolutionCacheExtractor.class)
                .named("Build SBT", BomToolType.SBT)
                .needsCurrentDirectory((context, file) -> context.directory = file)
                .needsFile(BUILD_SBT_FILENAME).noop()
                .build();

        add(buildSbtStrategy);

    }

}