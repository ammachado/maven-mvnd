/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.mvndaemon.mvnd.daemon;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ClientDispatcherTest {
    @Test
    void trimTrailingEols() {
        Assertions.assertEquals(null, ClientDispatcher.trimTrailingEols(null));
        Assertions.assertEquals("foo", ClientDispatcher.trimTrailingEols("foo"));
        Assertions.assertEquals("foo\nbar", ClientDispatcher.trimTrailingEols("foo\nbar"));
        Assertions.assertEquals("foo\nbar", ClientDispatcher.trimTrailingEols("foo\nbar\n"));
        Assertions.assertEquals("foo\nbar", ClientDispatcher.trimTrailingEols("foo\nbar\r\n"));
        Assertions.assertEquals("foo\nbar", ClientDispatcher.trimTrailingEols("foo\nbar\n\r\n"));
        Assertions.assertEquals("", ClientDispatcher.trimTrailingEols("\n"));
    }

    @Test
    void maxArtifactIdLength() {
        // The display column must be sized to the longest artifactId so that the goal column
        // stays aligned; a single long name widens the column for every project.
        Assertions.assertEquals(0, ClientDispatcher.maxArtifactIdLength(Collections.emptyList()));
        Assertions.assertEquals(3, ClientDispatcher.maxArtifactIdLength(projects("foo")));
        Assertions.assertEquals(
                "a-very-long-artifact-id".length(),
                ClientDispatcher.maxArtifactIdLength(projects("short", "a-very-long-artifact-id", "mid")));
    }

    private static List<MavenProject> projects(String... artifactIds) {
        return Arrays.stream(artifactIds)
                .map(artifactId -> {
                    MavenProject project = new MavenProject();
                    project.setArtifactId(artifactId);
                    return project;
                })
                .collect(Collectors.toList());
    }
}
