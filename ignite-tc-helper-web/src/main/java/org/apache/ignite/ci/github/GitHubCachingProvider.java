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
package org.apache.ignite.ci.github;

import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import org.apache.ignite.ci.user.ICredentialsProv;
import org.apache.ignite.ci.util.ExceptionUtil;

class GitHubCachingProvider implements IGitHubConnectionProvider {
    @Inject Provider<IGitHubConnection> factory;
    private final Cache<String, IGitHubConnection> srvs
        = CacheBuilder.newBuilder()
        .maximumSize(100)
        .expireAfterAccess(16, TimeUnit.MINUTES)
        .softValues()
        .build();

    /** {@inheritDoc} */
    @Override public IGitHubConnection server(String srvId, @Nullable ICredentialsProv prov) {
        Callable<IGitHubConnection> call = () -> {
            IGitHubConnection conn = factory.get();
            conn.init(srvId);
            return conn;
        };
        String fullKey = Strings.nullToEmpty(srvId);

        IGitHubConnection gitHubConn;
        try {
            gitHubConn = srvs.get(fullKey, call);
        }
        catch (ExecutionException e) {
            throw ExceptionUtil.propagateException(e);
        }
        return gitHubConn;
    }

}
