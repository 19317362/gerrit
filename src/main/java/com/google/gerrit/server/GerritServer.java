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

import com.google.gerrit.client.data.AccountCache;
import com.google.gerrit.client.data.ApprovalType;
import com.google.gerrit.client.data.GerritConfig;
import com.google.gerrit.client.data.GitwebLink;
import com.google.gerrit.client.data.GroupCache;
import com.google.gerrit.client.data.ProjectCache;
import com.google.gerrit.client.reviewdb.AccountGroup;
import com.google.gerrit.client.reviewdb.ApprovalCategory;
import com.google.gerrit.client.reviewdb.ApprovalCategoryValue;
import com.google.gerrit.client.reviewdb.Branch;
import com.google.gerrit.client.reviewdb.Change;
import com.google.gerrit.client.reviewdb.Project;
import com.google.gerrit.client.reviewdb.ProjectRight;
import com.google.gerrit.client.reviewdb.ReviewDb;
import com.google.gerrit.client.reviewdb.SchemaVersion;
import com.google.gerrit.client.reviewdb.SystemConfig;
import com.google.gerrit.client.reviewdb.TrustedExternalId;
import com.google.gerrit.client.reviewdb.SystemConfig.LoginType;
import com.google.gerrit.client.rpc.Common;
import com.google.gerrit.git.MergeQueue;
import com.google.gerrit.git.PushAllProjectsOp;
import com.google.gerrit.git.PushQueue;
import com.google.gerrit.git.WorkQueue;
import com.google.gerrit.server.mail.EmailException;
import com.google.gerrit.server.patch.DiffCacheEntryFactory;
import com.google.gerrit.server.ssh.SshKeyCacheEntryFactory;
import com.google.gerrit.server.workflow.NoOpFunction;
import com.google.gerrit.server.workflow.SubmitFunction;
import com.google.gwtjsonrpc.server.SignedToken;
import com.google.gwtjsonrpc.server.XsrfException;
import com.google.gwtorm.client.OrmException;
import com.google.gwtorm.client.Transaction;
import com.google.gwtorm.jdbc.Database;
import com.google.gwtorm.jdbc.SimpleDataSource;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.Configuration;
import net.sf.ehcache.config.DiskStoreConfiguration;
import net.sf.ehcache.constructs.blocking.SelfPopulatingCache;
import net.sf.ehcache.store.MemoryStoreEvictionPolicy;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.net.smtp.AuthSMTPClient;
import org.apache.commons.net.smtp.SMTPClient;
import org.apache.commons.net.smtp.SMTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spearce.jgit.errors.ConfigInvalidException;
import org.spearce.jgit.errors.RepositoryNotFoundException;
import org.spearce.jgit.lib.PersonIdent;
import org.spearce.jgit.lib.Repository;
import org.spearce.jgit.lib.RepositoryCache;
import org.spearce.jgit.lib.RepositoryConfig;
import org.spearce.jgit.lib.WindowCache;
import org.spearce.jgit.lib.WindowCacheConfig;
import org.spearce.jgit.lib.RepositoryCache.FileKey;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.http.HttpServletRequest;
import javax.sql.DataSource;

/** Global server-side state for Gerrit. */
public class GerritServer {
  private static final Logger log = LoggerFactory.getLogger(GerritServer.class);
  private static DataSource datasource;
  private static GerritServer impl;
  private static CacheManager cacheMgr;

  static void closeDataSource() {
    if (datasource != null) {
      try {
        try {
          Class.forName("com.mchange.v2.c3p0.DataSources").getMethod("destroy",
              DataSource.class).invoke(null, datasource);
        } catch (Throwable bad) {
          // Oh well, its not a c3p0 pooled connection. Too bad its
          // not standardized how "good applications cleanup".
        }
      } finally {
        datasource = null;
      }
    }

    if (cacheMgr != null) {
      try {
        cacheMgr.shutdown();
      } catch (Throwable bad) {
      } finally {
        cacheMgr = null;
      }
    }
  }

  /**
   * Obtain the singleton server instance for this web application.
   * 
   * @return the server instance. Never null.
   * @throws OrmException the database could not be configured. There is
   *         something wrong with the schema configuration in {@link ReviewDb}
   *         that must be addressed by a developer.
   * @throws XsrfException the XSRF support could not be correctly configured to
   *         protect the application against cross-site request forgery. The JVM
   *         is most likely lacking critical security algorithms.
   */
  public static synchronized GerritServer getInstance() throws OrmException,
      XsrfException {
    return getInstance(true);
  }

