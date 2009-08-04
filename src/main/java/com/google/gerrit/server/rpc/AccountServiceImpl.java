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

package com.google.gerrit.server.rpc;

import com.google.gerrit.client.account.AccountProjectWatchInfo;
import com.google.gerrit.client.account.AccountService;
import com.google.gerrit.client.account.AgreementInfo;
import com.google.gerrit.client.reviewdb.Account;
import com.google.gerrit.client.reviewdb.AccountGeneralPreferences;
import com.google.gerrit.client.reviewdb.AccountProjectWatch;
import com.google.gerrit.client.reviewdb.Project;
import com.google.gerrit.client.reviewdb.ReviewDb;
import com.google.gerrit.client.rpc.Common;
import com.google.gerrit.client.rpc.NoSuchEntityException;
import com.google.gerrit.server.BaseServiceImplementation;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwtjsonrpc.client.VoidResult;
import com.google.gwtorm.client.OrmException;
import com.google.gwtorm.client.SchemaFactory;
import com.google.gwtorm.client.Transaction;
import com.google.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

class AccountServiceImpl extends BaseServiceImplementation implements
    AccountService {
  private final ProjectCache projectCache;

  @Inject
  AccountServiceImpl(final SchemaFactory<ReviewDb> sf,
      final ProjectCache projectCache) {
    super(sf);
    this.projectCache = projectCache;
  }

  public void myAccount(final AsyncCallback<Account> callback) {
    run(callback, new Action<Account>() {
      public Account run(ReviewDb db) throws Failure {
        final Account a =
            Common.getAccountCache().get(Common.getAccountId(), db);
        if (a == null) {
          throw new Failure(new NoSuchEntityException());
        }
        return a;
      }
    });
  }

  public void changePreferences(final AccountGeneralPreferences pref,
      final AsyncCallback<VoidResult> callback) {
    run(callback, new Action<VoidResult>() {
      public VoidResult run(final ReviewDb db) throws OrmException, Failure {
        final Account a = db.accounts().get(Common.getAccountId());
        if (a == null) {
          throw new Failure(new NoSuchEntityException());
        }
        a.setGeneralPreferences(pref);
        db.accounts().update(Collections.singleton(a));
        Common.getAccountCache().invalidate(a.getId());
        return VoidResult.INSTANCE;
      }
    });
  }

  public void myProjectWatch(
      final AsyncCallback<List<AccountProjectWatchInfo>> callback) {
    run(callback, new Action<List<AccountProjectWatchInfo>>() {
      public List<AccountProjectWatchInfo> run(ReviewDb db) throws OrmException {
        final List<AccountProjectWatchInfo> r =
            new ArrayList<AccountProjectWatchInfo>();

        for (final AccountProjectWatch w : db.accountProjectWatches()
            .byAccount(Common.getAccountId()).toList()) {
          final ProjectState project = projectCache.get(w.getProjectId());
          if (project == null) {
            db.accountProjectWatches().delete(Collections.singleton(w));
            continue;
          }
          r.add(new AccountProjectWatchInfo(w, project.getProject()));
        }
        Collections.sort(r, new Comparator<AccountProjectWatchInfo>() {
          public int compare(final AccountProjectWatchInfo a,
              final AccountProjectWatchInfo b) {
            return a.getProject().getName().compareTo(b.getProject().getName());
          }
        });
        return r;
      }
    });
  }

  public void addProjectWatch(final String projectName,
      final AsyncCallback<AccountProjectWatchInfo> callback) {
    run(callback, new Action<AccountProjectWatchInfo>() {
      public AccountProjectWatchInfo run(ReviewDb db) throws OrmException,
          Failure {
        final ProjectState project =
            projectCache.get(new Project.NameKey(projectName));
        if (project == null) {
          throw new Failure(new NoSuchEntityException());
        }

        final AccountProjectWatch watch =
            new AccountProjectWatch(new AccountProjectWatch.Key(Common
                .getAccountId(), project.getProject().getId()));
        db.accountProjectWatches().insert(Collections.singleton(watch));
        return new AccountProjectWatchInfo(watch, project.getProject());
      }
    });
  }

  public void updateProjectWatch(final AccountProjectWatch watch,
      final AsyncCallback<VoidResult> callback) {
    if (!Common.getAccountId().equals(watch.getAccountId())) {
      callback.onFailure(new NoSuchEntityException());
      return;
    }

    run(callback, new Action<VoidResult>() {
      public VoidResult run(ReviewDb db) throws OrmException {
        db.accountProjectWatches().update(Collections.singleton(watch));
        return VoidResult.INSTANCE;
      }
    });
  }

  public void deleteProjectWatches(final Set<AccountProjectWatch.Key> keys,
      final AsyncCallback<VoidResult> callback) {
    run(callback, new Action<VoidResult>() {
      public VoidResult run(final ReviewDb db) throws OrmException, Failure {
        final Account.Id me = Common.getAccountId();
        for (final AccountProjectWatch.Key keyId : keys) {
          if (!me.equals(keyId.getParentKey()))
            throw new Failure(new NoSuchEntityException());
        }

        final List<AccountProjectWatch> k =
            db.accountProjectWatches().get(keys).toList();
        if (!k.isEmpty()) {
          final Transaction txn = db.beginTransaction();
          db.accountProjectWatches().delete(k, txn);
          txn.commit();
        }

        return VoidResult.INSTANCE;
      }
    });
  }

  public void myAgreements(final AsyncCallback<AgreementInfo> callback) {
    run(callback, new Action<AgreementInfo>() {
      public AgreementInfo run(final ReviewDb db) throws OrmException {
        final AgreementInfo i = new AgreementInfo();
        i.load(Common.getAccountId(), db);
        return i;
      }
    });
  }
}
