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

package com.google.gerrit.server;

import com.google.gerrit.client.admin.ProjectAdminService;
import com.google.gerrit.client.admin.ProjectDetail;
import com.google.gerrit.client.data.ProjectCache;
import com.google.gerrit.client.reviewdb.Account;
import com.google.gerrit.client.reviewdb.AccountGroup;
import com.google.gerrit.client.reviewdb.ApprovalCategory;
import com.google.gerrit.client.reviewdb.ApprovalCategoryValue;
import com.google.gerrit.client.reviewdb.Branch;
import com.google.gerrit.client.reviewdb.Project;
import com.google.gerrit.client.reviewdb.ProjectRight;
import com.google.gerrit.client.reviewdb.ReviewDb;
import com.google.gerrit.client.reviewdb.AccountGroup.Id;
import com.google.gerrit.client.rpc.Common;
import com.google.gerrit.client.rpc.InvalidNameException;
import com.google.gerrit.client.rpc.InvalidRevisionException;
import com.google.gerrit.client.rpc.NoSuchEntityException;
import com.google.gerrit.git.PushQueue;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwtjsonrpc.client.VoidResult;
import com.google.gwtorm.client.OrmException;
import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spearce.jgit.errors.IncorrectObjectTypeException;
import org.spearce.jgit.errors.MissingObjectException;
import org.spearce.jgit.errors.RepositoryNotFoundException;
import org.spearce.jgit.lib.Constants;
import org.spearce.jgit.lib.LockFile;
import org.spearce.jgit.lib.ObjectId;
import org.spearce.jgit.lib.Ref;
import org.spearce.jgit.lib.RefUpdate;
import org.spearce.jgit.lib.Repository;
import org.spearce.jgit.revwalk.ObjectWalk;
import org.spearce.jgit.revwalk.RevCommit;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

