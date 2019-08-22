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

package com.googlesource.gerrit.plugins.multisite.broker.kafka;

import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.multisite.broker.BrokerPublisher;
import com.googlesource.gerrit.plugins.multisite.broker.BrokerSession;
import com.googlesource.gerrit.plugins.multisite.forwarder.CacheEvictionForwarder;
import com.googlesource.gerrit.plugins.multisite.forwarder.IndexEventForwarder;
import com.googlesource.gerrit.plugins.multisite.forwarder.ProjectListUpdateForwarder;
import com.googlesource.gerrit.plugins.multisite.forwarder.StreamEventForwarder;
import com.googlesource.gerrit.plugins.multisite.forwarder.broker.BrokerCacheEvictionForwarder;
import com.googlesource.gerrit.plugins.multisite.forwarder.broker.BrokerIndexEventForwarder;
import com.googlesource.gerrit.plugins.multisite.forwarder.broker.BrokerProjectListUpdateForwarder;
import com.googlesource.gerrit.plugins.multisite.forwarder.broker.BrokerStreamEventForwarder;
import com.googlesource.gerrit.plugins.multisite.forwarder.events.EventTopic;
import com.googlesource.gerrit.plugins.multisite.kafka.KafkaConfiguration;

public class KafkaBrokerForwarderModule extends LifecycleModule {
  private final KafkaConfiguration config;

  @Inject
  public KafkaBrokerForwarderModule(KafkaConfiguration config) {
    this.config = config;
  }

  @Override
  protected void configure() {
    if (config.kafkaPublisher().enabled()) {
      listener().to(BrokerPublisher.class);
      bind(BrokerSession.class).to(KafkaSession.class);

      if (config.kafkaPublisher().enabledEvent(EventTopic.INDEX_TOPIC)) {
        DynamicSet.bind(binder(), IndexEventForwarder.class).to(BrokerIndexEventForwarder.class);
      }
      if (config.kafkaPublisher().enabledEvent(EventTopic.CACHE_TOPIC)) {
        DynamicSet.bind(binder(), CacheEvictionForwarder.class)
            .to(BrokerCacheEvictionForwarder.class);
      }
      if (config.kafkaPublisher().enabledEvent(EventTopic.PROJECT_LIST_TOPIC)) {
        DynamicSet.bind(binder(), ProjectListUpdateForwarder.class)
            .to(BrokerProjectListUpdateForwarder.class);
      }
      if (config.kafkaPublisher().enabledEvent(EventTopic.STREAM_EVENT_TOPIC)) {
        DynamicSet.bind(binder(), StreamEventForwarder.class).to(BrokerStreamEventForwarder.class);
      }
    }
  }
}
