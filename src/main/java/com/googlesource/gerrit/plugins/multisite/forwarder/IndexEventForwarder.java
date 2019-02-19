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

package com.googlesource.gerrit.plugins.multisite.forwarder;

import com.googlesource.gerrit.plugins.multisite.forwarder.events.AccountIndexEvent;
import com.googlesource.gerrit.plugins.multisite.forwarder.events.ChangeIndexEvent;
import com.googlesource.gerrit.plugins.multisite.forwarder.events.GroupIndexEvent;
import com.googlesource.gerrit.plugins.multisite.forwarder.events.ProjectIndexEvent;

public interface IndexEventForwarder {
  /**
   * Forward a account indexing event to the other master.
   *
   * @param accountIndexEvent the details of the account index event.
   * @return true if successful, otherwise false.
   */
  boolean indexAccount(AccountIndexEvent accountIndexEvent);

  /**
   * Forward a change indexing event to the other master.
   *
   * @param changeIndexEvent the details of the change index event.
   * @return true if successful, otherwise false.
   */
  boolean indexChange(ChangeIndexEvent changeIndexEvent);

  /**
   * Forward a delete change from index event to the other master.
   *
   * @param changeIndexEvent the details of the change index event.
   * @return rue if successful, otherwise false.
   */
  boolean deleteChangeFromIndex(ChangeIndexEvent changeIndexEvent);

  /**
   * Forward a group indexing event to the other master.
   *
   * @param groupIndexEvent the details of the index event.
   * @return true if successful, otherwise false.
   */
  boolean indexGroup(GroupIndexEvent groupIndexEvent);

  /**
   * Forward a project indexing event to the other master.
   *
   * @param projectIndexEvent the details of the index event.
   * @return true if successful, otherwise false.
   */
  boolean indexProject(ProjectIndexEvent projectIndexEvent);
}