  public static synchronized GerritServer getInstance(final boolean startQueues)
      throws OrmException, XsrfException {
    if (impl == null) {
      try {
        impl = new GerritServer();
        if (startQueues) {
          impl.reloadSubmitQueue();
          if (PushQueue.isReplicationEnabled()) {
            WorkQueue.schedule(new PushAllProjectsOp(), 30, TimeUnit.SECONDS);
          }
        }
      } catch (OrmException e) {
        closeDataSource();
        log.error("GerritServer ORM is unavailable", e);
        throw e;
      } catch (XsrfException e) {
        closeDataSource();
        log.error("GerritServer XSRF support failed to initailize", e);
        throw e;
      }
    }
    return impl;
  }

  public static String serverUrl(final HttpServletRequest req) {
    // Assume this servlet is in the context with a simple name like "login"
    // and we were accessed without any path info. Clipping the last part of
    // the name from the URL should generate the web application's root path.
    //
    String uri = req.getRequestURL().toString();
    final int s = uri.lastIndexOf('/');
    if (s >= 0) {
      uri = uri.substring(0, s + 1);
    }
    final String sfx = "/gerrit/rpc/";
    if (uri.endsWith(sfx)) {
      // Nope, it was one of our RPC servlets. Drop the rpc too.
      //
      uri = uri.substring(0, uri.length() - (sfx.length() - 1));
    }
    return uri;
  }

  private final Database<ReviewDb> db;
  private final RepositoryConfig gerritConfigFile;
  private final int sessionAge;
  private SystemConfig sConfig;
  private final SignedToken xsrf;
  private final SignedToken account;
  private final SignedToken emailReg;
  private final File basepath;
  private final SelfPopulatingCache diffCache;
  private final SelfPopulatingCache sshKeysCache;

  private GerritServer() throws OrmException, XsrfException {
    db = createDatabase();
    loadSystemConfig();
    if (sConfig == null) {
      throw new OrmException("No " + SystemConfig.class.getName() + " found");
    }

    final File cfgLoc = new File(getSitePath(), "gerrit.config");
    gerritConfigFile = new RepositoryConfig(null, cfgLoc);
    try {
      gerritConfigFile.load();
    } catch (FileNotFoundException e) {
      log.info("No " + cfgLoc.getAbsolutePath() + "; assuming defaults");
    } catch (ConfigInvalidException e) {
      throw new OrmException("Cannot read " + cfgLoc.getAbsolutePath(), e);
    } catch (IOException e) {
      throw new OrmException("Cannot read " + cfgLoc.getAbsolutePath(), e);
    }
    reconfigureWindowCache();
    sessionAge = gerritConfigFile.getInt("auth", "maxsessionage", 12 * 60) * 60;

    xsrf = new SignedToken(getSessionAge(), sConfig.xsrfPrivateKey);

    final int accountCookieAge;
    switch (getLoginType()) {
      case HTTP:
        accountCookieAge = -1; // expire when the browser closes
        break;
      case OPENID:
      default:
        accountCookieAge = getSessionAge();
        break;
    }
    account = new SignedToken(accountCookieAge, sConfig.accountPrivateKey);
    emailReg = new SignedToken(5 * 24 * 60 * 60, sConfig.accountPrivateKey);

    final String basePath =
        getGerritConfig().getString("gerrit", null, "basepath");
    if (basePath != null) {
      File root = new File(basePath);
      if (!root.isAbsolute()) {
        root = new File(getSitePath(), basePath);
      }
      basepath = root;
    } else {
      basepath = null;
    }

    final ReviewDb c = db.open();
    try {
      loadGerritConfig(c);
    } finally {
      c.close();
    }

    Common.setSchemaFactory(db);
    Common.setProjectCache(new ProjectCache());
    Common.setAccountCache(new AccountCache());
    Common.setGroupCache(new GroupCache(sConfig));

    cacheMgr = new CacheManager(createCacheConfiguration());
    diffCache = startCacheDiff();
    sshKeysCache = startCacheSshKeys();
  }

  private Configuration createCacheConfiguration() {
    final Configuration mgrCfg = new Configuration();
    configureDiskStore(mgrCfg);
    configureDefaultCache(mgrCfg);

    if (getLoginType() == LoginType.OPENID) {
      final CacheConfiguration c;
      c = configureNamedCache(mgrCfg, "openid", false, 5);
      c.setTimeToLiveSeconds(c.getTimeToIdleSeconds());
      mgrCfg.addCache(c);
    }

    mgrCfg.addCache(configureNamedCache(mgrCfg, "diff", true, 0));
    mgrCfg.addCache(configureNamedCache(mgrCfg, "sshkeys", false, 0));
    return mgrCfg;
  }

