// Copyright (C) 2008 The Android Open Source Project
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

package com.google.gerrit.pgm;

import com.google.gerrit.client.reviewdb.Change;
import com.google.gerrit.client.reviewdb.PatchSet;
import com.google.gerrit.client.reviewdb.Project;
import com.google.gerrit.client.reviewdb.ReviewDb;
import com.google.gerrit.git.PatchSetImporter;
import com.google.gerrit.server.GerritServer;
import com.google.gerrit.server.GerritServerModule;
import com.google.gwtorm.client.OrmException;
import com.google.gwtorm.client.SchemaFactory;
import com.google.inject.Guice;
import com.google.inject.Inject;

import org.spearce.jgit.errors.RepositoryNotFoundException;
import org.spearce.jgit.lib.ObjectId;
import org.spearce.jgit.lib.ProgressMonitor;
import org.spearce.jgit.lib.Repository;
import org.spearce.jgit.lib.TextProgressMonitor;
import org.spearce.jgit.revwalk.RevCommit;
import org.spearce.jgit.revwalk.RevWalk;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.ArrayList;

/**
 * Recreates PatchSet and Patch entities for the changes supplied.
 * <p>
 * Takes on input strings of the form <code>change_id|patch_set_id</code> or
 * <code>change_id,patch_set_id</code>, such as might be created by the
 * following PostgreSQL database dump:
 * 
 * <pre>
 *  psql reviewdb -tAc 'select change_id,patch_set_id from patch_sets'
 * </pre>
 * <p>
 * For each supplied PatchSet the info and patch entities are completely updated
 * based on the data stored in Git.
 */
public class ReimportPatchSets extends AbstractProgram {
  @Inject
  private SchemaFactory<ReviewDb> schema;

  @Inject
  private GerritServer gs;

  @Override
  public int run() throws Exception {
    Guice.createInjector(new GerritServerModule()).injectMembers(this);

    final ArrayList<PatchSet.Id> todo = new ArrayList<PatchSet.Id>();
    final BufferedReader br =
        new BufferedReader(new InputStreamReader(System.in));
    String line;
    while ((line = br.readLine()) != null) {
      todo.add(PatchSet.Id.parse(line.replace('|', ',')));
    }

    int exitStatus = 0;
    final ReviewDb db = schema.open();
    final ProgressMonitor pm = new TextProgressMonitor();
    try {
      pm.start(1);
      pm.beginTask("Import patch sets", todo.size());
      for (int i = 0; i < todo.size(); i++) {
        final PatchSet.Id psid = todo.get(i);
        final PatchSet ps = db.patchSets().get(psid);
        if (ps == null) {
          System.err.println();
          System.err.println("NotFound " + psid);
          continue;
        }

        final Change c = db.changes().get(ps.getId().getParentKey());
        if (c == null) {
          System.err.println();
          System.err.println("Orphan " + psid);
          continue;
        }

        final Project.NameKey projectKey = c.getDest().getParentKey();
        final String projectName = projectKey.get();
        final Repository repo;
        try {
          repo = gs.openRepository(projectName);
        } catch (RepositoryNotFoundException ie) {
          System.err.println();
          System.err.println("NoProject " + psid);
          System.err.println("NoProject " + ie.getMessage());
          continue;
        }

        final RevWalk rw = new RevWalk(repo);
        final RevCommit src =
            rw.parseCommit(ObjectId.fromString(ps.getRevision().get()));
        new PatchSetImporter(gs, db, projectKey, repo, src, ps, false).run();
        pm.update(1);
        repo.close();
      }
    } catch (OrmException e) {
      System.err.println();
      e.printStackTrace();
      if (e.getCause() instanceof SQLException) {
        final SQLException e2 = (SQLException) e.getCause();
        if (e2.getNextException() != null) {
          e2.getNextException().printStackTrace();
        }
      }
      exitStatus = 1;
    } finally {
      pm.endTask();
      db.close();
    }
    return exitStatus;
  }
}
