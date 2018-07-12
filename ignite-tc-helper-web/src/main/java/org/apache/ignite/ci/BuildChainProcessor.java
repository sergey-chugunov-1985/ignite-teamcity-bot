package org.apache.ignite.ci;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import org.apache.ignite.ci.analysis.FullChainRunCtx;
import org.apache.ignite.ci.analysis.MultBuildRunCtx;
import org.apache.ignite.ci.analysis.RunStat;
import org.apache.ignite.ci.analysis.SingleBuildRunCtx;
import org.apache.ignite.ci.analysis.SuiteInBranch;
import org.apache.ignite.ci.analysis.mode.LatestRebuildMode;
import org.apache.ignite.ci.analysis.mode.ProcessLogsMode;
import org.apache.ignite.ci.tcmodel.hist.BuildRef;
import org.apache.ignite.ci.tcmodel.result.Build;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Collections.singletonList;

public class BuildChainProcessor {
    private static final Logger logger = LoggerFactory.getLogger(BuildChainProcessor.class);

    @Nonnull public static Optional<FullChainRunCtx> loadChainsContext(
        IAnalyticsEnabledTeamcity teamcity,
        String suiteId,
        String branch,
        LatestRebuildMode rebuildMode,
        ProcessLogsMode procLogs,
        @Nullable String failRateBranch) {
        final List<BuildRef> builds = teamcity.getFinishedBuildsIncludeSnDepFailed(suiteId, branch);
        Optional<BuildRef> buildRef = builds.stream().max(Comparator.comparing(BuildRef::getId));

        return buildRef.flatMap(
            build -> processBuildChains(teamcity, rebuildMode, singletonList(build),
                procLogs, true, true, teamcity, failRateBranch));
    }

    public static Optional<FullChainRunCtx> processBuildChains(
        ITeamcity teamcity,
        LatestRebuildMode includeLatestRebuild,
        Collection<BuildRef> builds,
        ProcessLogsMode procLogs,
        boolean includeScheduled,
        boolean showContacts,
        @Nullable ITcAnalytics tcAnalytics,
        @Nullable String failRateBranch) {

        final Properties responsible = showContacts ? getContactPersonProperties(teamcity) : null;

        final FullChainRunCtx val = loadChainsContext(teamcity, builds,
            includeLatestRebuild,
            procLogs, responsible, includeScheduled, tcAnalytics,
            failRateBranch);

        return Optional.of(val);
    }

    @Nullable private static Properties getContactPersonProperties(ITeamcity teamcity) {
        return HelperConfig.loadContactPersons(teamcity.serverId());
    }

    public static <R> FullChainRunCtx loadChainsContext(
        ITeamcity teamcity,
        Collection<BuildRef> entryPoints,
        LatestRebuildMode includeLatestRebuild,
        ProcessLogsMode procLog,
        @Nullable Properties contactPersonProps,
        boolean includeScheduledInfo,
        @Nullable ITcAnalytics tcAnalytics,
        @Nullable String failRateBranch) {

        if (entryPoints.isEmpty())
            return new FullChainRunCtx(Build.createFakeStub());

        BuildRef next = entryPoints.iterator().next();
        Build results = teamcity.getBuild(next.href);
        FullChainRunCtx fullChainRunCtx = new FullChainRunCtx(results);

        Map<Integer, BuildRef> unique = new ConcurrentHashMap<>();
        Map<String, MultBuildRunCtx> buildsCtxMap = new ConcurrentHashMap<>();

        entryPoints.stream()
            .parallel()
            .unordered()
            .flatMap(ref -> dependencies(teamcity, ref)).filter(Objects::nonNull)
            .flatMap(ref -> dependencies(teamcity, ref)).filter(Objects::nonNull)
            .filter(ref -> ensureUnique(unique, ref))
            .flatMap((BuildRef buildRef) -> {
                    if (includeLatestRebuild == LatestRebuildMode.NONE)
                        return Stream.of(buildRef);

                    final String branch = getBranchOrDefault(buildRef.branchName);

                    final List<BuildRef> builds = teamcity.getFinishedBuilds(buildRef.buildTypeId, branch);

                    if (includeLatestRebuild == LatestRebuildMode.LATEST) {
                        BuildRef recentRef = builds.stream().max(Comparator.comparing(BuildRef::getId)).orElse(buildRef);

                        return Stream.of(recentRef.isFakeStub() ? buildRef : recentRef);
                    }

                    if (includeLatestRebuild == LatestRebuildMode.ALL) {
                        return builds.stream()
                            .filter(ref -> !ref.isFakeStub())
                            .filter(ref -> ensureUnique(unique, ref))
                            .sorted(Comparator.comparing(BuildRef::getId).reversed())
                            .limit(entryPoints.size()); // applying same limit
                    }

                    throw new UnsupportedOperationException("invalid mode " + includeLatestRebuild);
                }
            )
            .forEach((BuildRef buildRef) -> {
                Build build = teamcity.getBuild(buildRef.href);
                if (build == null || build.isFakeStub())
                    return;

                String buildTypeId = build.buildTypeId;
                MultBuildRunCtx ctx = buildsCtxMap.computeIfAbsent(buildTypeId, k -> new MultBuildRunCtx(build));

                collectBuildContext(ctx, teamcity, procLog, contactPersonProps, includeScheduledInfo, build);
            });

        Collection<MultBuildRunCtx> values = buildsCtxMap.values();
        ArrayList<MultBuildRunCtx> contexts = new ArrayList<>(values);
        if (tcAnalytics != null) {
            Function<MultBuildRunCtx, Float> function = ctx -> {
                SuiteInBranch key = new SuiteInBranch(ctx.suiteId(), normalizeBranch(failRateBranch));

                RunStat runStat = tcAnalytics.getBuildFailureRunStatProvider().apply(key);

                if (runStat == null)
                    return 0f;

                //some hack to bring timed out suites to top
                return runStat.getCriticalFailRate() * 3.14f + runStat.getFailRate();
            };

            contexts.sort(Comparator.comparing(function).reversed());
        }
        else if (contactPersonProps != null)
            contexts.sort(Comparator.comparing(MultBuildRunCtx::getContactPersonOrEmpty));
        else
            contexts.sort(Comparator.comparing(MultBuildRunCtx::suiteName));

        fullChainRunCtx.addAllSuites(contexts);

        return fullChainRunCtx;
    }