  private void configureDiskStore(final Configuration mgrCfg) {
    String path = gerritConfigFile.getString("cache", null, "directory");
    if (path == null || path.length() == 0) {
      path = "disk_cache";
    }

    final File loc = new File(getSitePath(), path);
    if (loc.exists() || loc.mkdirs()) {
      if (loc.canWrite()) {
        final DiskStoreConfiguration c = new DiskStoreConfiguration();
        c.setPath(loc.getAbsolutePath());
        mgrCfg.addDiskStore(c);
        log.info("Enabling disk cache " + loc.getAbsolutePath());
      } else {
        log.warn("Can't write to disk cache: " + loc.getAbsolutePath());
      }
    } else {
      log.warn("Can't create disk cache: " + loc.getAbsolutePath());
    }
  }

  private void configureDefaultCache(final Configuration mgrCfg) {
    final RepositoryConfig i = gerritConfigFile;
    final CacheConfiguration c = new CacheConfiguration();

    c.setMaxElementsInMemory(i.getInt("cache", "memorylimit", 1024));
    c.setMemoryStoreEvictionPolicyFromObject(MemoryStoreEvictionPolicy.LFU);

    c.setTimeToLiveSeconds(0);
    final int oneday = 24 * 60;
    c.setTimeToIdleSeconds(i.getInt("cache", "maxage", 3 * 30 * oneday) * 60);
    c.setEternal(c.getTimeToIdleSeconds() == 0);

    if (mgrCfg.getDiskStoreConfiguration() != null) {
      c.setMaxElementsOnDisk(i.getInt("cache", "disklimit", 16384));
      c.setOverflowToDisk(false);
      c.setDiskPersistent(false);

      int diskbuffer = i.getInt("cache", "diskbuffer", 5 * 1024 * 1024);
      diskbuffer /= 1024 * 1024;
      c.setDiskSpoolBufferSizeMB(Math.max(1, diskbuffer));
      c.setDiskExpiryThreadIntervalSeconds(60 * 60);
    }

    mgrCfg.setDefaultCacheConfiguration(c);
  }

  private CacheConfiguration configureNamedCache(final Configuration mgrCfg,
      final String name, final boolean disk, final int defaultAge) {
    final RepositoryConfig i = gerritConfigFile;
    final CacheConfiguration def = mgrCfg.getDefaultCacheConfiguration();
    final CacheConfiguration cfg;
    try {
      cfg = def.clone();
    } catch (CloneNotSupportedException e) {
      throw new RuntimeException("Cannot configure cache " + name, e);
    }
    cfg.setName(name);

    cfg.setMaxElementsInMemory(i.getInt("cache", name, "memorylimit", def
        .getMaxElementsInMemory()));

    cfg.setTimeToIdleSeconds(i.getInt("cache", name, "maxage", defaultAge > 0
        ? defaultAge : (int) (def.getTimeToIdleSeconds() / 60)) * 60);
    cfg.setEternal(cfg.getTimeToIdleSeconds() == 0);

    if (disk && mgrCfg.getDiskStoreConfiguration() != null) {
      cfg.setMaxElementsOnDisk(i.getInt("cache", name, "disklimit", def
          .getMaxElementsOnDisk()));

      final int m = 1024 * 1024;
      final int diskbuffer =
          i.getInt("cache", name, "diskbuffer", def.getDiskSpoolBufferSizeMB()
              * m)
              / m;
      cfg.setDiskSpoolBufferSizeMB(Math.max(1, diskbuffer));
      cfg.setOverflowToDisk(true);
      cfg.setDiskPersistent(true);
    }

    return cfg;
  }

  private SelfPopulatingCache startCacheDiff() {
    final Cache dc = cacheMgr.getCache("diff");
    final SelfPopulatingCache r;

    r = new SelfPopulatingCache(dc, new DiffCacheEntryFactory(this));
    cacheMgr.replaceCacheWithDecoratedCache(dc, r);
    return r;
  }

