// Copyright (C) 2015 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.multisite;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Suppliers.memoize;
import static com.google.common.base.Suppliers.ofInstance;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CaseFormat;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.spi.Message;
import com.googlesource.gerrit.plugins.multisite.forwarder.events.EventFamily;
import com.googlesource.gerrit.plugins.multisite.validation.dfsrefdb.SharedRefEnforcement.EnforcePolicy;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.commons.lang.StringUtils;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.BoundedExponentialBackoffRetry;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class Configuration {
  private static final Logger log = LoggerFactory.getLogger(Configuration.class);

  public static final String PLUGIN_NAME = "multi-site";
  public static final String MULTI_SITE_CONFIG = PLUGIN_NAME + ".config";
  public static final String REPLICATION_CONFIG = "replication.config";

  static final String INSTANCE_ID_FILE = "instanceId.data";

  // common parameters to cache and index sections
  static final String THREAD_POOL_SIZE_KEY = "threadPoolSize";

  static final int DEFAULT_INDEX_MAX_TRIES = 2;
  static final int DEFAULT_INDEX_RETRY_INTERVAL = 30000;
  private static final int DEFAULT_POLLING_INTERVAL_MS = 1000;
  static final int DEFAULT_THREAD_POOL_SIZE = 4;
  static final String NUM_STRIPED_LOCKS = "numStripedLocks";
  static final int DEFAULT_NUM_STRIPED_LOCKS = 10;
  static final String ENABLE_KEY = "enabled";
  static final String DEFAULT_KAFKA_BOOTSTRAP_SERVERS = "localhost:9092";
  static final boolean DEFAULT_ENABLE_PROCESSING = true;
  static final String KAFKA_SECTION = "kafka";
  public static final String KAFKA_PROPERTY_PREFIX = "KafkaProp-";

  private final Supplier<KafkaPublisher> publisher;
  private final Supplier<Cache> cache;
  private final Supplier<Event> event;
  private final Supplier<Index> index;
  private final Supplier<KafkaSubscriber> subscriber;
  private final Supplier<Kafka> kafka;
  private final Supplier<ZookeeperConfig> zookeeperConfig;
  private final Supplier<Collection<Message>> replicationConfigValidation;

  @Inject
  Configuration(SitePaths sitePaths) {
    this(getConfigFile(sitePaths, MULTI_SITE_CONFIG), getConfigFile(sitePaths, REPLICATION_CONFIG));
  }

  @VisibleForTesting
  public Configuration(Config multiSiteConfig, Config replicationConfig) {
    Supplier<Config> lazyCfg = lazyLoad(multiSiteConfig);
    replicationConfigValidation = lazyValidateReplicatioConfig(replicationConfig);
    kafka = memoize(() -> new Kafka(lazyCfg));
    publisher = memoize(() -> new KafkaPublisher(lazyCfg));
    subscriber = memoize(() -> new KafkaSubscriber(lazyCfg));
    cache = memoize(() -> new Cache(lazyCfg));
    event = memoize(() -> new Event(lazyCfg));
    index = memoize(() -> new Index(lazyCfg));
    zookeeperConfig = memoize(() -> new ZookeeperConfig(lazyCfg));
  }

  public ZookeeperConfig getZookeeperConfig() {
    return zookeeperConfig.get();
  }

  public Kafka getKafka() {
    return kafka.get();
  }

  public KafkaPublisher kafkaPublisher() {
    return publisher.get();
  }

  public Cache cache() {
    return cache.get();
  }

  public Event event() {
    return event.get();
  }

  public Index index() {
    return index.get();
  }

  public KafkaSubscriber kafkaSubscriber() {
    return subscriber.get();
  }

  public Collection<Message> validate() {
    return replicationConfigValidation.get();
  }

  private static FileBasedConfig getConfigFile(SitePaths sitePaths, String configFileName) {
    return new FileBasedConfig(sitePaths.etc_dir.resolve(configFileName).toFile(), FS.DETECTED);
  }

  private Supplier<Config> lazyLoad(Config config) {
    if (config instanceof FileBasedConfig) {
      return memoize(
          () -> {
            FileBasedConfig fileConfig = (FileBasedConfig) config;
            String fileConfigFileName = fileConfig.getFile().getPath();
            try {
              log.info("Loading configuration from {}", fileConfigFileName);
              fileConfig.load();
            } catch (IOException | ConfigInvalidException e) {
              log.error("Unable to load configuration from " + fileConfigFileName, e);
            }
            return fileConfig;
          });
    }
    return ofInstance(config);
  }

  private Supplier<Collection<Message>> lazyValidateReplicatioConfig(Config replicationConfig) {
    if (replicationConfig instanceof FileBasedConfig) {
      FileBasedConfig fileConfig = (FileBasedConfig) replicationConfig;
      try {
        fileConfig.load();
        return memoize(() -> validateReplicationConfig(replicationConfig));
      } catch (IOException | ConfigInvalidException e) {
        return ofInstance(Arrays.asList(new Message("Unable to load replication.config", e)));
      }
    }
    return ofInstance(validateReplicationConfig(replicationConfig));
  }

  private Collection<Message> validateReplicationConfig(Config replicationConfig) {
    if (replicationConfig.getBoolean("gerrit", "replicateOnStartup", false)) {
      return Arrays.asList(
          new Message(
              "Invalid replication.config: gerrit.replicateOnStartup has to be set to 'false' for multi-site setups"));
    }
    return Collections.emptyList();
  }

  private static int getInt(
      Supplier<Config> cfg, String section, String subSection, String name, int defaultValue) {
    try {
      return cfg.get().getInt(section, subSection, name, defaultValue);
    } catch (IllegalArgumentException e) {
      log.error("invalid value for {}; using default value {}", name, defaultValue);
      log.debug("Failed to retrieve integer value: {}", e.getMessage(), e);
      return defaultValue;
    }
  }

  private static long getLong(
      Supplier<Config> cfg, String section, String subSection, String name, long defaultValue) {
    try {
      return cfg.get().getLong(section, subSection, name, defaultValue);
    } catch (IllegalArgumentException e) {
      log.error("invalid value for {}; using default value {}", name, defaultValue);
      log.debug("Failed to retrieve long value: {}", e.getMessage(), e);
      return defaultValue;
    }
  }

  private static String getString(
      Supplier<Config> cfg, String section, String subsection, String name, String defaultValue) {
    String value = cfg.get().getString(section, subsection, name);
    if (!Strings.isNullOrEmpty(value)) {
      return value;
    }
    return defaultValue;
  }

  private static Map<EventFamily, Boolean> eventsEnabled(
      Supplier<Config> config, String subsection) {
    Map<EventFamily, Boolean> eventsEnabled = new HashMap<>();
    for (EventFamily eventFamily : EventFamily.values()) {
      String enabledConfigKey = eventFamily.lowerCamelName() + "Enabled";

      eventsEnabled.put(
          eventFamily,
          config
              .get()
              .getBoolean(KAFKA_SECTION, subsection, enabledConfigKey, DEFAULT_ENABLE_PROCESSING));
    }
    return eventsEnabled;
  }

  private static void applyKafkaConfig(
      Supplier<Config> configSupplier, String subsectionName, Properties target) {
    Config config = configSupplier.get();
    for (String section : config.getSubsections(KAFKA_SECTION)) {
      if (section.equals(subsectionName)) {
        for (String name : config.getNames(KAFKA_SECTION, section, true)) {
          if (name.startsWith(KAFKA_PROPERTY_PREFIX)) {
            Object value = config.getString(KAFKA_SECTION, subsectionName, name);
            String configProperty = name.replaceFirst(KAFKA_PROPERTY_PREFIX, "");
            String propName =
                CaseFormat.LOWER_CAMEL
                    .to(CaseFormat.LOWER_HYPHEN, configProperty)
                    .replaceAll("-", ".");
            log.info("[{}] Setting kafka property: {} = {}", subsectionName, propName, value);
            target.put(propName, value);
          }
        }
      }
    }
    target.put(
        "bootstrap.servers",
        getString(
            configSupplier,
            KAFKA_SECTION,
            null,
            "bootstrapServers",
            DEFAULT_KAFKA_BOOTSTRAP_SERVERS));
  }

  public static class Kafka {
    private final Map<EventFamily, String> eventTopics;
    private final String bootstrapServers;

    private static final Map<EventFamily, String> EVENT_TOPICS =
        ImmutableMap.of(
            EventFamily.INDEX_EVENT,
            "GERRIT.EVENT.INDEX",
            EventFamily.STREAM_EVENT,
            "GERRIT.EVENT.STREAM",
            EventFamily.CACHE_EVENT,
            "GERRIT.EVENT.CACHE",
            EventFamily.PROJECT_LIST_EVENT,
            "GERRIT.EVENT.PROJECT.LIST");

    Kafka(Supplier<Config> config) {
      this.bootstrapServers =
          getString(
              config, KAFKA_SECTION, null, "bootstrapServers", DEFAULT_KAFKA_BOOTSTRAP_SERVERS);

      this.eventTopics = new HashMap<>();
      for (Map.Entry<EventFamily, String> topicDefault : EVENT_TOPICS.entrySet()) {
        String topicConfigKey = topicDefault.getKey().lowerCamelName() + "Topic";
        eventTopics.put(
            topicDefault.getKey(),
            getString(config, KAFKA_SECTION, null, topicConfigKey, topicDefault.getValue()));
      }
    }

    public String getTopic(EventFamily eventType) {
      return eventTopics.get(eventType);
    }

    public String getBootstrapServers() {
      return bootstrapServers;
    }
  }

  public static class KafkaPublisher extends Properties {
    private static final long serialVersionUID = 0L;

    public static final String KAFKA_STRING_SERIALIZER = StringSerializer.class.getName();

    public static final String KAFKA_PUBLISHER_SUBSECTION = "publisher";
    public static final boolean DEFAULT_BROKER_ENABLED = false;

    private final boolean enabled;
    private final Map<EventFamily, Boolean> eventsEnabled;

    private KafkaPublisher(Supplier<Config> cfg) {
      enabled =
          cfg.get()
              .getBoolean(
                  KAFKA_SECTION, KAFKA_PUBLISHER_SUBSECTION, ENABLE_KEY, DEFAULT_BROKER_ENABLED);

      eventsEnabled = eventsEnabled(cfg, KAFKA_PUBLISHER_SUBSECTION);

      if (enabled) {
        setDefaults();
        applyKafkaConfig(cfg, KAFKA_PUBLISHER_SUBSECTION, this);
      }
    }

    private void setDefaults() {
      put("acks", "all");
      put("retries", 0);
      put("batch.size", 16384);
      put("linger.ms", 1);
      put("buffer.memory", 33554432);
      put("key.serializer", KAFKA_STRING_SERIALIZER);
      put("value.serializer", KAFKA_STRING_SERIALIZER);
      put("reconnect.backoff.ms", 5000L);
    }

    public boolean enabled() {
      return enabled;
    }

    public boolean enabledEvent(EventFamily eventType) {
      return eventsEnabled.get(eventType);
    }
  }

  public class KafkaSubscriber extends Properties {
    private static final long serialVersionUID = 1L;

    static final String KAFKA_SUBSCRIBER_SUBSECTION = "subscriber";

    private final boolean enabled;
    private final Integer pollingInterval;
    private Map<EventFamily, Boolean> eventsEnabled;
    private final Config cfg;

    public KafkaSubscriber(Supplier<Config> configSupplier) {
      this.cfg = configSupplier.get();

      this.pollingInterval =
          cfg.getInt(
              KAFKA_SECTION,
              KAFKA_SUBSCRIBER_SUBSECTION,
              "pollingIntervalMs",
              DEFAULT_POLLING_INTERVAL_MS);

      enabled = cfg.getBoolean(KAFKA_SECTION, KAFKA_SUBSCRIBER_SUBSECTION, ENABLE_KEY, false);

      eventsEnabled = eventsEnabled(configSupplier, KAFKA_SUBSCRIBER_SUBSECTION);

      if (enabled) {
        applyKafkaConfig(configSupplier, KAFKA_SUBSCRIBER_SUBSECTION, this);
      }
    }

    public boolean enabled() {
      return enabled;
    }

    public boolean enabledEvent(EventFamily eventFamily) {
      return eventsEnabled.get(eventFamily);
    }

    public Properties initPropsWith(UUID instanceId) {
      String groupId =
          getString(
              cfg, KAFKA_SECTION, KAFKA_SUBSCRIBER_SUBSECTION, "groupId", instanceId.toString());
      this.put("group.id", groupId);

      return this;
    }

    public Integer getPollingInterval() {
      return pollingInterval;
    }

    private String getString(
        Config cfg, String section, String subsection, String name, String defaultValue) {
      String value = cfg.getString(section, subsection, name);
      if (!Strings.isNullOrEmpty(value)) {
        return value;
      }
      return defaultValue;
    }
  }

  /** Common parameters to cache, event, index */
  public abstract static class Forwarding {
    static final boolean DEFAULT_SYNCHRONIZE = true;
    static final String SYNCHRONIZE_KEY = "synchronize";

    private final boolean synchronize;

    private Forwarding(Supplier<Config> cfg, String section) {
      synchronize =
          Configuration.getBoolean(cfg, section, null, SYNCHRONIZE_KEY, DEFAULT_SYNCHRONIZE);
    }

    public boolean synchronize() {
      return synchronize;
    }
  }

  public static class Cache extends Forwarding {
    static final String CACHE_SECTION = "cache";
    static final String PATTERN_KEY = "pattern";

    private final int threadPoolSize;
    private final List<String> patterns;

    private Cache(Supplier<Config> cfg) {
      super(cfg, CACHE_SECTION);
      threadPoolSize =
          getInt(cfg, CACHE_SECTION, null, THREAD_POOL_SIZE_KEY, DEFAULT_THREAD_POOL_SIZE);
      patterns = Arrays.asList(cfg.get().getStringList(CACHE_SECTION, null, PATTERN_KEY));
    }

    public int threadPoolSize() {
      return threadPoolSize;
    }

    public List<String> patterns() {
      return Collections.unmodifiableList(patterns);
    }
  }

  public static class Event extends Forwarding {
    static final String EVENT_SECTION = "event";

    private Event(Supplier<Config> cfg) {
      super(cfg, EVENT_SECTION);
    }
  }

  public static class Index extends Forwarding {
    static final String INDEX_SECTION = "index";
    static final String MAX_TRIES_KEY = "maxTries";
    static final String RETRY_INTERVAL_KEY = "retryInterval";

    private final int threadPoolSize;
    private final int retryInterval;
    private final int maxTries;

    private final int numStripedLocks;

    private Index(Supplier<Config> cfg) {
      super(cfg, INDEX_SECTION);
      threadPoolSize =
          getInt(cfg, INDEX_SECTION, null, THREAD_POOL_SIZE_KEY, DEFAULT_THREAD_POOL_SIZE);
      retryInterval =
          getInt(cfg, INDEX_SECTION, null, RETRY_INTERVAL_KEY, DEFAULT_INDEX_RETRY_INTERVAL);
      maxTries = getInt(cfg, INDEX_SECTION, null, MAX_TRIES_KEY, DEFAULT_INDEX_MAX_TRIES);
      numStripedLocks =
          getInt(cfg, INDEX_SECTION, null, NUM_STRIPED_LOCKS, DEFAULT_NUM_STRIPED_LOCKS);
    }

    public int threadPoolSize() {
      return threadPoolSize;
    }

    public int retryInterval() {
      return retryInterval;
    }

    public int maxTries() {
      return maxTries;
    }

    public int numStripedLocks() {
      return numStripedLocks;
    }
  }

  public static class ZookeeperConfig {
    public static final String SECTION = "ref-database";
    public static final int defaultSessionTimeoutMs;
    public static final int defaultConnectionTimeoutMs;
    public static final String DEFAULT_ZK_CONNECT = "localhost:2181";
    private final int DEFAULT_RETRY_POLICY_BASE_SLEEP_TIME_MS = 1000;
    private final int DEFAULT_RETRY_POLICY_MAX_SLEEP_TIME_MS = 3000;
    private final int DEFAULT_RETRY_POLICY_MAX_RETRIES = 3;
    private final int DEFAULT_CAS_RETRY_POLICY_BASE_SLEEP_TIME_MS = 100;
    private final int DEFAULT_CAS_RETRY_POLICY_MAX_SLEEP_TIME_MS = 300;
    private final int DEFAULT_CAS_RETRY_POLICY_MAX_RETRIES = 3;
    private final int DEFAULT_TRANSACTION_LOCK_TIMEOUT = 1000;

    static {
      CuratorFrameworkFactory.Builder b = CuratorFrameworkFactory.builder();
      defaultSessionTimeoutMs = b.getSessionTimeoutMs();
      defaultConnectionTimeoutMs = b.getConnectionTimeoutMs();
    }

    public static final String SUBSECTION = "zookeeper";
    public static final String KEY_CONNECT_STRING = "connectString";
    public static final String KEY_SESSION_TIMEOUT_MS = "sessionTimeoutMs";
    public static final String KEY_CONNECTION_TIMEOUT_MS = "connectionTimeoutMs";
    public static final String KEY_RETRY_POLICY_BASE_SLEEP_TIME_MS = "retryPolicyBaseSleepTimeMs";
    public static final String KEY_RETRY_POLICY_MAX_SLEEP_TIME_MS = "retryPolicyMaxSleepTimeMs";
    public static final String KEY_RETRY_POLICY_MAX_RETRIES = "retryPolicyMaxRetries";
    public static final String KEY_LOCK_TIMEOUT_MS = "lockTimeoutMs";
    public static final String KEY_ROOT_NODE = "rootNode";
    public final String KEY_CAS_RETRY_POLICY_BASE_SLEEP_TIME_MS = "casRetryPolicyBaseSleepTimeMs";
    public final String KEY_CAS_RETRY_POLICY_MAX_SLEEP_TIME_MS = "casRetryPolicyMaxSleepTimeMs";
    public final String KEY_CAS_RETRY_POLICY_MAX_RETRIES = "casRetryPolicyMaxRetries";
    public static final String KEY_MIGRATE = "migrate";
    public final String TRANSACTION_LOCK_TIMEOUT_KEY = "transactionLockTimeoutMs";

    public static final String SUBSECTION_ENFORCEMENT_RULES = "enforcementRules";

    private final String connectionString;
    private final String root;
    private final int sessionTimeoutMs;
    private final int connectionTimeoutMs;
    private final int baseSleepTimeMs;
    private final int maxSleepTimeMs;
    private final int maxRetries;
    private final int casBaseSleepTimeMs;
    private final int casMaxSleepTimeMs;
    private final int casMaxRetries;
    private final boolean enabled;

    private final Multimap<EnforcePolicy, String> enforcementRules;

    private final Long transactionLockTimeOut;

    private CuratorFramework build;

    private ZookeeperConfig(Supplier<Config> cfg) {
      connectionString =
          getString(cfg, SECTION, SUBSECTION, KEY_CONNECT_STRING, DEFAULT_ZK_CONNECT);
      root = getString(cfg, SECTION, SUBSECTION, KEY_ROOT_NODE, "gerrit/multi-site");
      sessionTimeoutMs =
          getInt(cfg, SECTION, SUBSECTION, KEY_SESSION_TIMEOUT_MS, defaultSessionTimeoutMs);
      connectionTimeoutMs =
          getInt(cfg, SECTION, SUBSECTION, KEY_CONNECTION_TIMEOUT_MS, defaultConnectionTimeoutMs);

      baseSleepTimeMs =
          getInt(
              cfg,
              SECTION,
              SUBSECTION,
              KEY_RETRY_POLICY_BASE_SLEEP_TIME_MS,
              DEFAULT_RETRY_POLICY_BASE_SLEEP_TIME_MS);

      maxSleepTimeMs =
          getInt(
              cfg,
              SECTION,
              SUBSECTION,
              KEY_RETRY_POLICY_MAX_SLEEP_TIME_MS,
              DEFAULT_RETRY_POLICY_MAX_SLEEP_TIME_MS);

      maxRetries =
          getInt(
              cfg,
              SECTION,
              SUBSECTION,
              KEY_RETRY_POLICY_MAX_RETRIES,
              DEFAULT_RETRY_POLICY_MAX_RETRIES);

      casBaseSleepTimeMs =
          getInt(
              cfg,
              SECTION,
              SUBSECTION,
              KEY_CAS_RETRY_POLICY_BASE_SLEEP_TIME_MS,
              DEFAULT_CAS_RETRY_POLICY_BASE_SLEEP_TIME_MS);

      casMaxSleepTimeMs =
          getInt(
              cfg,
              SECTION,
              SUBSECTION,
              KEY_CAS_RETRY_POLICY_MAX_SLEEP_TIME_MS,
              DEFAULT_CAS_RETRY_POLICY_MAX_SLEEP_TIME_MS);

      casMaxRetries =
          getInt(
              cfg,
              SECTION,
              SUBSECTION,
              KEY_CAS_RETRY_POLICY_MAX_RETRIES,
              DEFAULT_CAS_RETRY_POLICY_MAX_RETRIES);

      transactionLockTimeOut =
          getLong(
              cfg,
              SECTION,
              SUBSECTION,
              TRANSACTION_LOCK_TIMEOUT_KEY,
              DEFAULT_TRANSACTION_LOCK_TIMEOUT);

      checkArgument(StringUtils.isNotEmpty(connectionString), "zookeeper.%s contains no servers");

      enabled = Configuration.getBoolean(cfg, SECTION, null, ENABLE_KEY, true);

      enforcementRules = MultimapBuilder.hashKeys().arrayListValues().build();
      for (EnforcePolicy policy : EnforcePolicy.values()) {
        enforcementRules.putAll(
            policy,
            Configuration.getList(cfg, SECTION, SUBSECTION_ENFORCEMENT_RULES, policy.name()));
      }
    }

    public CuratorFramework buildCurator() {
      if (build == null) {
        this.build =
            CuratorFrameworkFactory.builder()
                .connectString(connectionString)
                .sessionTimeoutMs(sessionTimeoutMs)
                .connectionTimeoutMs(connectionTimeoutMs)
                .retryPolicy(
                    new BoundedExponentialBackoffRetry(baseSleepTimeMs, maxSleepTimeMs, maxRetries))
                .namespace(root)
                .build();
        this.build.start();
      }

      return this.build;
    }

    public Long getZkInterProcessLockTimeOut() {
      return transactionLockTimeOut;
    }

    public RetryPolicy buildCasRetryPolicy() {
      return new BoundedExponentialBackoffRetry(
          casBaseSleepTimeMs, casMaxSleepTimeMs, casMaxRetries);
    }

    public boolean isEnabled() {
      return enabled;
    }

    public Multimap<EnforcePolicy, String> getEnforcementRules() {
      return enforcementRules;
    }
  }

  static List<String> getList(
      Supplier<Config> cfg, String section, String subsection, String name) {
    return ImmutableList.copyOf(cfg.get().getStringList(section, subsection, name));
  }

  static boolean getBoolean(
      Supplier<Config> cfg, String section, String subsection, String name, boolean defaultValue) {
    try {
      return cfg.get().getBoolean(section, subsection, name, defaultValue);
    } catch (IllegalArgumentException e) {
      log.error("invalid value for {}; using default value {}", name, defaultValue);
      log.debug("Failed to retrieve boolean value: {}", e.getMessage(), e);
      return defaultValue;
    }
  }
}
