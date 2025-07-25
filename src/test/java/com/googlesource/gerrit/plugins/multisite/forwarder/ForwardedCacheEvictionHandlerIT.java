// Copyright (C) 2021 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import com.gerritforge.gerrit.globalrefdb.validation.SharedRefDbConfiguration;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.projects.ProjectInput;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.registration.RegistrationHandle;
import com.google.gerrit.server.cache.CacheRemovalListener;
import com.google.gerrit.server.events.EventGson;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.project.ProjectCacheImpl;
import com.google.gson.Gson;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.multisite.Configuration;
import com.googlesource.gerrit.plugins.multisite.ExecutorProvider;
import com.googlesource.gerrit.plugins.multisite.cache.CacheModule;
import com.googlesource.gerrit.plugins.multisite.forwarder.events.CacheEvictionEvent;
import com.googlesource.gerrit.plugins.multisite.forwarder.router.CacheEvictionEventRouter;
import com.googlesource.gerrit.plugins.multisite.forwarder.router.RouterModule;
import com.googlesource.gerrit.plugins.multisite.index.IndexModule;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "multi-site",
    sysModule =
        "com.googlesource.gerrit.plugins.multisite.forwarder.ForwardedCacheEvictionHandlerIT$TestModule")
public class ForwardedCacheEvictionHandlerIT extends LightweightPluginDaemonTest {
  private static final Duration CACHE_EVICTIONS_WAIT_TIMEOUT = Duration.ofMinutes(1);

  @SuppressWarnings("rawtypes")
  @Inject
  private DynamicSet<CacheRemovalListener> cacheRemovalListeners;

  @Inject private CacheEvictionEventRouter objectUnderTest;
  @Inject @EventGson private Gson gson;
  private CacheEvictionsTracker<?, ?> evictionsCacheTracker;
  private RegistrationHandle cacheEvictionRegistrationHandle;

  public static class TestModule extends AbstractModule {
    @Inject Configuration config;

    @Override
    protected void configure() {
      install(new ForwarderModule());
      install(new CacheModule(TestForwardingExecutorProvider.class));
      install(new RouterModule(config.index()));
      install(new IndexModule());
      SharedRefDbConfiguration sharedRefDbConfig =
          new SharedRefDbConfiguration(new Config(), "multi-site");
      bind(SharedRefDbConfiguration.class).toInstance(sharedRefDbConfig);
    }
  }

  @Singleton
  public static class TestForwardingExecutorProvider extends ExecutorProvider {
    private final ScheduledThreadPoolExecutor executor;
    private final AtomicInteger executionsCounter;

    @Inject
    protected TestForwardingExecutorProvider(WorkQueue workQueue) {
      super(workQueue, 1, "test");
      executionsCounter = new AtomicInteger();
      executor =
          new ScheduledThreadPoolExecutor(1) {

            @Override
            public void execute(Runnable command) {
              @SuppressWarnings("unused")
              int ignored = executionsCounter.incrementAndGet();
              super.execute(command);
            }
          };
    }

    @Override
    public ScheduledExecutorService get() {
      return executor;
    }

    public int executions() {
      return executionsCounter.get();
    }
  }

  public static class CacheEvictionsTracker<K, V> implements CacheRemovalListener<K, V> {
    private final Map<String, Set<Object>> trackedEvictions;
    private final CountDownLatch allExpectedEvictionsArrived;
    private final String trackedCacheName;

    public CacheEvictionsTracker(String cacheName, int numExpectedEvictions) {
      this.trackedCacheName = cacheName;
      allExpectedEvictionsArrived = new CountDownLatch(numExpectedEvictions);
      trackedEvictions = Maps.newHashMap();
    }

    public Set<Object> trackedEvictionsFor(String cacheName) {
      return trackedEvictions.getOrDefault(cacheName, Collections.emptySet());
    }

    public void waitForExpectedEvictions() throws InterruptedException {
      allExpectedEvictionsArrived.await(
          CACHE_EVICTIONS_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void onRemoval(
        String pluginName, String cacheName, RemovalNotification<K, V> notification) {
      if (cacheName.equals(trackedCacheName)) {
        trackedEvictions.compute(
            cacheName,
            (k, v) -> {
              if (v == null) {
                return Sets.newHashSet(notification.getKey());
              }
              v.add(notification.getKey());
              return v;
            });
        allExpectedEvictionsArrived.countDown();
      }
    }
  }

  @Before
  public void startTrackingCacheEvictions() {
    evictionsCacheTracker = new CacheEvictionsTracker<>(ProjectCacheImpl.CACHE_NAME, 1);
    cacheEvictionRegistrationHandle = cacheRemovalListeners.add("gerrit", evictionsCacheTracker);
  }

  @After
  public void stopTrackingCacheEvictions() {
    cacheEvictionRegistrationHandle.remove();
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldEvictProjectCache() throws Exception {
    objectUnderTest.route(
        new CacheEvictionEvent(ProjectCacheImpl.CACHE_NAME, project.get(), "instance-id"));
    evictionsCacheTracker.waitForExpectedEvictions();

    assertThat(evictionsCacheTracker.trackedEvictionsFor(ProjectCacheImpl.CACHE_NAME))
        .contains(project);
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "cache.threads", value = "0")
  public void shouldNotForwardProjectCacheEvictionsWhenEventIsForwarded() throws Exception {
    TestForwardingExecutorProvider cacheForwarder =
        plugin.getSysInjector().getInstance(TestForwardingExecutorProvider.class);
    Context.setForwardedEvent(true);
    projectCache.evict(allProjects);

    evictionsCacheTracker.waitForExpectedEvictions();
    assertThat(evictionsCacheTracker.trackedEvictionsFor(ProjectCacheImpl.CACHE_NAME))
        .contains(allProjects);

    assertThat(cacheForwarder.executions()).isEqualTo(0);
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldForwardProjectCacheEvictions() throws Exception {
    TestForwardingExecutorProvider cacheForwarder =
        plugin.getSysInjector().getInstance(TestForwardingExecutorProvider.class);
    projectCache.evict(allProjects);

    evictionsCacheTracker.waitForExpectedEvictions();
    assertThat(evictionsCacheTracker.trackedEvictionsFor(ProjectCacheImpl.CACHE_NAME))
        .contains(allProjects);

    assertThat(cacheForwarder.executions()).isEqualTo(1);
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "instance-id")
  public void shouldEvictProjectCacheWithSlash() throws Exception {
    ProjectInput in = new ProjectInput();
    in.name = name("my/project");
    gApi.projects().create(in);
    Project.NameKey projectNameKey = Project.nameKey(in.name);

    restartCacheEvictionsTracking();

    objectUnderTest.route(
        new CacheEvictionEvent(ProjectCacheImpl.CACHE_NAME, projectNameKey.get(), "instance-id"));

    evictionsCacheTracker.waitForExpectedEvictions();
    assertThat(evictionsCacheTracker.trackedEvictionsFor(ProjectCacheImpl.CACHE_NAME))
        .contains(projectNameKey);
  }

  private void restartCacheEvictionsTracking() {
    stopTrackingCacheEvictions();
    startTrackingCacheEvictions();
  }
}
