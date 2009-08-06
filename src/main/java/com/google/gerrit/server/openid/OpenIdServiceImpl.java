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

package com.google.gerrit.server.openid;

import com.google.gerrit.client.Link;
import com.google.gerrit.client.SignInDialog;
import com.google.gerrit.client.SignInDialog.Mode;
import com.google.gerrit.client.openid.DiscoveryResult;
import com.google.gerrit.client.openid.OpenIdService;
import com.google.gerrit.client.openid.OpenIdUtil;
import com.google.gerrit.client.reviewdb.Account;
import com.google.gerrit.client.reviewdb.AccountExternalId;
import com.google.gerrit.client.reviewdb.AccountExternalIdAccess;
import com.google.gerrit.client.reviewdb.ReviewDb;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.UrlEncoded;
import com.google.gerrit.server.account.AccountByEmailCache;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.config.AuthConfig;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.config.Nullable;
import com.google.gerrit.server.http.GerritCall;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwtorm.client.KeyUtil;
import com.google.gwtorm.client.OrmException;
import com.google.gwtorm.client.ResultSet;
import com.google.gwtorm.client.SchemaFactory;
import com.google.gwtorm.client.Transaction;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.constructs.blocking.CacheEntryFactory;
import net.sf.ehcache.constructs.blocking.SelfPopulatingCache;

import org.openid4java.consumer.ConsumerException;
import org.openid4java.consumer.ConsumerManager;
import org.openid4java.consumer.VerificationResult;
import org.openid4java.discovery.DiscoveryException;
import org.openid4java.discovery.DiscoveryInformation;
import org.openid4java.discovery.Identifier;
import org.openid4java.message.AuthRequest;
import org.openid4java.message.Message;
import org.openid4java.message.MessageException;
import org.openid4java.message.MessageExtension;
import org.openid4java.message.ParameterList;
import org.openid4java.message.ax.AxMessage;
import org.openid4java.message.ax.FetchRequest;
import org.openid4java.message.ax.FetchResponse;
import org.openid4java.message.sreg.SRegMessage;
import org.openid4java.message.sreg.SRegRequest;
import org.openid4java.message.sreg.SRegResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Singleton
class OpenIdServiceImpl implements OpenIdService {
  private static final Logger log =
      LoggerFactory.getLogger(OpenIdServiceImpl.class);

  static final String RETURN_URL = "OpenID";

  private static final String P_MODE = "gerrit.mode";
  private static final String P_TOKEN = "gerrit.token";
  private static final String P_REMEMBER = "gerrit.remember";
  private static final int LASTID_AGE = 365 * 24 * 60 * 60; // seconds

  private static final String OPENID_MODE = "openid.mode";
  private static final String OMODE_CANCEL = "cancel";

  private static final String SCHEMA_EMAIL =
      "http://schema.openid.net/contact/email";
  private static final String SCHEMA_FIRSTNAME =
      "http://schema.openid.net/namePerson/first";
  private static final String SCHEMA_LASTNAME =
      "http://schema.openid.net/namePerson/last";

  private final Provider<GerritCall> callFactory;
  private final Provider<IdentifiedUser> identifiedUser;
  private final AuthConfig authConfig;
  private final Provider<String> urlProvider;
  private final SchemaFactory<ReviewDb> schema;
  private final ConsumerManager manager;
  private final AccountByEmailCache byEmailCache;
  private final AccountCache byIdCache;
  private final SelfPopulatingCache discoveryCache;

  @Inject
  OpenIdServiceImpl(final Provider<GerritCall> cf,
      final Provider<IdentifiedUser> iu, final AuthConfig ac,
      @CanonicalWebUrl @Nullable final Provider<String> up,
      final AccountByEmailCache bec, final AccountCache bic,
      final CacheManager cacheMgr, final SchemaFactory<ReviewDb> sf)
      throws ConsumerException {
    callFactory = cf;
    identifiedUser = iu;
    authConfig = ac;
    urlProvider = up;
    schema = sf;
    byEmailCache = bec;
    byIdCache = bic;
    manager = new ConsumerManager();

    final Cache base = cacheMgr.getCache("openid");
    discoveryCache = new SelfPopulatingCache(base, new CacheEntryFactory() {
      public Object createEntry(final Object objKey) throws Exception {
        try {
          final List<?> list = manager.discover((String) objKey);
          return list != null && !list.isEmpty() ? list : null;
        } catch (DiscoveryException e) {
          return null;
        }
      }
    });
    cacheMgr.replaceCacheWithDecoratedCache(base, discoveryCache);
  }