  private SelfPopulatingCache startCacheSshKeys() {
    final Cache dc = cacheMgr.getCache("sshkeys");
    final SelfPopulatingCache r;

    r = new SelfPopulatingCache(dc, new SshKeyCacheEntryFactory());
    cacheMgr.replaceCacheWithDecoratedCache(dc, r);
    return r;
  }

  public static Database<ReviewDb> createDatabase() throws OrmException {
    final String dsName = "java:comp/env/jdbc/ReviewDb";
    try {
      datasource = (DataSource) new InitialContext().lookup(dsName);
    } catch (NamingException namingErr) {
      final Properties p = readGerritDataSource();
      if (p == null) {
        throw new OrmException("Initialization error:\n" + "  * No DataSource "
            + dsName + "\n" + "  * No -DGerritServer=GerritServer.properties"
            + " on Java command line", namingErr);
      }

      try {
        datasource = new SimpleDataSource(p);
      } catch (SQLException se) {
        throw new OrmException("Database unavailable", se);
      }
    }
    return new Database<ReviewDb>(datasource, ReviewDb.class);
  }

  private static Properties readGerritDataSource() throws OrmException {
    final Properties srvprop = new Properties();
    String name = System.getProperty("GerritServer");
    if (name == null) {
      name = "GerritServer.properties";
    }
    try {
      final InputStream in = new FileInputStream(name);
      try {
        srvprop.load(in);
      } finally {
        in.close();
      }
    } catch (IOException e) {
      throw new OrmException("Cannot read " + name, e);
    }

    final Properties dbprop = new Properties();
    for (final Map.Entry<Object, Object> e : srvprop.entrySet()) {
      final String key = (String) e.getKey();
      if (key.startsWith("database.")) {
        dbprop.put(key.substring("database.".length()), e.getValue());
      }
    }
    return dbprop;
  }

  private void initSystemConfig(final ReviewDb c) throws OrmException {
    final AccountGroup admin =
        new AccountGroup(new AccountGroup.NameKey("Administrators"),
            new AccountGroup.Id(c.nextAccountGroupId()));
    admin.setDescription("Gerrit Site Administrators");
    c.accountGroups().insert(Collections.singleton(admin));

    final AccountGroup anonymous =
        new AccountGroup(new AccountGroup.NameKey("Anonymous Users"),
            new AccountGroup.Id(c.nextAccountGroupId()));
    anonymous.setDescription("Any user, signed-in or not");
    anonymous.setOwnerGroupId(admin.getId());
    anonymous.setAutomaticMembership(true);
    c.accountGroups().insert(Collections.singleton(anonymous));

    final AccountGroup registered =
        new AccountGroup(new AccountGroup.NameKey("Registered Users"),
            new AccountGroup.Id(c.nextAccountGroupId()));
    registered.setDescription("Any signed-in user");
    registered.setOwnerGroupId(admin.getId());
    registered.setAutomaticMembership(true);
    c.accountGroups().insert(Collections.singleton(registered));

    final SystemConfig s = SystemConfig.create();
    s.xsrfPrivateKey = SignedToken.generateRandomKey();
    s.accountPrivateKey = SignedToken.generateRandomKey();
    s.adminGroupId = admin.getId();
    s.anonymousGroupId = anonymous.getId();
    s.registeredGroupId = registered.getId();
    c.systemConfig().insert(Collections.singleton(s));

    // By default with OpenID trust any http:// or https:// provider
    //
    initTrustedExternalId(c, "http://");
    initTrustedExternalId(c, "https://");
    initTrustedExternalId(c, "https://www.google.com/accounts/o8/id?id=");
  }

  private void initTrustedExternalId(final ReviewDb c, final String re)
      throws OrmException {
    c.trustedExternalIds().insert(
        Collections.singleton(new TrustedExternalId(new TrustedExternalId.Key(
            re))));
  }

  private void initWildCardProject(final ReviewDb c) throws OrmException {
    final Project proj;

    proj =
        new Project(new Project.NameKey("-- All Projects --"),
            ProjectRight.WILD_PROJECT);
    proj.setDescription("Rights inherited by all other projects");
    proj.setUseContributorAgreements(false);
    c.projects().insert(Collections.singleton(proj));
  }

  private void initVerifiedCategory(final ReviewDb c) throws OrmException {
    final Transaction txn = c.beginTransaction();
    final ApprovalCategory cat;
    final ArrayList<ApprovalCategoryValue> vals;

    cat = new ApprovalCategory(new ApprovalCategory.Id("VRIF"), "Verified");
    cat.setPosition((short) 0);
    vals = new ArrayList<ApprovalCategoryValue>();
    vals.add(value(cat, 1, "Verified"));
    vals.add(value(cat, 0, "No score"));
    vals.add(value(cat, -1, "Fails"));
    c.approvalCategories().insert(Collections.singleton(cat), txn);
    c.approvalCategoryValues().insert(vals, txn);
    txn.commit();
  }

