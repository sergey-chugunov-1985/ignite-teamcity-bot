package org.apache.ignite.ci.web.rest.model.current;

import com.google.common.base.Objects;
import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.ignite.ci.ITcAnalytics;
import org.apache.ignite.ci.ITeamcity;
import org.apache.ignite.ci.analysis.ITestFailureOccurrences;
import org.apache.ignite.ci.analysis.MultBuildRunCtx;
import org.apache.ignite.ci.analysis.RunStat;
import org.apache.ignite.ci.analysis.TestLogCheckResult;
import org.apache.ignite.ci.tcmodel.result.tests.TestOccurrenceFull;
import org.apache.ignite.ci.web.rest.GetBuildLog;

import static org.apache.ignite.ci.util.TimeUtil.getDurationPrintable;
import static org.apache.ignite.ci.util.UrlUtil.escape;

/**
 * Represent Suite result
 */
@SuppressWarnings("WeakerAccess") public class SuiteCurrentStatus {
    /** Suite Name */
    public String name;

    /** Suite Run Result (filled if failed) */
    public String result;

    /** Web Href. to suite runs history */
    public String webToHist = "";

    /** Web Href. to suite particular run */
    public String webToBuild = "";

    /** Contact person. */
    public String contactPerson;

    public List<TestFailure> testFailures = new ArrayList<>();
    public List<TestFailure> topLongRunning = new ArrayList<>();
    public List<TestFailure> warnOnly = new ArrayList<>();
    public List<TestFailure> logConsumers = new ArrayList<>();

    /** Web Href. to thread dump display */
    @Nullable public String webUrlThreadDump;

    @Nullable public Integer runningBuildCount;
    @Nullable public Integer queuedBuildCount;

    /** TC server id. */
    public String serverId;

    /** Suite ID in teamcity identification. */
    public String suiteId;

    /** Branch name in teamcity identification. */
    public String branchName;

    /** Registered number of failures from TC helper DB */
    @Nullable public Integer failures;

    /** Registered number of runs from TC helper DB */
    @Nullable public Integer runs;

    /** Registered percent of fails from TC helper DB */
    @Nullable public String failureRate;

    /** User commits, comma separated string. */
    public String userCommits = "";

    public Integer failedTests;

    public String durationPrintable;

    public void initFromContext(@Nonnull final ITeamcity teamcity,
        @Nonnull final MultBuildRunCtx suite,
        @Nullable final ITcAnalytics tcAnalytics) {

        name = suite.suiteName();

        String suiteId = suite.suiteId();
        if (!Strings.isNullOrEmpty(suiteId) && tcAnalytics != null) {
            final RunStat stat = tcAnalytics.getBuildFailureRunStatProvider().apply(suiteId);

            if (stat != null) {
                failures = stat.failures;
                runs = stat.runs;
                failureRate = stat.getFailPercentPrintable();
            }
        }

        Set<String> collect = suite.lastChangeUsers().collect(Collectors.toSet());

        if(!collect.isEmpty())
            userCommits = collect.toString();

        result = suite.getResult();
        failedTests = suite.failedTests();
        durationPrintable = getDurationPrintable(suite.getBuildDuration());
        contactPerson = suite.getContactPerson();
        webToHist = buildWebLink(teamcity, suite);
        webToBuild = buildWebLinkToBuild(teamcity, suite);

        Stream<? extends ITestFailureOccurrences> tests = suite.getFailedTests();
        if (tcAnalytics != null) {
            Function<ITestFailureOccurrences, Float> function = foccur -> {
                RunStat apply = tcAnalytics.getTestRunStatProvider().apply(foccur.getName());

                return apply == null ? 0f : apply.getFailRate();
            };
            tests = tests.sorted(Comparator.comparing(function).reversed());
        }

        tests.forEach(occurrence -> {
            Stream<TestOccurrenceFull> stream = suite.getFullTests(occurrence);

            final TestFailure failure = new TestFailure();
            failure.initFromOccurrence(occurrence, stream, teamcity, suite);
            if (tcAnalytics != null)
                failure.initStat(tcAnalytics.getTestRunStatProvider());

            testFailures.add(failure);
        });

        suite.getTopLongRunning().forEach(occurrence -> {
            final TestFailure failure = new TestFailure();
            failure.initFromOccurrence(occurrence, Stream.empty(), teamcity, suite);

            if (tcAnalytics != null)
                failure.initStat(tcAnalytics.getTestRunStatProvider());

            topLongRunning.add(failure);
        });

        suite.getCriticalFailLastStartedTest().forEach(
            lastTest -> {
                final TestFailure failure = new TestFailure();
                failure.name = lastTest + " (last started)";
                testFailures.add(failure);
            }
        );

        Map<String, Long> logSizeBytes = new HashMap<>();

        suite.getLogsCheckResults().forEach(map -> {
            map.forEach(
                (testName, logCheckResult) -> {
                    if (logCheckResult.hasWarns())
                        findFailureAndAddWarning(testName, logCheckResult);

                    logSizeBytes.merge(testName, (long)logCheckResult.getLogSizeBytes(), (a, b) -> a + b);
                }
            );
        });
        List<Map.Entry<String, Long>> testSizes = new ArrayList<>(logSizeBytes.entrySet());
        Comparator<Map.Entry<String, Long>> comparing = Comparator.comparing(Map.Entry::getValue);
        testSizes.sort(comparing.reversed());
        testSizes.stream().limit(3).filter(entry -> entry.getValue() > 1024 * 1024).forEach(
            (entry) -> {
                TestFailure failure = new TestFailure();
                long sizeMb = entry.getValue() / 1024 / 1024;
                failure.name = entry.getKey() + " " + sizeMb + " Mbytes";
                logConsumers.add(failure);
            }
        );

        suite.getBuildsWithThreadDump().forEach(buildId -> {
            webUrlThreadDump = "/rest/" + GetBuildLog.GET_BUILD_LOG + "/" + GetBuildLog.THREAD_DUMP
                + "?" + GetBuildLog.SERVER_ID + "=" + teamcity.serverId()
                + "&" + GetBuildLog.BUILD_NO + "=" + Integer.toString(buildId)
                + "&" + GetBuildLog.FILE_IDX + "=" + Integer.toString(-1);
        });

        runningBuildCount = suite.runningBuildCount();
        queuedBuildCount = suite.queuedBuildCount();
        serverId = teamcity.serverId();
        this.suiteId = suite.suiteId();
        branchName = branchForLink(suite.branchName());
    }