  public void discover(final String openidIdentifier,
      final SignInDialog.Mode mode, final boolean remember,
      final String returnToken, final AsyncCallback<DiscoveryResult> callback) {
    final State state;
    state = init(openidIdentifier, mode, remember, returnToken);
    if (state == null) {
      callback.onSuccess(new DiscoveryResult(false));
      return;
    }

    final AuthRequest aReq;
    try {
      aReq = manager.authenticate(state.discovered, state.retTo.toString());
      aReq.setRealm(state.contextUrl);

      if (requestRegistration(aReq)) {
        final SRegRequest sregReq = SRegRequest.createFetchRequest();
        sregReq.addAttribute("fullname", true);
        sregReq.addAttribute("email", true);
        aReq.addExtension(sregReq);

        final FetchRequest fetch = FetchRequest.createFetchRequest();
        fetch.addAttribute("FirstName", SCHEMA_FIRSTNAME, true);
        fetch.addAttribute("LastName", SCHEMA_LASTNAME, true);
        fetch.addAttribute("Email", SCHEMA_EMAIL, true);
        aReq.addExtension(fetch);
      }
    } catch (MessageException e) {
      callback.onSuccess(new DiscoveryResult(false));
      return;
    } catch (ConsumerException e) {
      callback.onSuccess(new DiscoveryResult(false));
      return;
    }

    callback.onSuccess(new DiscoveryResult(true, aReq.getDestinationUrl(false),
        aReq.getParameterMap()));
  }

  private boolean requestRegistration(final AuthRequest aReq) {
    if (AuthRequest.SELECT_ID.equals(aReq.getIdentity())) {
      // We don't know anything about the identity, as the provider
      // will offer the user a way to indicate their identity. Skip
      // any database query operation and assume we must ask for the
      // registration information, in case the identity is new to us.
      //
      return true;
    }

    // We might already have this account on file. Look for it.
    //
    try {
      final ReviewDb db = schema.open();
      try {
        final ResultSet<AccountExternalId> ae =
            db.accountExternalIds().byExternal(aReq.getIdentity());
        if (ae.iterator().hasNext()) {
          // We already have it. Don't bother asking for the
          // registration information, we have what we need.
          //
          return false;
        }
      } finally {
        db.close();
      }
    } catch (OrmException e) {
      log.warn("Failed looking for existing account", e);
    }

    // We don't have this account on file, or our query failed. Assume
    // we should ask for registration information in case the account
    // turns out to be new.
    //
    return true;
  }