  private void initCodeReviewCategory(final ReviewDb c) throws OrmException {
    final Transaction txn = c.beginTransaction();
    final ApprovalCategory cat;
    final ArrayList<ApprovalCategoryValue> vals;

    cat = new ApprovalCategory(new ApprovalCategory.Id("CRVW"), "Code Review");
    cat.setPosition((short) 1);
    vals = new ArrayList<ApprovalCategoryValue>();
    vals.add(value(cat, 2, "Looks good to me, approved"));
    vals.add(value(cat, 1, "Looks good to me, but someone else must approve"));
    vals.add(value(cat, 0, "No score"));
    vals.add(value(cat, -1, "I would prefer that you didn't submit this"));
    vals.add(value(cat, -2, "Do not submit"));
    c.approvalCategories().insert(Collections.singleton(cat), txn);
    c.approvalCategoryValues().insert(vals, txn);
    txn.commit();

    final ProjectRight approve =
        new ProjectRight(new ProjectRight.Key(ProjectRight.WILD_PROJECT, cat
            .getId(), sConfig.registeredGroupId));
    approve.setMaxValue((short) 1);
    approve.setMinValue((short) -1);
    c.projectRights().insert(Collections.singleton(approve));
  }

  private void initOwnerCategory(final ReviewDb c) throws OrmException {
    final Transaction txn = c.beginTransaction();
    final ApprovalCategory cat;
    final ArrayList<ApprovalCategoryValue> vals;

    cat = new ApprovalCategory(ApprovalCategory.OWN, "Owner");
    cat.setPosition((short) -1);
    cat.setFunctionName(NoOpFunction.NAME);
    vals = new ArrayList<ApprovalCategoryValue>();
    vals.add(value(cat, 1, "Administer All Settings"));
    c.approvalCategories().insert(Collections.singleton(cat), txn);
    c.approvalCategoryValues().insert(vals, txn);
    txn.commit();
  }

  private void initReadCategory(final ReviewDb c) throws OrmException {
    final Transaction txn = c.beginTransaction();
    final ApprovalCategory cat;
    final ArrayList<ApprovalCategoryValue> vals;

    cat = new ApprovalCategory(ApprovalCategory.READ, "Read Access");
    cat.setPosition((short) -1);
    cat.setFunctionName(NoOpFunction.NAME);
    vals = new ArrayList<ApprovalCategoryValue>();
    vals.add(value(cat, 1, "Read access"));
    vals.add(value(cat, -1, "No access"));
    c.approvalCategories().insert(Collections.singleton(cat), txn);
    c.approvalCategoryValues().insert(vals, txn);
    txn.commit();
    {
      final ProjectRight read =
          new ProjectRight(new ProjectRight.Key(ProjectRight.WILD_PROJECT, cat
              .getId(), sConfig.anonymousGroupId));
      read.setMaxValue((short) 1);
      read.setMinValue((short) 1);
      c.projectRights().insert(Collections.singleton(read));
    }
    {
      final ProjectRight read =
          new ProjectRight(new ProjectRight.Key(ProjectRight.WILD_PROJECT, cat
              .getId(), sConfig.adminGroupId));
      read.setMaxValue((short) 1);
      read.setMinValue((short) 1);
      c.projectRights().insert(Collections.singleton(read));
    }
  }

  private void initSubmitCategory(final ReviewDb c) throws OrmException {
    final Transaction txn = c.beginTransaction();
    final ApprovalCategory cat;
    final ArrayList<ApprovalCategoryValue> vals;

    cat = new ApprovalCategory(ApprovalCategory.SUBMIT, "Submit");
    cat.setPosition((short) -1);
    cat.setFunctionName(SubmitFunction.NAME);
    vals = new ArrayList<ApprovalCategoryValue>();
    vals.add(value(cat, 1, "Submit"));
    c.approvalCategories().insert(Collections.singleton(cat), txn);
    c.approvalCategoryValues().insert(vals, txn);
    txn.commit();
  }

