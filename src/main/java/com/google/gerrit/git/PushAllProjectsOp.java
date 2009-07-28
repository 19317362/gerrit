// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.git;

import com.google.gerrit.client.reviewdb.Branch;
import com.google.gerrit.client.reviewdb.Project;
import com.google.gerrit.client.reviewdb.ProjectRight;
import com.google.gerrit.client.reviewdb.ReviewDb;
import com.google.gerrit.server.GerritServer;
import com.google.gwtorm.client.OrmException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;

public class PushAllProjectsOp implements Runnable {
  private static final Logger log =
      LoggerFactory.getLogger(PushAllProjectsOp.class);

  private final GerritServer server;
  private final ReplicationQueue replication;
  private final String urlMatch;

  public PushAllProjectsOp(final GerritServer gs, final ReplicationQueue rq) {
    this(gs, rq, null);
  }

  public PushAllProjectsOp(final GerritServer gs, final ReplicationQueue rq,
      final String urlMatch) {
    this.server = gs;
    this.replication = rq;
    this.urlMatch = urlMatch;
  }

  public void run() {
    final HashSet<Branch.NameKey> pending = new HashSet<Branch.NameKey>();
    try {
      final ReviewDb db = server.getSchemaFactory().open();
      try {
        for (final Project project : db.projects().all()) {
          if (!ProjectRight.WILD_PROJECT.equals(project.getId())) {
            replication.scheduleFullSync(project.getNameKey(), urlMatch);
          }
        }
      } finally {
        db.close();
      }
    } catch (OrmException e) {
      log.error("Cannot enumerate known projects", e);
    }
  }

  @Override
  public String toString() {
    String s = "Replicate All Projects";
    if (urlMatch != null) {
      s = s + " to " + urlMatch;
    }
    return s;
  }
}