  /** Called by {@link OpenIdLoginServlet} doGet, doPost */
  void doAuth(final HttpServletRequest req, final HttpServletResponse rsp)
      throws Exception {
    if (false) {
      debugRequest(req);
    }

    callFactory.get().noCache();

    final String openidMode = req.getParameter(OPENID_MODE);
    if (OMODE_CANCEL.equals(openidMode)) {
      cancel(req, rsp);

    } else {
      // Process the authentication response.
      //
      final SignInDialog.Mode mode = signInMode(req);
      final String openidIdentifier = req.getParameter("openid.identity");
      final String returnToken = req.getParameter(P_TOKEN);
      final boolean remember = "1".equals(req.getParameter(P_REMEMBER));
      final State state;

      state = init(openidIdentifier, mode, remember, returnToken);
      if (state == null) {
        // Re-discovery must have failed, we can't run a login.
        //
        cancel(req, rsp);
        return;
      }

      final String returnTo = req.getParameter("openid.return_to");
      if (returnTo != null && returnTo.contains("openid.rpnonce=")) {
        // Some providers (claimid.com) seem to embed these request
        // parameters into our return_to URL, and then give us them
        // in the return_to request parameter. But not all.
        //
        state.retTo.put("openid.rpnonce", req.getParameter("openid.rpnonce"));
        state.retTo.put("openid.rpsig", req.getParameter("openid.rpsig"));
      }

      final VerificationResult result =
          manager.verify(state.retTo.toString(), new ParameterList(req
              .getParameterMap()), state.discovered);
      final Identifier user = result.getVerifiedId();

      if (user != null) {
        // Authentication was successful.
        //
        final Message authRsp = result.getAuthResponse();
        SRegResponse sregRsp = null;
        FetchResponse fetchRsp = null;

        if (authRsp.hasExtension(SRegMessage.OPENID_NS_SREG)) {
          final MessageExtension ext =
              authRsp.getExtension(SRegMessage.OPENID_NS_SREG);
          if (ext instanceof SRegResponse) {
            sregRsp = (SRegResponse) ext;
          }
        }

        if (authRsp.hasExtension(AxMessage.OPENID_NS_AX)) {
          final MessageExtension ext =
              authRsp.getExtension(AxMessage.OPENID_NS_AX);
          if (ext instanceof FetchResponse) {
            fetchRsp = (FetchResponse) ext;
          }
        }

        String fullname = null;
        String email = null;

        if (sregRsp != null) {
          fullname = sregRsp.getAttributeValue("fullname");
          email = sregRsp.getAttributeValue("email");

        } else if (fetchRsp != null) {
          final String firstName = fetchRsp.getAttributeValue("FirstName");
          final String lastName = fetchRsp.getAttributeValue("LastName");
          final StringBuilder n = new StringBuilder();
          if (firstName != null && firstName.length() > 0) {
            n.append(firstName);
          }
          if (lastName != null && lastName.length() > 0) {
            if (n.length() > 0) {
              n.append(' ');
            }
            n.append(lastName);
          }
          fullname = n.length() > 0 ? n.toString() : null;
          email = fetchRsp.getAttributeValue("Email");
        }

        initializeAccount(req, rsp, user, fullname, email);
      } else if ("Nonce verification failed.".equals(result.getStatusMsg())) {
        // We might be suffering from clock skew on this system.
        //
        log.error("OpenID failure: " + result.getStatusMsg()
            + "  Likely caused by clock skew on this server,"
            + " install/configure NTP.");
        cancelWithError(req, rsp, mode, result.getStatusMsg());
      } else if (result.getStatusMsg() != null) {
        // Authentication failed.
        //
        log.error("OpenID failure: " + result.getStatusMsg());
        cancelWithError(req, rsp, mode, result.getStatusMsg());
      } else {
        // Assume authentication was canceled.
        //
        cancel(req, rsp);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static void debugRequest(final HttpServletRequest req) {
    System.err.println(req.getMethod() + " /" + RETURN_URL);
    for (final String n : new TreeMap<String, Object>(req.getParameterMap())
        .keySet()) {
      for (final String v : req.getParameterValues(n)) {
        System.err.println("  " + n + "=" + v);
      }
    }
    System.err.println();
  }

  private void initializeAccount(final HttpServletRequest req,
      final HttpServletResponse rsp, final Identifier user,
      final String fullname, final String email) throws IOException {
    final SignInDialog.Mode mode = signInMode(req);
    Account account = null;
    boolean isNew = false;
    if (user != null) {
      try {
        final ReviewDb d = schema.open();
        try {
          switch (mode) {
            case SIGN_IN:
            case REGISTER: {
              SignInResult r = openAccount(d, user, fullname, email);
              if (r != null) {
                account = r.account;
                isNew = r.isNew;
              }
              break;
            }
            case LINK_IDENTIY:
              account = linkAccount(req, d, user, email);
              break;
          }
        } finally {
          d.close();
        }
      } catch (OrmException e) {
        log.error("Account lookup failed", e);
        account = null;
      }
    }

    if (account == null) {
      if (isSignIn(mode)) {
        callFactory.get().logout();
      }
      cancel(req, rsp);

    } else if (isSignIn(mode)) {
      final boolean remember = "1".equals(req.getParameter(P_REMEMBER));
      callFactory.get().setAccount(account.getId(), false);

      final Cookie lastId = new Cookie(OpenIdUtil.LASTID_COOKIE, "");
      lastId.setPath(req.getContextPath() + "/");
      if (remember) {
        lastId.setValue(user.getIdentifier());
        lastId.setMaxAge(LASTID_AGE);
      } else {
        lastId.setMaxAge(0);
      }
      rsp.addCookie(lastId);

      callback(isNew, req, rsp);

    } else {
      callback(isNew, req, rsp);
    }
  }

  private boolean isSignIn(final SignInDialog.Mode mode) {
    switch (mode) {
      case SIGN_IN:
      case REGISTER:
        return true;
      default:
        return false;
    }
  }

  static class SignInResult {
    final Account account;
    final boolean isNew;

    SignInResult(final Account a, final boolean n) {
      account = a;
      isNew = n;
    }
  }

  private SignInResult openAccount(final ReviewDb db, final Identifier user,
      final String fullname, final String email) throws OrmException {
    Account account;
    final AccountExternalIdAccess extAccess = db.accountExternalIds();
    AccountExternalId acctExt = lookup(extAccess, user.getIdentifier());

    if (acctExt == null && email != null
        && authConfig.isAllowGoogleAccountUpgrade() && isGoogleAccount(user)) {
      acctExt = lookupGoogleAccount(extAccess, email);
      if (acctExt != null) {
        // Legacy user from Gerrit 1? Attach the OpenID identity.
        //
        final AccountExternalId openidExt =
            new AccountExternalId(new AccountExternalId.Key(acctExt
                .getAccountId(), user.getIdentifier()));
        extAccess.insert(Collections.singleton(openidExt));
        acctExt = openidExt;
      }
    }

    if (acctExt != null) {
      // Existing user; double check the email is current.
      //
      if (email != null && !email.equals(acctExt.getEmailAddress())) {
        byEmailCache.evict(acctExt.getEmailAddress());
        byEmailCache.evict(email);
        acctExt.setEmailAddress(email);
      }
      acctExt.setLastUsedOn();
      extAccess.update(Collections.singleton(acctExt));
      account = byIdCache.get(acctExt.getAccountId()).getAccount();
    } else {
      account = null;
    }

    if (account == null) {
      // New user; create an account entity for them.
      //
      final Transaction txn = db.beginTransaction();

      account = new Account(new Account.Id(db.nextAccountId()));
      account.setFullName(fullname);
      account.setPreferredEmail(email);

      acctExt =
          new AccountExternalId(new AccountExternalId.Key(account.getId(), user
              .getIdentifier()));
      acctExt.setLastUsedOn();
      acctExt.setEmailAddress(email);

      db.accounts().insert(Collections.singleton(account), txn);
      extAccess.insert(Collections.singleton(acctExt), txn);
      txn.commit();
      byEmailCache.evict(email);
      return new SignInResult(account, true);
    }
    return account != null ? new SignInResult(account, false) : null;
  }

  private Account linkAccount(final HttpServletRequest req, final ReviewDb db,
      final Identifier user, final String curEmail) throws OrmException {
    final Account account = identifiedUser.get().getAccount();
    final AccountExternalId.Key idKey =
        new AccountExternalId.Key(account.getId(), user.getIdentifier());
    AccountExternalId id = db.accountExternalIds().get(idKey);
    if (id == null) {
      id = new AccountExternalId(idKey);
      id.setLastUsedOn();
      id.setEmailAddress(curEmail);
      db.accountExternalIds().insert(Collections.singleton(id));
      byEmailCache.evict(curEmail);
      byIdCache.evict(account.getId());
    } else {
      final String oldEmail = id.getEmailAddress();
      if (curEmail != null && !curEmail.equals(oldEmail)) {
        id.setEmailAddress(curEmail);
      }
      id.setLastUsedOn();
      db.accountExternalIds().update(Collections.singleton(id));
      if (curEmail != null && !curEmail.equals(oldEmail)) {
        byEmailCache.evict(oldEmail);
        byEmailCache.evict(curEmail);
        byIdCache.evict(account.getId());
      }
    }
    return account;
  }

  private static Mode signInMode(final HttpServletRequest req) {
    try {
      return SignInDialog.Mode.valueOf(req.getParameter(P_MODE));
    } catch (RuntimeException e) {
      return SignInDialog.Mode.SIGN_IN;
    }
  }

  private static AccountExternalId lookup(
      final AccountExternalIdAccess extAccess, final String id)
      throws OrmException {
    final List<AccountExternalId> extRes = extAccess.byExternal(id).toList();
    switch (extRes.size()) {
      case 0:
        return null;
      case 1:
        return extRes.get(0);
      default:
        throw new OrmException("More than one account matches: " + id);
    }
  }

  private static boolean isGoogleAccount(final Identifier user) {
    return user.getIdentifier().startsWith(OpenIdUtil.URL_GOOGLE + "?");
  }

  private static AccountExternalId lookupGoogleAccount(
      final AccountExternalIdAccess extAccess, final String email)
      throws OrmException {
    // We may have multiple records which match the email address, but
    // all under the same account. This happens when the user does a
    // login through different server hostnames, as Google issues
    // unique OpenID tokens per server.
    //
    // Match to an existing account only if there is exactly one record
    // for this email using the generic Google identity.
    //
    final List<AccountExternalId> m = new ArrayList<AccountExternalId>();
    for (final AccountExternalId e : extAccess.byEmailAddress(email)) {
      if (e.getExternalId().equals("Google Account " + email)) {
        m.add(e);
      }
    }
    return m.size() == 1 ? m.get(0) : null;
  }

  private void callback(final boolean isNew, final HttpServletRequest req,
      final HttpServletResponse rsp) throws IOException {
    String token = req.getParameter(P_TOKEN);
    if (token == null || token.isEmpty() || token.startsWith("SignInFailure,")) {
      token = Link.MINE;
    }

    final StringBuilder rdr = new StringBuilder();
    rdr.append(urlProvider.get());
    rdr.append('#');
    if (isNew) {
      rdr.append(Link.REGISTER);
      rdr.append(',');
    }
    rdr.append(token);
    rsp.sendRedirect(rdr.toString());
  }

  private void cancel(final HttpServletRequest req,
      final HttpServletResponse rsp) throws IOException {
    callback(false, req, rsp);
  }

  private void cancelWithError(final HttpServletRequest req,
      final HttpServletResponse rsp, final SignInDialog.Mode mode,
      final String errorDetail) throws IOException {
    final StringBuilder rdr = new StringBuilder();
    rdr.append(urlProvider.get());
    rdr.append('#');
    rdr.append("SignInFailure");
    rdr.append(',');
    rdr.append(mode.name());
    rdr.append(',');
    rdr.append(errorDetail != null ? KeyUtil.encode(errorDetail) : "");
    rsp.sendRedirect(rdr.toString());
  }

  private State init(final String openidIdentifier,
      final SignInDialog.Mode mode, final boolean remember,
      final String returnToken) {
    final Element serverCache = discoveryCache.get(openidIdentifier);
    if (serverCache == null) {
      return null;
    }

    final List<?> list = (List<?>) serverCache.getObjectValue();
    if (list == null || list.isEmpty()) {
      return null;
    }

    final String contextUrl = urlProvider.get();
    final DiscoveryInformation discovered = manager.associate(list);
    final UrlEncoded retTo = new UrlEncoded(contextUrl + RETURN_URL);
    retTo.put(P_MODE, mode.name());
    if (returnToken != null && returnToken.length() > 0) {
      retTo.put(P_TOKEN, returnToken);
    }
    if (remember) {
      retTo.put(P_REMEMBER, "1");
    }
    return new State(discovered, retTo, contextUrl);
  }

  private static class State {
    final DiscoveryInformation discovered;
    final UrlEncoded retTo;
    final String contextUrl;

    State(final DiscoveryInformation d, final UrlEncoded r, final String c) {
      discovered = d;
      retTo = r;
      contextUrl = c;
    }
  }
}