  private void initPushTagCategory(final ReviewDb c) throws OrmException {
    final Transaction txn = c.beginTransaction();
    final ApprovalCategory cat;
    final ArrayList<ApprovalCategoryValue> vals;

    cat = new ApprovalCategory(ApprovalCategory.PUSH_TAG, "Push Annotated Tag");
    cat.setPosition((short) -1);
    cat.setFunctionName(NoOpFunction.NAME);
    vals = new ArrayList<ApprovalCategoryValue>();
    vals.add(value(cat, ApprovalCategory.PUSH_TAG_SIGNED, "Create Signed Tag"));
    vals.add(value(cat, ApprovalCategory.PUSH_TAG_ANNOTATED,
        "Create Annotated Tag"));
    vals.add(value(cat, ApprovalCategory.PUSH_TAG_ANY, "Create Any Tag"));
    c.approvalCategories().insert(Collections.singleton(cat), txn);
    c.approvalCategoryValues().insert(vals, txn);
    txn.commit();
  }

  private void initPushUpdateBranchCategory(final ReviewDb c)
      throws OrmException {
    final Transaction txn = c.beginTransaction();
    final ApprovalCategory cat;
    final ArrayList<ApprovalCategoryValue> vals;

    cat = new ApprovalCategory(ApprovalCategory.PUSH_HEAD, "Push Branch");
    cat.setPosition((short) -1);
    cat.setFunctionName(NoOpFunction.NAME);
    vals = new ArrayList<ApprovalCategoryValue>();
    vals.add(value(cat, ApprovalCategory.PUSH_HEAD_UPDATE, "Update Branch"));
    vals.add(value(cat, ApprovalCategory.PUSH_HEAD_CREATE, "Create Branch"));
    vals.add(value(cat, ApprovalCategory.PUSH_HEAD_REPLACE,
        "Force Push Branch; Delete Branch"));
    c.approvalCategories().insert(Collections.singleton(cat), txn);
    c.approvalCategoryValues().insert(vals, txn);
    txn.commit();
  }

  private static ApprovalCategoryValue value(final ApprovalCategory cat,
      final int value, final String name) {
    return new ApprovalCategoryValue(new ApprovalCategoryValue.Id(cat.getId(),
        (short) value), name);
  }

  private void loadSystemConfig() throws OrmException {
    final ReviewDb c = db.open();
    try {
      SchemaVersion sVer;
      try {
        sVer = c.schemaVersion().get(new SchemaVersion.Key());
      } catch (OrmException e) {
        // Assume the schema doesn't exist.
        //
        sVer = null;
      }

      if (sVer == null) {
        // Assume the schema is empty and populate it.
        //
        c.createSchema();
        sVer = SchemaVersion.create();
        sVer.versionNbr = ReviewDb.VERSION;
        c.schemaVersion().insert(Collections.singleton(sVer));

        initSystemConfig(c);
        sConfig = c.systemConfig().get(new SystemConfig.Key());
        initOwnerCategory(c);
        initReadCategory(c);
        initVerifiedCategory(c);
        initCodeReviewCategory(c);
        initSubmitCategory(c);
        initPushTagCategory(c);
        initPushUpdateBranchCategory(c);
        initWildCardProject(c);
      }

      if (sVer.versionNbr == 2) {
        initPushTagCategory(c);
        initPushUpdateBranchCategory(c);

        sVer.versionNbr = 3;
        c.schemaVersion().update(Collections.singleton(sVer));
      }

      if (sVer.versionNbr == ReviewDb.VERSION) {
        final List<SystemConfig> all = c.systemConfig().all().toList();
        switch (all.size()) {
          case 0:
            throw new OrmException("system_config table is empty");
          case 1:
            sConfig = all.get(0);
            break;
          default:
            throw new OrmException("system_config must have exactly 1 row;"
                + " found " + all.size() + " rows instead");
        }

      } else {
        throw new OrmException("Unsupported schema version " + sVer.versionNbr
            + "; expected schema version " + ReviewDb.VERSION);
      }
    } finally {
      c.close();
    }
  }