class ProjectAdminServiceImpl extends BaseServiceImplementation implements
    ProjectAdminService {
  private final Logger log = LoggerFactory.getLogger(getClass());

  @Inject
  ProjectAdminServiceImpl(final GerritServer gs) {
    super(gs);
  }

  public void ownedProjects(final AsyncCallback<List<Project>> callback) {
    run(callback, new Action<List<Project>>() {
      public List<Project> run(ReviewDb db) throws OrmException {
        final List<Project> result = myOwnedProjects(db);
        Collections.sort(result, new Comparator<Project>() {
          public int compare(final Project a, final Project b) {
            return a.getName().compareTo(b.getName());
          }
        });
        return result;
      }
    });
  }

  public void projectDetail(final Project.Id projectId,
      final AsyncCallback<ProjectDetail> callback) {
    run(callback, new Action<ProjectDetail>() {
      public ProjectDetail run(ReviewDb db) throws OrmException, Failure {
        assertAmProjectOwner(db, projectId);
        final ProjectCache.Entry p = Common.getProjectCache().get(projectId);
        if (p == null) {
          throw new Failure(new NoSuchEntityException());
        }

        final ProjectDetail d = new ProjectDetail();
        d.load(db, p);
        return d;
      }
    });
  }

  public void changeProjectSettings(final Project update,
      final AsyncCallback<ProjectDetail> callback) {
    run(callback, new Action<ProjectDetail>() {
      public ProjectDetail run(final ReviewDb db) throws OrmException, Failure {
        assertAmProjectOwner(db, update.getId());
        final Project proj = db.projects().get(update.getId());
        if (proj == null) {
          throw new Failure(new NoSuchEntityException());
        }
        proj.copySettingsFrom(update);
        db.projects().update(Collections.singleton(proj));
        Common.getProjectCache().invalidate(proj);

        if (!ProjectRight.WILD_PROJECT.equals(update.getId())) {
          // Update git's description file, in case gitweb is being used
          //
          try {
            final Repository e;
            final LockFile f;

            e = server.openRepository(proj.getName());
            f = new LockFile(new File(e.getDirectory(), "description"));
            if (f.lock()) {
              String d = proj.getDescription();
              if (d != null) {
                d = d.trim();
                if (d.length() > 0) {
                  d += "\n";
                }
              } else {
                d = "";
              }
              f.write(Constants.encode(d));
              f.commit();
            }
            e.close();
          } catch (RepositoryNotFoundException e) {
            log.error("Cannot update description for " + proj.getName(), e);
          } catch (IOException e) {
            log.error("Cannot update description for " + proj.getName(), e);
          }
        }

        final ProjectDetail d = new ProjectDetail();
        d.load(db, Common.getProjectCache().get(update.getId()));
        return d;
      }
    });
  }

  public void deleteRight(final Set<ProjectRight.Key> keys,
      final AsyncCallback<VoidResult> callback) {
    run(callback, new Action<VoidResult>() {
      public VoidResult run(final ReviewDb db) throws OrmException, Failure {
        final Set<Project.Id> owned = ids(myOwnedProjects(db));
        for (final ProjectRight.Key k : keys) {
          if (!owned.contains(k.getProjectId())) {
            throw new Failure(new NoSuchEntityException());
          }
        }
        for (final ProjectRight.Key k : keys) {
          final ProjectRight m = db.projectRights().get(k);
          if (m != null) {
            db.projectRights().delete(Collections.singleton(m));
            Common.getProjectCache().invalidate(k.getProjectId());
          }
        }
        return VoidResult.INSTANCE;
      }
    });
  }

  public void addRight(final Project.Id projectId,
      final ApprovalCategory.Id categoryId, final String groupName,
      final short amin, final short amax,
      final AsyncCallback<ProjectDetail> callback) {
    if (ProjectRight.WILD_PROJECT.equals(projectId)
        && ApprovalCategory.OWN.equals(categoryId)) {
      // Giving out control of the WILD_PROJECT to other groups beyond
      // Administrators is dangerous. Having control over WILD_PROJECT
      // is about the same as having Administrator access as users are
      // able to affect grants in all projects on the system.
      //
      callback.onFailure(new NoSuchEntityException());
      return;
    }

    final short min, max;
    if (amin <= amax) {
      min = amin;
      max = amax;
    } else {
      min = amax;
      max = amin;
    }

    run(callback, new Action<ProjectDetail>() {
      public ProjectDetail run(ReviewDb db) throws OrmException, Failure {
        assertAmProjectOwner(db, projectId);
        final Project proj = db.projects().get(projectId);
        if (proj == null) {
          throw new Failure(new NoSuchEntityException());
        }

        final ApprovalCategory cat = db.approvalCategories().get(categoryId);
        if (cat == null) {
          throw new Failure(new NoSuchEntityException());
        }

        if (db.approvalCategoryValues().get(
            new ApprovalCategoryValue.Id(categoryId, min)) == null) {
          throw new Failure(new NoSuchEntityException());
        }

        if (db.approvalCategoryValues().get(
            new ApprovalCategoryValue.Id(categoryId, max)) == null) {
          throw new Failure(new NoSuchEntityException());
        }

        final AccountGroup group =
            db.accountGroups().get(new AccountGroup.NameKey(groupName));
        if (group == null) {
          throw new Failure(new NoSuchEntityException());
        }

        final ProjectRight.Key key =
            new ProjectRight.Key(projectId, categoryId, group.getId());
        ProjectRight pr = db.projectRights().get(key);
        if (pr == null) {
          pr = new ProjectRight(key);
          pr.setMinValue(min);
          pr.setMaxValue(max);
          db.projectRights().insert(Collections.singleton(pr));
        } else {
          pr.setMinValue(min);
          pr.setMaxValue(max);
          db.projectRights().update(Collections.singleton(pr));
        }

        Common.getProjectCache().invalidate(proj);
        final ProjectDetail d = new ProjectDetail();
        d.load(db, Common.getProjectCache().get(projectId));
        return d;
      }
    });
  }

  public void listBranches(final Project.Id project,
      final AsyncCallback<List<Branch>> callback) {
    run(callback, new Action<List<Branch>>() {
      public List<Branch> run(ReviewDb db) throws OrmException, Failure {
        final ProjectCache.Entry e = Common.getProjectCache().get(project);
        if (e == null) {
          throw new Failure(new NoSuchEntityException());
        }
        assertCanRead(e.getProject().getNameKey());
        return db.branches().byProject(e.getProject().getNameKey()).toList();
      }
    });
  }

  public void deleteBranch(final Set<Branch.NameKey> ids,
      final AsyncCallback<Set<Branch.NameKey>> callback) {
    run(callback, new Action<Set<Branch.NameKey>>() {
      public Set<Branch.NameKey> run(ReviewDb db) throws OrmException, Failure {
        final Set<Branch.NameKey> deleted = new HashSet<Branch.NameKey>();
        final Set<Project.Id> owned = ids(myOwnedProjects(db));
        for (final Branch.NameKey k : ids) {
          final ProjectCache.Entry e;

          e = Common.getProjectCache().get(k.getParentKey());
          if (e == null) {
            throw new Failure(new NoSuchEntityException());
          }
          if (!owned.contains(e.getProject().getId())) {
            throw new Failure(new NoSuchEntityException());
          }
        }
        for (final Branch.NameKey k : ids) {
          final Branch m = db.branches().get(k);
          if (m == null) {
            continue;
          }
          final Repository r;

          try {
            r = server.openRepository(k.getParentKey().get());
          } catch (RepositoryNotFoundException e) {
            throw new Failure(new NoSuchEntityException());
          }

          final RefUpdate.Result result;
          try {
            final RefUpdate u = r.updateRef(m.getName());
            u.setForceUpdate(true);
            result = u.delete();
          } catch (IOException e) {
            log.error("Cannot delete " + k, e);
            r.close();
            continue;
          }

          final Branch.NameKey mKey = m.getNameKey();
          switch (result) {
            case NEW:
            case NO_CHANGE:
            case FAST_FORWARD:
            case FORCED:
              db.branches().delete(Collections.singleton(m));
              deleted.add(mKey);
              PushQueue.scheduleUpdate(mKey.getParentKey(), m.getName());
              break;

            case REJECTED_CURRENT_BRANCH:
              log.warn("Cannot delete " + k + ": " + result.name());
              break;

            default:
              log.error("Cannot delete " + k + ": " + result.name());
              break;
          }
          r.close();
        }
        return deleted;
      }
    });
  }

  public void addBranch(final Project.Id projectId, final String branchName,
      final String startingRevision, final AsyncCallback<List<Branch>> callback) {
    run(callback, new Action<List<Branch>>() {
      public List<Branch> run(ReviewDb db) throws OrmException, Failure {
        String refname = branchName;
        if (!refname.startsWith(Constants.R_REFS)) {
          refname = Constants.R_HEADS + refname;
        }
        if (!Repository.isValidRefName(refname)) {
          throw new Failure(new InvalidNameException());
        }

        final Account me = Common.getAccountCache().get(Common.getAccountId());
        if (me == null) {
          throw new Failure(new NoSuchEntityException());
        }
        final ProjectCache.Entry pce = Common.getProjectCache().get(projectId);
        if (pce == null) {
          throw new Failure(new NoSuchEntityException());
        }
        assertAmProjectOwner(db, projectId);

        final String repoName = pce.getProject().getName();
        final Branch.NameKey name =
            new Branch.NameKey(pce.getProject().getNameKey(), refname);
        final Repository repo;
        try {
          repo = server.openRepository(repoName);
        } catch (RepositoryNotFoundException e1) {
          throw new Failure(new NoSuchEntityException());
        }

        try {
          // Convert the name given by the user into a valid object.
          //
          final ObjectId revid;
          try {
            revid = repo.resolve(startingRevision);
            if (revid == null) {
              throw new Failure(new InvalidRevisionException());
            }
          } catch (IOException err) {
            log.error("Cannot resolve \"" + startingRevision + "\" in "
                + repoName, err);
            throw new Failure(new InvalidRevisionException());
          }

          // Ensure it is fully connected in this repository. If not,
          // we can't safely create a ref to it as objects are missing
          //
          final RevCommit revcommit;
          final ObjectWalk rw = new ObjectWalk(repo);
          try {
            try {
              revcommit = rw.parseCommit(revid);
              rw.markStart(revcommit);
            } catch (IncorrectObjectTypeException err) {
              throw new Failure(new InvalidRevisionException());
            }
            for (final Ref r : repo.getAllRefs().values()) {
              try {
                rw.markUninteresting(rw.parseAny(r.getObjectId()));
              } catch (MissingObjectException err) {
                continue;
              }
            }
            rw.checkConnectivity();
          } catch (IncorrectObjectTypeException err) {
            throw new Failure(new InvalidRevisionException());
          } catch (MissingObjectException err) {
            throw new Failure(new InvalidRevisionException());
          } catch (IOException err) {
            log.error("Repository " + repoName + " possibly corrupt", err);
            throw new Failure(new InvalidRevisionException());
          }

          final HttpServletRequest hreq =
              GerritJsonServlet.getCurrentCall().getHttpServletRequest();
          try {
            final RefUpdate u = repo.updateRef(refname);
            u.setExpectedOldObjectId(ObjectId.zeroId());
            u.setNewObjectId(revid);
            u.setRefLogIdent(ChangeUtil.toReflogIdent(me,
                new InetSocketAddress(hreq.getRemoteHost(), hreq
                    .getRemotePort())));
            u.setRefLogMessage("created via web from " + startingRevision,
                false);
            final RefUpdate.Result result = u.update(rw);
            switch (result) {
              case FAST_FORWARD:
              case NEW:
              case NO_CHANGE:
                PushQueue.scheduleUpdate(name.getParentKey(), refname);
                break;
              default: {
                final String msg =
                    "Cannot create branch " + name + ": " + result.name();
                log.error(msg);
                throw new Failure(new IOException(result.name()));
              }
            }
          } catch (IOException err) {
            log.error("Cannot create branch " + name, err);
            throw new Failure(err);
          }
        } finally {
          repo.close();
        }

        final Branch.Id id = new Branch.Id(db.nextBranchId());
        final Branch newBranch = new Branch(name, id);
        db.branches().insert(Collections.singleton(newBranch));
        return db.branches().byProject(pce.getProject().getNameKey()).toList();
      }
    });
  }

  private void assertAmProjectOwner(final ReviewDb db,
      final Project.Id projectId) throws Failure {
    final ProjectCache.Entry p = Common.getProjectCache().get(projectId);
    if (p == null) {
      throw new Failure(new NoSuchEntityException());
    }
    if (Common.getGroupCache().isAdministrator(Common.getAccountId())) {
      return;
    }
    final Set<Id> myGroups = myGroups();
    if (!canPerform(myGroups, p, ApprovalCategory.OWN, (short) 1)) {
      throw new Failure(new NoSuchEntityException());
    }
  }

  private List<Project> myOwnedProjects(final ReviewDb db) throws OrmException {
    if (Common.getGroupCache().isAdministrator(Common.getAccountId())) {
      return db.projects().all().toList();
    }

    final Set<AccountGroup.Id> myGroups = myGroups();
    final HashSet<Project.Id> projects = new HashSet<Project.Id>();
    for (final AccountGroup.Id groupId : myGroups) {
      for (final ProjectRight r : db.projectRights().byCategoryGroup(
          ApprovalCategory.OWN, groupId)) {
        projects.add(r.getProjectId());
      }
    }

    final ProjectCache projectCache = Common.getProjectCache();
    final List<Project> own = new ArrayList<Project>();
    for (Project.Id id : projects) {
      final ProjectCache.Entry cacheEntry = projectCache.get(id);
      if (canPerform(myGroups, cacheEntry, ApprovalCategory.OWN, (short) 1)) {
        own.add(cacheEntry.getProject());
      }
    }
    return own;
  }

  private Set<Id> myGroups() {
    return Common.getGroupCache().getEffectiveGroups(Common.getAccountId());
  }

  private static Set<Project.Id> ids(final Collection<Project> projectList) {
    final HashSet<Project.Id> r = new HashSet<Project.Id>();
    for (final Project project : projectList) {
      r.add(project.getId());
    }
    return r;
  }
}