    public void findFailureAndAddWarning(String testName, TestLogCheckResult logCheckRes) {
        TestFailure failure = testFailures.stream().filter(f -> f.name.contains(testName)).findAny().orElseGet(
            () -> {
                return warnOnly.stream().filter(f -> f.name.contains(testName)).findAny().orElseGet(
                    () -> {
                        TestFailure f = new TestFailure();
                        f.name = testName + " (warning)";
                        warnOnly.add(f);

                        return f;
                    });
            });

        failure.warnings.addAll(logCheckRes.getWarns());
    }

    private static String buildWebLinkToBuild(ITeamcity teamcity, MultBuildRunCtx suite) {
        return teamcity.host() + "viewLog.html?buildId=" + Integer.toString(suite.getBuildId());
    }

    private static String buildWebLink(ITeamcity teamcity, MultBuildRunCtx suite) {
        final String branch = branchForLink(suite.branchName());
        return teamcity.host() + "viewType.html?buildTypeId=" + suite.suiteId()
            + "&branch=" + escape(branch)
            + "&tab=buildTypeStatusDiv";
    }

    public static String branchForLink(String branchName) {
        return branchName == null || "refs/heads/master".equals(branchName) ? "<default>" : branchName;
    }

    @Override public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        SuiteCurrentStatus status = (SuiteCurrentStatus)o;
        return Objects.equal(name, status.name) &&
            Objects.equal(result, status.result) &&
            Objects.equal(webToHist, status.webToHist) &&
            Objects.equal(webToBuild, status.webToBuild) &&
            Objects.equal(contactPerson, status.contactPerson) &&
            Objects.equal(testFailures, status.testFailures) &&
            Objects.equal(topLongRunning, status.topLongRunning) &&
            Objects.equal(webUrlThreadDump, status.webUrlThreadDump) &&
            Objects.equal(runningBuildCount, status.runningBuildCount) &&
            Objects.equal(queuedBuildCount, status.queuedBuildCount) &&
            Objects.equal(serverId, status.serverId) &&
            Objects.equal(suiteId, status.suiteId) &&
            Objects.equal(branchName, status.branchName) &&
            Objects.equal(failures, status.failures) &&
            Objects.equal(runs, status.runs) &&
            Objects.equal(failureRate, status.failureRate) &&
            Objects.equal(userCommits, status.userCommits) &&
            Objects.equal(failedTests, status.failedTests) &&
            Objects.equal(durationPrintable, status.durationPrintable)&&
            Objects.equal(warnOnly, status.warnOnly);
    }

    @Override public int hashCode() {
        return Objects.hashCode(name, result, webToHist, webToBuild, contactPerson, testFailures,
            topLongRunning, webUrlThreadDump, runningBuildCount, queuedBuildCount, serverId,
            suiteId, branchName, failures, runs, failureRate, userCommits, failedTests, durationPrintable,
            warnOnly);
    }
}
