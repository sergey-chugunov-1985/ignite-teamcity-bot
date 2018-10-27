/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.ci.analysis;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import org.apache.ignite.ci.tcmodel.changes.Change;
import org.apache.ignite.ci.tcmodel.result.tests.TestOccurrenceFull;
import org.apache.ignite.ci.teamcity.ignited.IStringCompactor;
import org.apache.ignite.ci.teamcity.ignited.fatbuild.FatBuildCompacted;
import org.apache.ignite.ci.teamcity.ignited.fatbuild.ProblemCompacted;
import org.apache.ignite.ci.util.FutureUtil;
import org.jetbrains.annotations.Nullable;

/**
 * Single build ocurrence,
 */
public class SingleBuildRunCtx implements ISuiteResults {
    /** Build compacted. */
    private FatBuildCompacted buildCompacted;

    /** Compactor. */
    private IStringCompactor compactor;

    /** Logger check result future. */
    private CompletableFuture<LogCheckResult> logCheckResFut;

    /** Changes. */
    private List<Change> changes = new ArrayList<>();

    /**
     * @param buildCompacted Build compacted.
     * @param compactor Compactor.
     */
    public SingleBuildRunCtx(FatBuildCompacted buildCompacted,
                             IStringCompactor compactor) {
        this.buildCompacted = buildCompacted;
        this.compactor = compactor;
    }

    /**
     *
     */
    public Integer buildId() {
        Preconditions.checkNotNull(buildCompacted);
        return buildCompacted.id() < 0 ? null : buildCompacted.id();
    }

    public boolean hasTimeoutProblem() {
        return getExecutionTimeoutCount() > 0;
    }

    private long getExecutionTimeoutCount() {
        return getProblemsStream().filter(p -> p.isExecutionTimeout(compactor)).count();
    }

    Stream<ProblemCompacted> getProblemsStream() {
        return buildCompacted.problems().stream();
    }

    @Override public boolean hasJvmCrashProblem() {
        return getProblemsStream().anyMatch(p -> p.isJvmCrash(compactor));
    }

    @Override public boolean hasOomeProblem() {
        return getProblemsStream().anyMatch(p -> p.isOome(compactor));
    }

    @Override public boolean hasExitCodeProblem() {
        return getProblemsStream().anyMatch(p -> p.isExitCode(compactor));
    }

    @Override public String suiteId() {
        return compactor.getStringFromId(buildCompacted.buildTypeId());
    }

    public void setLogCheckResFut(CompletableFuture<LogCheckResult> logCheckResFut) {
        this.logCheckResFut = logCheckResFut;
    }

    @Nullable public String getCriticalFailLastStartedTest() {
        LogCheckResult logCheckRes = getLogCheckIfFinished();
        if (logCheckRes == null)
            return null;

        return logCheckRes.getLastStartedTest();
    }

    @Nullable public Map<String, TestLogCheckResult> getTestLogCheckResult() {
        LogCheckResult logCheckRes = getLogCheckIfFinished();

        if (logCheckRes == null)
            return null;

        return logCheckRes.getTestLogCheckResult();
    }

    @Nullable public Integer getBuildIdIfHasThreadDump() {
        LogCheckResult logCheckRes = getLogCheckIfFinished();

        if (logCheckRes == null)
            return null;

        return !Strings.isNullOrEmpty(logCheckRes.getLastThreadDump()) ? buildId() : null;
    }

    @Nullable public LogCheckResult getLogCheckIfFinished() {
        if (logCheckResFut == null)
            return null;

        if (!logCheckResFut.isDone() || logCheckResFut.isCancelled())
            return null;

        LogCheckResult logCheckRes = FutureUtil.getResultSilent(logCheckResFut);

        if (logCheckRes == null)
            return null;

        return logCheckRes;
    }

    public void addChange(Change change) {
        if (change.isFakeStub())
            return;

        this.changes.add(change);
    }

    public List<Change> getChanges() {
        return changes;
    }

    public List<TestOccurrenceFull> getTests() {
        if(isComposite())
            return Collections.emptyList();

        return buildCompacted.getTestOcurrences(compactor).getTests();
    }


    @Nonnull Stream<? extends Future<?>> getFutures() {
        return logCheckResFut == null ? Stream.empty() : Stream.of((Future<?>)logCheckResFut);
    }

    public boolean isComposite() {
        return buildCompacted.isComposite();
    }

    public String getBranch() {
        return compactor.getStringFromId(buildCompacted.branchName());
    }


    /**
     * @return Names of not muted or ignored test failed for non composite build
     */
    public Stream<String> getFailedNotMutedTestNames() {
        if(isComposite())
            return Stream.empty();

        return buildCompacted.getFailedNotMutedTestNames(compactor);
    }

    public Stream<String> getAllTestNames() {
        return buildCompacted.getAllTestNames(compactor);
    }

    public String suiteName() {
        return buildCompacted.buildTypeName(compactor);
    }

    public String projectId() {
        return buildCompacted.projectId(compactor);
    }
}
