// Copyright (C) 2013 The Android Open Source Project
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

package com.google.gerrit.server.change;

import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;

import com.google.gerrit.common.data.PatchScript.FileMode;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.reviewdb.client.Patch;
import com.google.gerrit.server.FileTypeRegistry;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.io.OutputStream;

@Singleton
public class FileContentUtil {
  public static final String TEXT_X_GERRIT_COMMIT_MESSAGE = "text/x-gerrit-commit-message";
  private static final String X_GIT_SYMLINK = "x-git/symlink";
  private static final String X_GIT_GITLINK = "x-git/gitlink";
  private static final int MAX_SIZE = 5 << 20;

  private final GitRepositoryManager repoManager;
  private final FileTypeRegistry registry;

  @Inject
  FileContentUtil(GitRepositoryManager repoManager,
      FileTypeRegistry ftr) {
    this.repoManager = repoManager;
    this.registry = ftr;
  }

  public BinaryResult getContent(ProjectState project, ObjectId revstr,
      String path) throws ResourceNotFoundException, IOException {
    Repository repo = openRepository(project);
    try {
      RevWalk rw = new RevWalk(repo);
      try {
        RevCommit commit = rw.parseCommit(revstr);
        ObjectReader reader = rw.getObjectReader();
        TreeWalk tw = TreeWalk.forPath(reader, path, commit.getTree());
        if (tw == null) {
          throw new ResourceNotFoundException();
        }

        org.eclipse.jgit.lib.FileMode mode = tw.getFileMode(0);
        ObjectId id = tw.getObjectId(0);
        if (mode == org.eclipse.jgit.lib.FileMode.GITLINK) {
          return BinaryResult.create(id.name())
              .setContentType(X_GIT_GITLINK)
              .base64();
        }

        final ObjectLoader obj = repo.open(id, OBJ_BLOB);
        byte[] raw;
        try {
          raw = obj.getCachedBytes(MAX_SIZE);
        } catch (LargeObjectException e) {
          raw = null;
        }

        BinaryResult result;
        if (raw != null) {
          result = BinaryResult.create(raw);
        } else {
          result = asBinaryResult(obj);
        }

        String type;
        if (mode == org.eclipse.jgit.lib.FileMode.SYMLINK) {
          type = X_GIT_SYMLINK;
        } else {
          type = registry.getMimeType(path, raw).toString();
          type = resolveContentType(project, path, FileMode.FILE, type);
        }
        return result.setContentType(type).base64();
      } finally {
        rw.release();
      }
    } finally {
      repo.close();
    }
  }

  private static BinaryResult asBinaryResult(final ObjectLoader obj) {
    @SuppressWarnings("resource")
    BinaryResult result = new BinaryResult() {
      @Override
      public void writeTo(OutputStream os) throws IOException {
        obj.copyTo(os);
      }
    }.setContentLength(obj.getSize());
    return result;
  }

  public String getContentType(ProjectState project, ObjectId rev,
      String path) throws ResourceNotFoundException, IOException {
    Repository repo = openRepository(project);
    try {
      RevWalk rw = new RevWalk(repo);
      try {
        ObjectReader reader = rw.getObjectReader();
        RevCommit commit = rw.parseCommit(rev);
        TreeWalk tw = TreeWalk.forPath(reader, path, commit.getTree());
        if (tw == null) {
          throw new ResourceNotFoundException();
        }

        org.eclipse.jgit.lib.FileMode mode = tw.getFileMode(0);
        if (mode == org.eclipse.jgit.lib.FileMode.GITLINK) {
          return X_GIT_GITLINK;
        } else if (mode == org.eclipse.jgit.lib.FileMode.SYMLINK) {
          return X_GIT_SYMLINK;
        }

        ObjectLoader blob = reader.open(tw.getObjectId(0), OBJ_BLOB);
        byte[] raw;
        try {
          raw = blob.getCachedBytes(MAX_SIZE);
        } catch (LargeObjectException e) {
          raw = null;
        }
        String type = registry.getMimeType(path, raw).toString();
        return resolveContentType(project, path, FileMode.FILE, type);
      } finally {
        rw.release();
      }
    } finally {
      repo.close();
    }
  }

  public static String resolveContentType(ProjectState project, String path,
      FileMode fileMode, String mimeType) {
    switch (fileMode) {
      case FILE:
        if (Patch.COMMIT_MSG.equals(path)) {
          return TEXT_X_GERRIT_COMMIT_MESSAGE;
        }
        if (project != null) {
          for (ProjectState p : project.tree()) {
            String t = p.getConfig().getMimeTypes().getMimeType(path);
            if (t != null) {
              return t;
            }
          }
        }
        return mimeType;
      case GITLINK:
        return X_GIT_GITLINK;
      case SYMLINK:
        return X_GIT_SYMLINK;
      default:
        throw new IllegalStateException("file mode: " + fileMode);
    }
  }

  private Repository openRepository(ProjectState project)
      throws RepositoryNotFoundException, IOException {
    return repoManager.openRepository(project.getProject().getNameKey());
  }
}