  private void loadGerritConfig(final ReviewDb db) throws OrmException {
    final GerritConfig r = new GerritConfig();
    r.setCanonicalUrl(getCanonicalURL());
    r.setUseContributorAgreements(getGerritConfig().getBoolean("auth",
        "contributoragreements", false));
    r.setGitDaemonUrl(getGerritConfig().getString("gerrit", null,
        "canonicalgiturl"));
    r.setUseRepoDownload(getGerritConfig().getBoolean("repo", null,
        "showdownloadcommand", false));
    r.setUseContactInfo(getContactStoreURL() != null);
    r.setAllowRegisterNewEmail(isOutgoingMailEnabled());
    r.setLoginType(getLoginType());

    final String gitwebUrl = getGerritConfig().getString("gitweb", null, "url");
    if (gitwebUrl != null) {
      r.setGitwebLink(new GitwebLink(gitwebUrl));
    }

    for (final ApprovalCategory c : db.approvalCategories().all()) {
      r.add(new ApprovalType(c, db.approvalCategoryValues().byCategory(
          c.getId()).toList()));
    }

    Common.setGerritConfig(r);
  }

  public boolean isOutgoingMailEnabled() {
    return getGerritConfig().getBoolean("sendemail", null, "enable", true);
  }

  public SMTPClient createOutgoingMail() throws EmailException {
    if (!isOutgoingMailEnabled()) {
      throw new EmailException("Sending email is disabled");
    }

    final RepositoryConfig cfg = getGerritConfig();
    String smtpHost = cfg.getString("sendemail", null, "smtpserver");
    if (smtpHost == null) {
      smtpHost = "127.0.0.1";
    }
    int smtpPort = cfg.getInt("sendemail", null, "smtpserverport", 25);

    String smtpUser = cfg.getString("sendemail", null, "smtpuser");
    String smtpPass = cfg.getString("sendemail", null, "smtpuserpass");

    final AuthSMTPClient client = new AuthSMTPClient("UTF-8");
    client.setAllowRcpt(cfg.getStringList("sendemail", null, "allowrcpt"));
    try {
      client.connect(smtpHost, smtpPort);
      if (!SMTPReply.isPositiveCompletion(client.getReplyCode())) {
        throw new EmailException("SMTP server rejected connection");
      }
      if (!client.login()) {
        String e = client.getReplyString();
        throw new EmailException("SMTP server rejected login: " + e);
      }
      if (smtpUser != null && !client.auth(smtpUser, smtpPass)) {
        String e = client.getReplyString();
        throw new EmailException("SMTP server rejected auth: " + e);
      }
    } catch (IOException e) {
      if (client.isConnected()) {
        try {
          client.disconnect();
        } catch (IOException e2) {
        }
      }
      throw new EmailException(e.getMessage(), e);
    } catch (EmailException e) {
      if (client.isConnected()) {
        try {
          client.disconnect();
        } catch (IOException e2) {
        }
      }
      throw e;
    }
    return client;
  }

  private void reconfigureWindowCache() {
    final WindowCacheConfig c = new WindowCacheConfig();
    c.fromConfig(gerritConfigFile);
    WindowCache.reconfigure(c);
  }

  private void reloadSubmitQueue() {
    WorkQueue.schedule(new Runnable() {
      public void run() {
        final HashSet<Branch.NameKey> pending = new HashSet<Branch.NameKey>();
        try {
          final ReviewDb c = db.open();
          try {
            for (final Change change : c.changes().allSubmitted()) {
              pending.add(change.getDest());
            }
          } finally {
            c.close();
          }
        } catch (OrmException e) {
          log.error("Cannot reload MergeQueue", e);
        }

        for (final Branch.NameKey branch : pending) {
          MergeQueue.schedule(branch);
        }
      }

      @Override
      public String toString() {
        return "Reload Submit Queue";
      }
    }, 15, TimeUnit.SECONDS);
  }

  /** Time (in seconds) that user sessions stay "signed in". */
  public int getSessionAge() {
    return sessionAge;
  }

  /** Get the signature support used to protect against XSRF attacks. */
  public SignedToken getXsrfToken() {
    return xsrf;
  }

  /** Get the signature support used to protect user identity cookies. */
  public SignedToken getAccountToken() {
    return account;
  }

  /** Get the signature used for email registration/validation links. */
  public SignedToken getEmailRegistrationToken() {
    return emailReg;
  }

  private SystemConfig.LoginType getLoginType() {
    String type = getGerritConfig().getString("auth", null, "type");
    if (type == null) {
      return SystemConfig.LoginType.OPENID;
    }
    for (SystemConfig.LoginType t : SystemConfig.LoginType.values()) {
      if (type.equalsIgnoreCase(t.name())) {
        return t;
      }
    }
    throw new IllegalStateException("Unsupported auth.type: " + type);
  }

  public String getLoginHttpHeader() {
    return getGerritConfig().getString("auth", null, "httpheader");
  }

  public String getEmailFormat() {
    return getGerritConfig().getString("auth", null, "emailformat");
  }