    @NotNull private static String getBranchOrDefault(@Nullable String branchName) {
        return branchName == null ? ITeamcity.DEFAULT : branchName;
    }

    private static boolean ensureUnique(Map<Integer, BuildRef> unique, BuildRef ref) {
        if (ref.isFakeStub())
            return false;

        BuildRef prevVal = unique.putIfAbsent(ref.getId(), ref);

        return prevVal == null;
    }

    private static void collectBuildContext(
        MultBuildRunCtx outCtx, ITeamcity teamcity, ProcessLogsMode procLog,
        @Nullable Properties contactPersonProps, boolean includeScheduledInfo, Build build) {

        SingleBuildRunCtx ctx = teamcity.loadTestsAndProblems(build, outCtx);
        outCtx.addBuild(ctx);

        if ((procLog == ProcessLogsMode.SUITE_NOT_COMPLETE && ctx.hasSuiteIncompleteFailure())
            || procLog == ProcessLogsMode.ALL)
            ctx.setLogCheckResultsFut(teamcity.analyzeBuildLog(ctx.buildId(), ctx));

        if (includeScheduledInfo && !outCtx.hasScheduledBuildsInfo()) {
            Function<List<BuildRef>, Long> countRelatedToThisBuildType = list ->
                list.stream()
                    .filter(ref -> Objects.equals(ref.buildTypeId, build.buildTypeId))
                    .filter(ref -> Objects.equals(normalizeBranch(build), normalizeBranch(ref)))
                    .count();

            outCtx.setRunningBuildCount(teamcity.getRunningBuilds("").thenApply(countRelatedToThisBuildType));
            outCtx.setQueuedBuildCount(teamcity.getQueuedBuilds("").thenApply(countRelatedToThisBuildType));
        }

        if (contactPersonProps != null && outCtx.getContactPerson() == null)
            outCtx.setContactPerson(contactPersonProps.getProperty(outCtx.suiteId()));
    }

    @NotNull protected static String normalizeBranch(@NotNull final BuildRef build) {
        return normalizeBranch(build.branchName);
    }

    @NotNull public static String normalizeBranch(@Nullable String branchName) {
        String branch = getBranchOrDefault(branchName);

        if ("refs/heads/master".equals(branch))
            return ITeamcity.DEFAULT;

        return branch;
    }

    @Nullable private static Stream<? extends BuildRef> dependencies(ITeamcity teamcity, BuildRef ref) {
        Build results = teamcity.getBuild(ref.href);
        if (results == null)
            return Stream.of(ref);

        List<BuildRef> aNull = results.getSnapshotDependenciesNonNull();
        if(aNull.isEmpty())
            return Stream.of(ref);

        logger.info("Snapshot deps found: " +
            ref.suiteId() + "->" + aNull.stream().map(BuildRef::suiteId).collect(Collectors.toList()));

        List<BuildRef> cp = new ArrayList<>(aNull);

        cp.add(ref);

        return cp.stream();
    }
}
