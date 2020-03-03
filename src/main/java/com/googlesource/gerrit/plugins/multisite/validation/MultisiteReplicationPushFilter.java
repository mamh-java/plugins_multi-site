// Copyright (C) 2019 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.multisite.validation;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.multisite.SharedRefDatabaseWrapper;
import com.googlesource.gerrit.plugins.multisite.validation.dfsrefdb.SharedLockException;
import com.googlesource.gerrit.plugins.multisite.validation.dfsrefdb.SharedRefDatabase;
import com.googlesource.gerrit.plugins.replication.ReplicationPushFilter;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class MultisiteReplicationPushFilter implements ReplicationPushFilter {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String REF_META_SUFFIX = "/meta";
  public static final int MIN_WAIT_BEFORE_RELOAD_LOCAL_VERSION_MS = 1000;
  public static final int RANDOM_WAIT_BEFORE_RELOAD_LOCAL_VERSION_MS = 1000;

  static final String REPLICATION_LOG_NAME = "replication_log";
  static final Logger repLog = LoggerFactory.getLogger(REPLICATION_LOG_NAME);

  private final SharedRefDatabaseWrapper sharedRefDb;
  private final GitRepositoryManager gitRepositoryManager;

  @Inject
  public MultisiteReplicationPushFilter(
      SharedRefDatabaseWrapper sharedRefDb, GitRepositoryManager gitRepositoryManager) {
    this.sharedRefDb = sharedRefDb;
    this.gitRepositoryManager = gitRepositoryManager;
  }

  @Override
  public List<RemoteRefUpdate> filter(String projectName, List<RemoteRefUpdate> remoteUpdatesList) {
    Set<String> outdatedChanges = new HashSet<>();
    try (Repository repository =
        gitRepositoryManager.openRepository(Project.NameKey.parse(projectName))) {
      List<RemoteRefUpdate> filteredRefUpdates =
          remoteUpdatesList.stream()
              .filter(
                  refUpdate -> {
                    String ref = refUpdate.getSrcRef();
                    try {
                      if (sharedRefDb.isUpToDate(
                          projectName, SharedRefDatabase.newRef(ref, refUpdate.getNewObjectId()))) {
                        return true;
                      }
                      // The ref coming from the event might be old compared to the local version.
                      // Valid refs won't be replicated because of this misalignment.
                      // Reading the local ref and re-trying the comparison, after a short sleep,
                      // could mitigate the issue.
                      int waitBeforeReloadLocalVersionMs =
                          MIN_WAIT_BEFORE_RELOAD_LOCAL_VERSION_MS
                              + new Random().nextInt(RANDOM_WAIT_BEFORE_RELOAD_LOCAL_VERSION_MS);
                      repLog.debug(
                          String.format(
                              "'%s' is not up-to-date for project '%s' [local='%s']. Reload local ref in '%d ms' and re-check",
                              ref,
                              projectName,
                              refUpdate.getNewObjectId(),
                              waitBeforeReloadLocalVersionMs));
                      Thread.sleep(waitBeforeReloadLocalVersionMs);
                      Optional<ObjectId> objectIdVersion =
                          getProjectLocalObjectIdVersion(repository, projectName, ref);
                      if (objectIdVersion.isPresent()
                          && sharedRefDb.isUpToDate(
                              projectName,
                              new ObjectIdRef.Unpeeled(
                                  Ref.Storage.NETWORK, ref, objectIdVersion.get()))) {
                        repLog.debug("{} is up-to-date after retrying", objectIdVersion);
                        return true;
                      }
                      repLog.warn(
                          "{} is not up-to-date with the shared-refdb and thus will NOT BE replicated",
                          refUpdate);
                    } catch (SharedLockException e) {
                      repLog.warn(
                          "{} is locked on shared-refdb and thus will NOT BE replicated",
                          refUpdate);
                    } catch (InterruptedException ie) {
                      String message =
                          String.format(
                              "Error while waiting for next check for '%s', ref '%s'",
                              projectName, ref);
                      repLog.error(message);
                      logger.atSevere().withCause(ie).log(message);
                      return false;
                    }
                    if (ref.endsWith(REF_META_SUFFIX)) {
                      outdatedChanges.add(getRootChangeRefPrefix(ref));
                    }
                    return false;
                  })
              .collect(Collectors.toList());

      return filteredRefUpdates.stream()
          .filter(
              refUpdate -> {
                if (outdatedChanges.contains(changePrefix(refUpdate.getSrcRef()))) {
                  repLog.warn(
                      "{} belongs to an outdated /meta ref and thus will NOT BE replicated",
                      refUpdate);
                  return false;
                }
                return true;
              })
          .collect(Collectors.toList());

    } catch (IOException ioe) {
      String message = String.format("Error while opening project: '%s'", projectName);
      repLog.error(message);
      logger.atSevere().withCause(ioe).log(message);
      return Collections.emptyList();
    }
  }

  private String changePrefix(String changeRef) {
    if (changeRef == null || !changeRef.startsWith("refs/changes")) {
      return changeRef;
    }
    if (changeRef.endsWith(REF_META_SUFFIX)) {
      return getRootChangeRefPrefix(changeRef);
    }

    // changeRef has the form '/refs/changes/NN/NNNN/P'
    return changeRef.substring(0, changeRef.lastIndexOf('/'));
  }

  private String getRootChangeRefPrefix(String changeMetaRef) {
    if (changeMetaRef.endsWith(REF_META_SUFFIX)) {
      return changeMetaRef.substring(0, changeMetaRef.length() - REF_META_SUFFIX.length());
    }

    return changeMetaRef;
  }

  private Optional<ObjectId> getProjectLocalObjectIdVersion(
      Repository repository, String projectName, String ref) {
    try {
      return Optional.ofNullable(repository.exactRef(ref).getObjectId());

    } catch (IOException ioe) {
      String message =
          String.format("Error while extracting ref '%s' for project '%s'", ref, projectName);
      repLog.error(message);
      logger.atSevere().withCause(ioe).log(message);
    }
    return Optional.empty();
  }
}