  public String getContactStoreURL() {
    return getGerritConfig().getString("contactstore", null, "url");
  }

  public String getContactStoreAPPSEC() {
    return getGerritConfig().getString("contactstore", null, "appsec");
  }

  /** A binary string key to encrypt cookies related to account data. */
  public String getAccountCookieKey() {
    byte[] r = new byte[sConfig.accountPrivateKey.length()];
    for (int k = r.length - 1; k >= 0; k--) {
      r[k] = (byte) sConfig.accountPrivateKey.charAt(k);
    }
    r = Base64.decodeBase64(r);
    final StringBuilder b = new StringBuilder();
    for (int i = 0; i < r.length; i++) {
      b.append((char) r[i]);
    }
    return b.toString();
  }

  /** Local filesystem location of header/footer/CSS configuration files. */
  public File getSitePath() {
    return sConfig.sitePath != null ? new File(sConfig.sitePath) : null;
  }

  /** Optional canonical URL for this application. */
  public String getCanonicalURL() {
    String u = getGerritConfig().getString("gerrit", null, "canonicalweburl");
    if (u != null && !u.endsWith("/")) {
      u += "/";
    }
    return u;
  }

  /** Get the parsed <code>$site_path/gerrit.config</code> file. */
  public RepositoryConfig getGerritConfig() {
    return gerritConfigFile;
  }

  /**
   * Get (or open) a repository by name.
   * 
   * @param name the repository name, relative to the base directory.
   * @return the cached Repository instance. Caller must call {@code close()}
   *         when done to decrement the resource handle.
   * @throws RepositoryNotFoundException the name does not denote an existing
   *         repository, or the name cannot be read as a repository.
   */
  public Repository openRepository(String name)
      throws RepositoryNotFoundException {
    if (basepath == null) {
      throw new RepositoryNotFoundException("No gerrit.basepath configured");
    }

    if (isUnreasonableName(name)) {
      throw new RepositoryNotFoundException("Invalid name: " + name);
    }

    try {
      final FileKey loc = FileKey.lenient(new File(basepath, name));
      return RepositoryCache.open(loc);
    } catch (IOException e1) {
      final RepositoryNotFoundException e2;
      e2 = new RepositoryNotFoundException("Cannot open repository " + name);
      e2.initCause(e1);
      throw e2;
    }
  }

  private boolean isUnreasonableName(final String name) {
    if (name.length() == 0) return true; // no empty paths

    if (name.indexOf('\\') >= 0) return true; // no windows/dos stlye paths
    if (name.charAt(0) == '/') return true; // no absolute paths
    if (new File(name).isAbsolute()) return true; // no absolute paths

    if (name.startsWith("../")) return true; // no "l../etc/passwd"
    if (name.contains("/../")) return true; // no "foo/../etc/passwd"
    if (name.contains("/./")) return true; // "foo/./foo" is insane to ask
    if (name.contains("//")) return true; // windows UNC path can be "//..."

    return false; // is a reasonable name
  }

  /** Get all registered caches. */
  public Ehcache[] getAllCaches() {
    final String[] cacheNames = cacheMgr.getCacheNames();
    Arrays.sort(cacheNames);
    final Ehcache[] r = new Ehcache[cacheNames.length];
    for (int i = 0; i < cacheNames.length; i++) {
      r[i] = cacheMgr.getEhcache(cacheNames[i]);
    }
    return r;
  }

  /** Get any existing cache by name. */
  public Cache getCache(final String name) {
    return cacheMgr.getCache(name);
  }

  /** Get the self-populating cache of DiffCacheContent entities. */
  public SelfPopulatingCache getDiffCache() {
    return diffCache;
  }

  /** Get the self-populating cache of user SSH keys. */
  public SelfPopulatingCache getSshKeysCache() {
    return sshKeysCache;
  }

  /** Get a new identity representing this Gerrit server in Git. */
  public PersonIdent newGerritPersonIdent() {
    String name = getGerritConfig().getString("user", null, "name");
    if (name == null) {
      name = "Gerrit Code Review";
    }
    String email = getGerritConfig().getCommitterEmail();
    if (email == null || email.length() == 0) {
      email = "gerrit@localhost";
    }
    return new PersonIdent(name, email);
  }

  public boolean isAllowGoogleAccountUpgrade() {
    return getGerritConfig().getBoolean("auth", "allowgoogleaccountupgrade",
        false);
  }
}
