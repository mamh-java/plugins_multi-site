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

package com.googlesource.gerrit.plugins.multisite.consumer;

import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.googlesource.gerrit.plugins.multisite.forwarder.events.EventTopic;
import com.googlesource.gerrit.plugins.multisite.forwarder.events.MultiSiteEvent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SubscriberModule extends LifecycleModule {

  @Override
  protected void configure() {
    MultiSiteEvent.registerEventTypes();
    bind(ExecutorService.class)
        .annotatedWith(ConsumerExecutor.class)
        .toInstance(Executors.newFixedThreadPool(EventTopic.values().length));
    listener().to(MultiSiteConsumerRunner.class);

    DynamicSet.setOf(binder(), AbstractSubcriber.class);
    DynamicSet.setOf(binder(), DroppedEventListener.class);
  }
}