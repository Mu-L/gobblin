/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.service;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.linkedin.data.DataMap;
import com.linkedin.data.template.StringMap;
import com.linkedin.r2.transport.http.client.HttpClientFactory;
import com.linkedin.restli.client.RestLiResponseException;
import com.linkedin.restli.common.HttpStatus;
import com.linkedin.restli.common.PatchRequest;
import com.linkedin.restli.internal.server.util.DataMapUtils;
import com.linkedin.restli.server.resources.BaseResource;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import lombok.Setter;

import org.apache.gobblin.config.ConfigBuilder;
import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.restli.EmbeddedRestliServer;
import org.apache.gobblin.runtime.api.FlowSpec;
import org.apache.gobblin.runtime.api.SpecCatalogListener;
import org.apache.gobblin.runtime.spec_catalog.AddSpecResponse;
import org.apache.gobblin.runtime.spec_catalog.FlowCatalog;
import org.apache.gobblin.runtime.spec_store.FSSpecStore;
import org.apache.gobblin.service.modules.restli.FlowConfigsV2ResourceHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


@Test(groups = { "gobblin.service" }, singleThreaded = true)
public class FlowConfigsV2Test {
  private FlowConfigV2Client _client;
  private EmbeddedRestliServer _server;
  private File _testDirectory;
  private TestRequesterService _requesterService;
  private GroupOwnershipService groupOwnershipService;
  private final Set<String> _compilationFailureFlowPaths = Sets.newHashSet();

  private static final String TEST_SPEC_STORE_DIR = "/tmp/flowConfigV2Test/";
  private static final String TEST_DUMMY_GROUP_NAME = "dummyGroup";
  private static final String TEST_DUMMY_FLOW_NAME = "dummyFlow";
  private static final String TEST_GROUP_NAME = "testGroup1";
  private static final String TEST_FLOW_NAME = "testFlow1";
  private static final String TEST_FLOW_NAME_2 = "testFlow2";
  private static final String TEST_FLOW_NAME_3 = "testFlow3";
  private static final String TEST_FLOW_NAME_4 = "testFlow4";
  private static final String TEST_FLOW_NAME_5 = "testFlow5";
  private static final String TEST_FLOW_NAME_6 = "testFlow6";
  private static final String TEST_FLOW_NAME_7 = "testFlow7";
  private static final String TEST_FLOW_NAME_8 = "testFlow8";
  private static final String TEST_FLOW_NAME_9 = "testFlow9";
  private static final String TEST_FLOW_NAME_10 = "testFlow10";
  private static final String TEST_FLOW_NAME_11 = "testFlow11";
  private static final String TEST_FLOW_NAME_12 = "testFlow12";
  private static final String TEST_SCHEDULE = "0 1/0 * ? * *";
  private static final String TEST_TEMPLATE_URI = "FS:///templates/test.template";

  private static final ServiceRequester TEST_REQUESTER = new ServiceRequester("testName", "USER_PRINCIPAL", "testFrom");
  private static final ServiceRequester TEST_REQUESTER2 = new ServiceRequester("testName2", "USER_PRINCIPAL", "testFrom");

  @BeforeClass
  public void setUp() throws Exception {
    _testDirectory = Files.createTempDir();
    cleanUpDir(TEST_SPEC_STORE_DIR);

    Config config = ConfigBuilder.create()
        .addPrimitive(ConfigurationKeys.JOB_CONFIG_FILE_DIR_KEY, _testDirectory.getAbsolutePath())
        .addPrimitive(FSSpecStore.SPECSTORE_FS_DIR_KEY, TEST_SPEC_STORE_DIR).build();

    final FlowCatalog flowCatalog = new FlowCatalog(config);
    final SpecCatalogListener mockListener = mock(SpecCatalogListener.class);
    when(mockListener.getName()).thenReturn(ServiceConfigKeys.GOBBLIN_ORCHESTRATOR_LISTENER_CLASS);
    when(mockListener.onAddSpec(any())).thenAnswer(invocation -> new AddSpecResponse(_compilationFailureFlowPaths
        .contains(invocation.<FlowSpec>getArgument(0).getUri().getPath()) ? null : "")
    );

    flowCatalog.addListener(mockListener);
    flowCatalog.startAsync();
    flowCatalog.awaitRunning();

    _requesterService = new TestRequesterService(ConfigFactory.empty());

    File groupConfigFile = new File(_testDirectory + "/TestGroups.json");
    String groups ="{\"testGroup\": \"testName,testName2\"}";
    Files.write(groups.getBytes(), groupConfigFile);
    Config groupServiceConfig = ConfigBuilder.create()
        .addPrimitive(LocalGroupOwnershipService.GROUP_MEMBER_LIST, groupConfigFile.getAbsolutePath())
        .build();

    groupOwnershipService = new LocalGroupOwnershipService(groupServiceConfig);

    Injector injector = Guice.createInjector(binder -> {
      binder.bind(FlowConfigsV2ResourceHandler.class).toInstance(new FlowConfigsV2ResourceHandler("service_name", flowCatalog));
      binder.bind(RequesterService.class).toInstance(_requesterService);
      binder.bind(GroupOwnershipService.class).toInstance(groupOwnershipService);
    });

    _server = EmbeddedRestliServer.builder().resources(Lists.newArrayList(FlowConfigsV2Resource.class)).injector(injector).build();

    _server.startAsync();
    _server.awaitRunning();

    Map<String, String> transportClientProperties = Maps.newHashMap();
    transportClientProperties.put(HttpClientFactory.HTTP_REQUEST_TIMEOUT, "10000");
    _client =
        new FlowConfigV2Client(String.format("http://localhost:%s/", _server.getPort()), transportClientProperties);
  }

  protected void cleanUpDir(String dir) throws Exception {
    File specStoreDir = new File(dir);
    if (specStoreDir.exists()) {
      FileUtils.deleteDirectory(specStoreDir);
    }
  }

  @Test
  public void testCreateBadSchedule() throws Exception {
    Map<String, String> flowProperties = Maps.newHashMap();
    flowProperties.put("param1", "value1");

    FlowConfig flowConfig = new FlowConfig().setId(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME))
        .setTemplateUris(TEST_TEMPLATE_URI).setSchedule(new Schedule().setCronSchedule("bad schedule").
            setRunImmediately(true))
        .setProperties(new StringMap(flowProperties));

    try {
      _client.createFlowConfig(flowConfig);
    } catch (RestLiResponseException e) {
      Assert.assertEquals(e.getStatus(), HttpStatus.S_422_UNPROCESSABLE_ENTITY.getCode());
      return;
    }

    Assert.fail("Get should have gotten a 422 error");
  }

  @Test
  public void testCreateBadTemplateUri() throws Exception {
    Map<String, String> flowProperties = Maps.newHashMap();
    flowProperties.put("param1", "value1");

    FlowConfig flowConfig = new FlowConfig().setId(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME))
        .setTemplateUris("FILE://bad/uri").setSchedule(new Schedule().setCronSchedule(TEST_SCHEDULE).
            setRunImmediately(true))
        .setProperties(new StringMap(flowProperties));

    try {
      _client.createFlowConfig(flowConfig);
    } catch (RestLiResponseException e) {
      Assert.assertEquals(e.getStatus(), HttpStatus.S_422_UNPROCESSABLE_ENTITY.getCode());
      return;
    }

    Assert.fail("Get should have gotten a 422 error");
  }

  @Test
  public void testCreate() throws Exception {
    Map<String, String> flowProperties = Maps.newHashMap();
    flowProperties.put("param1", "value1");
    _requesterService.setRequester(TEST_REQUESTER);

    FlowConfig flowConfig = new FlowConfig().setId(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_12))
        .setTemplateUris(TEST_TEMPLATE_URI).setSchedule(new Schedule().setCronSchedule(TEST_SCHEDULE).
            setRunImmediately(true))
        .setProperties(new StringMap(flowProperties));

    _client.createFlowConfig(flowConfig);
  }

  @Test (dependsOnMethods = "testCreate")
  public void testGet() throws Exception {
    FlowId flowId = new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_12);
    FlowConfig flowConfig = _client.getFlowConfig(flowId);

    Assert.assertEquals(flowConfig.getId().getFlowGroup(), TEST_GROUP_NAME);
    Assert.assertEquals(flowConfig.getId().getFlowName(), TEST_FLOW_NAME_12);
    Assert.assertEquals(flowConfig.getSchedule().getCronSchedule(), TEST_SCHEDULE );
    Assert.assertEquals(flowConfig.getTemplateUris(), TEST_TEMPLATE_URI);
    Assert.assertTrue(flowConfig.getSchedule().isRunImmediately());
    // Add this assert back when getFlowSpec() is changed to return the raw flow spec
    //Assert.assertEquals(flowConfig.getProperties().size(), 1);
    Assert.assertEquals(flowConfig.getProperties().get("param1"), "value1");
  }

  @Test (dependsOnMethods = "testGet")
  public void testUpdate() throws Exception {
    FlowId flowId = new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_12);
    _requesterService.setRequester(TEST_REQUESTER);

    Map<String, String> flowProperties = Maps.newHashMap();
    flowProperties.put("param1", "value1b");
    flowProperties.put("param2", "value2b");

    FlowConfig flowConfig = new FlowConfig().setId(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_12))
        .setTemplateUris(TEST_TEMPLATE_URI).setSchedule(new Schedule().setCronSchedule(TEST_SCHEDULE))
        .setProperties(new StringMap(flowProperties));

    _client.updateFlowConfig(flowConfig);

    FlowConfig retrievedFlowConfig = _client.getFlowConfig(flowId);

    Assert.assertEquals(retrievedFlowConfig.getId().getFlowGroup(), TEST_GROUP_NAME);
    Assert.assertEquals(retrievedFlowConfig.getId().getFlowName(), TEST_FLOW_NAME_12);
    Assert.assertEquals(Objects.requireNonNull(retrievedFlowConfig.getSchedule()).getCronSchedule(), TEST_SCHEDULE);
    Assert.assertEquals(retrievedFlowConfig.getTemplateUris(), TEST_TEMPLATE_URI);
    // Add this asssert when getFlowSpec() is changed to return the raw flow spec
    //Assert.assertEquals(flowConfig.getProperties().size(), 2);
    Assert.assertEquals(retrievedFlowConfig.getProperties().get("param1"), "value1b");
    Assert.assertEquals(retrievedFlowConfig.getProperties().get("param2"), "value2b");
    Assert.assertEquals(RequesterService.deserialize(retrievedFlowConfig.getProperties().get(RequesterService.REQUESTER_LIST)),
        Lists.newArrayList(TEST_REQUESTER));
  }

  @Test (dependsOnMethods = "testUpdate")
  public void testUnschedule() throws Exception {
    FlowId flowId = new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_12);
    Map<String, String> flowProperties = Maps.newHashMap();
    flowProperties.put("param1", "value1");
    flowProperties.put(ConfigurationKeys.FLOW_UNSCHEDULE_KEY, "true");

    FlowConfig flowConfig = new FlowConfig().setId(flowId)
        .setTemplateUris(TEST_TEMPLATE_URI).setSchedule(new Schedule().setCronSchedule(TEST_SCHEDULE).
            setRunImmediately(true))
        .setProperties(new StringMap(flowProperties));

    _client.updateFlowConfig(flowConfig);

    FlowConfig persistedFlowConfig = _client.getFlowConfig(flowId);

    Assert.assertFalse(persistedFlowConfig.getProperties().containsKey(ConfigurationKeys.FLOW_UNSCHEDULE_KEY));
    Assert.assertEquals(Objects.requireNonNull(persistedFlowConfig.getSchedule()).getCronSchedule(), FlowConfigsV2ResourceHandler.NEVER_RUN_CRON_SCHEDULE.getCronSchedule());
  }

  @Test (dependsOnMethods = "testUnschedule")
  public void testDelete() throws Exception {
    FlowId flowId = new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_12);

    // make sure flow config exists
    FlowConfig flowConfig = _client.getFlowConfig(flowId);
    Assert.assertEquals(flowConfig.getId().getFlowGroup(), TEST_GROUP_NAME);
    Assert.assertEquals(flowConfig.getId().getFlowName(), TEST_FLOW_NAME_12);

    _client.deleteFlowConfig(flowId);

    try {
      _client.getFlowConfig(flowId);
    } catch (RestLiResponseException e) {
      Assert.assertEquals(e.getStatus(), HttpStatus.S_404_NOT_FOUND.getCode());
      return;
    }

    Assert.fail("Get should have gotten a 404 error");
  }

  @Test
  public void testBadGet() throws Exception {
    FlowId flowId = new FlowId().setFlowGroup(TEST_DUMMY_GROUP_NAME).setFlowName(TEST_DUMMY_FLOW_NAME);

    try {
      _client.getFlowConfig(flowId);
    } catch (RestLiResponseException e) {
      Assert.assertEquals(e.getStatus(), HttpStatus.S_404_NOT_FOUND.getCode());
      return;
    }

    Assert.fail("Get should have raised a 404 error");
  }

  @Test
  public void testBadDelete() throws Exception {
    FlowId flowId = new FlowId().setFlowGroup(TEST_DUMMY_GROUP_NAME).setFlowName(TEST_DUMMY_FLOW_NAME);

    try {
      _client.getFlowConfig(flowId);
    } catch (RestLiResponseException e) {
      Assert.assertEquals(e.getStatus(), HttpStatus.S_404_NOT_FOUND.getCode());
      return;
    }

    Assert.fail("Get should have raised a 404 error");
  }

  @Test
  public void testBadUpdate() throws Exception {
    Map<String, String> flowProperties = Maps.newHashMap();
    flowProperties.put("param1", "value1b");
    flowProperties.put("param2", "value2b");

    FlowConfig flowConfig = new FlowConfig().setId(new FlowId().setFlowGroup(TEST_DUMMY_GROUP_NAME)
            .setFlowName(TEST_DUMMY_FLOW_NAME))
        .setTemplateUris(TEST_TEMPLATE_URI).setSchedule(new Schedule().setCronSchedule(TEST_SCHEDULE))
        .setProperties(new StringMap(flowProperties));

    try {
      _client.updateFlowConfig(flowConfig);
    } catch (RestLiResponseException e) {
      Assert.assertEquals(e.getStatus(), HttpStatus.S_404_NOT_FOUND.getCode());
      return;
    }

    Assert.fail("Update should have raised a 404 error");
  }

  @Test
  public void testCheckFlowExecutionId() throws Exception {
    Map<String, String> flowProperties = Maps.newHashMap();
    flowProperties.put("param1", "value1");

    FlowConfig flowConfig = new FlowConfig().setId(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME))
        .setTemplateUris(TEST_TEMPLATE_URI).setProperties(new StringMap(flowProperties));
    FlowStatusId flowStatusId =_client.createFlowConfig(flowConfig);
    Assert.assertEquals(TEST_GROUP_NAME, flowStatusId.getFlowGroup());
    Assert.assertEquals(TEST_FLOW_NAME, flowStatusId.getFlowName());
    Assert.assertTrue(flowStatusId.getFlowExecutionId() != -1);

    flowConfig = new FlowConfig().setId(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_2))
        .setTemplateUris(TEST_TEMPLATE_URI).setProperties(new StringMap(flowProperties))
        .setSchedule(new Schedule().setCronSchedule(TEST_SCHEDULE).setRunImmediately(true));
    Assert.assertEquals(_client.createFlowConfig(flowConfig).getFlowExecutionId().longValue(), -1L);
  }

  @Test (dependsOnMethods = "testDelete")
  public void testCreateRejectedWhenFailsCompilation() throws Exception {
    FlowId flowId = new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_10);
    _requesterService.setRequester(TEST_REQUESTER);

    Map<String, String> flowProperties = Maps.newHashMap();
    flowProperties.put("param1", "value1");
    flowProperties.put("param2", "value2");
    flowProperties.put("param3", "value3");

    FlowConfig flowConfig = new FlowConfig().setId(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_10))
        .setTemplateUris(TEST_TEMPLATE_URI).setSchedule(new Schedule().setCronSchedule(TEST_SCHEDULE).setRunImmediately(false))
        .setProperties(new StringMap(flowProperties));

    // inform mock that this flow should fail compilation
    _compilationFailureFlowPaths.add(String.format("/%s/%s", TEST_GROUP_NAME, TEST_FLOW_NAME_10));
    try {
      _client.createFlowConfig(flowConfig);
      Assert.fail("create seemingly accepted (despite anticipated flow compilation failure)");
    } catch (RestLiResponseException e) {
      Assert.assertEquals(e.getStatus(), HttpStatus.S_400_BAD_REQUEST.getCode());
      Assert.assertTrue(e.getMessage().contains("Flow was not compiled successfully."));
    }
  }

  @Test (dependsOnMethods = "testDelete")
  public void testPartialUpdate() throws Exception {
    FlowId flowId = new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_3);
    _requesterService.setRequester(TEST_REQUESTER);

    Map<String, String> flowProperties = Maps.newHashMap();
    flowProperties.put("param1", "value1");
    flowProperties.put("param2", "value2");
    flowProperties.put("param3", "value3");

    FlowConfig flowConfig = new FlowConfig().setId(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_3))
        .setTemplateUris(TEST_TEMPLATE_URI).setSchedule(new Schedule().setCronSchedule(TEST_SCHEDULE).setRunImmediately(false))
        .setProperties(new StringMap(flowProperties));

    // Set some initial config
    _client.createFlowConfig(flowConfig);

    // Change param2 to value4, delete param3
    String patchJson = "{\"schedule\":{\"$set\":{\"runImmediately\":true}},"
        + "\"properties\":{\"$set\":{\"param2\":\"value4\"},\"$delete\":[\"param3\"]}}";
    DataMap dataMap = DataMapUtils.readMap(IOUtils.toInputStream(patchJson));
    PatchRequest<FlowConfig> flowConfigPatch = PatchRequest.createFromPatchDocument(dataMap);

    _client.partialUpdateFlowConfig(flowId, flowConfigPatch);

    FlowConfig retrievedFlowConfig = _client.getFlowConfig(flowId);

    Assert.assertTrue(Objects.requireNonNull(retrievedFlowConfig.getSchedule()).isRunImmediately());
    Assert.assertEquals(retrievedFlowConfig.getProperties().get("param1"), "value1");
    Assert.assertEquals(retrievedFlowConfig.getProperties().get("param2"), "value4");
    Assert.assertFalse(retrievedFlowConfig.getProperties().containsKey("param3"));
  }

  @Test (expectedExceptions = RestLiResponseException.class, dependsOnMethods = "testDelete")
  public void testPartialUpdateNotPossibleWithoutCreateFirst() throws Exception {
    String flowName = TEST_FLOW_NAME + 3;
    FlowId flowId = new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(flowName);

    String patchJson = "{\"schedule\":{\"$set\":{\"runImmediately\":true}},"
        + "\"properties\":{\"$set\":{\"param2\":\"value4\"},\"$delete\":[\"param3\"]}}";
    DataMap dataMap = DataMapUtils.readMap(IOUtils.toInputStream(patchJson));
    PatchRequest<FlowConfig> flowConfigPatch = PatchRequest.createFromPatchDocument(dataMap);

    // Throws exception since flow was not created first, prior to partial update
    _client.partialUpdateFlowConfig(flowId, flowConfigPatch);
  }

  @Test (dependsOnMethods = "testDelete")
  public void testPartialUpdateRejectedWhenFailsCompilation() throws Exception {
    FlowId flowId = new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_11);
    _requesterService.setRequester(TEST_REQUESTER);

    Map<String, String> flowProperties = Maps.newHashMap();
    flowProperties.put("param1", "value1");
    flowProperties.put("param2", "value2");
    flowProperties.put("param3", "value3");

    FlowConfig flowConfig = new FlowConfig().setId(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_11))
        .setTemplateUris(TEST_TEMPLATE_URI).setSchedule(new Schedule().setCronSchedule(TEST_SCHEDULE).setRunImmediately(false))
        .setProperties(new StringMap(flowProperties));

    // Set some initial config
    _client.createFlowConfig(flowConfig);

    // Change param2 to value4, delete param3, add param5=value5
    String patchJson = "{\"schedule\":{\"$set\":{\"runImmediately\":true}},"
        + "\"properties\":{\"$set\":{\"param2\":\"value4\",\"param5\":\"value5\"},\"$delete\":[\"param3\"]}}";
    DataMap dataMap = DataMapUtils.readMap(IOUtils.toInputStream(patchJson));
    PatchRequest<FlowConfig> flowConfigPatch = PatchRequest.createFromPatchDocument(dataMap);

    // inform mock that this flow should hereafter fail compilation
    _compilationFailureFlowPaths.add(String.format("/%s/%s", TEST_GROUP_NAME, TEST_FLOW_NAME_11));
    try {
      _client.partialUpdateFlowConfig(flowId, flowConfigPatch);
      Assert.fail("update seemingly accepted (despite anticipated flow compilation failure)");
    } catch (RestLiResponseException e) {
      Assert.assertEquals(e.getStatus(), HttpStatus.S_400_BAD_REQUEST.getCode());
      Assert.assertTrue(e.getMessage().contains("Flow was not compiled successfully."));
    }

    // verify that prior state of flow config still retained: that updates had no effect
    FlowConfig retrievedFlowConfig = _client.getFlowConfig(flowId);

    Assert.assertFalse(retrievedFlowConfig.getSchedule().isRunImmediately());
    Assert.assertEquals(retrievedFlowConfig.getProperties().get("param1"), "value1");
    Assert.assertEquals(retrievedFlowConfig.getProperties().get("param2"), "value2");
    Assert.assertEquals(retrievedFlowConfig.getProperties().get("param3"), "value3");
    Assert.assertFalse(retrievedFlowConfig.getProperties().containsKey("param5"));
  }

  @Test (dependsOnMethods = "testDelete")
  public void testDisallowedRequester() throws Exception {
    try {
      ServiceRequester testRequester = new ServiceRequester("testName", "testType", "testFrom");
      _requesterService.setRequester(testRequester);

      Map<String, String> flowProperties = Maps.newHashMap();
      flowProperties.put("param1", "value1");

      FlowConfig flowConfig = new FlowConfig().setId(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_4))
          .setTemplateUris(TEST_TEMPLATE_URI)
          .setProperties(new StringMap(flowProperties));
      _client.createFlowConfig(flowConfig);

      testRequester.setName("testName2");
      _client.deleteFlowConfig(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_4));
    } catch (RestLiResponseException e) {
      Assert.assertEquals(e.getStatus(), HttpStatus.S_401_UNAUTHORIZED.getCode());
    }
  }

  @Test (dependsOnMethods = "testDelete")
  public void testGroupRequesterAllowed() throws Exception {
    ServiceRequester testRequester = new ServiceRequester("testName", "USER_PRINCIPAL", "testFrom");
    _requesterService.setRequester(testRequester);
    Map<String, String> flowProperties = Maps.newHashMap();

    FlowConfig flowConfig = new FlowConfig().setId(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_5))
        .setTemplateUris(TEST_TEMPLATE_URI)
        .setProperties(new StringMap(flowProperties))
        .setOwningGroup("testGroup");

     _client.createFlowConfig(flowConfig);

    testRequester.setName("testName2");
    _client.deleteFlowConfig(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_5));
  }

  @Test (dependsOnMethods = "testDelete")
  public void testGroupRequesterRejected() throws Exception {
    try {
      ServiceRequester testRequester = new ServiceRequester("testName", "USER_PRINCIPAL", "testFrom");
      _requesterService.setRequester(testRequester);
      Map<String, String> flowProperties = Maps.newHashMap();

      FlowConfig flowConfig = new FlowConfig().setId(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_6))
          .setTemplateUris(TEST_TEMPLATE_URI)
          .setProperties(new StringMap(flowProperties))
          .setOwningGroup("testGroup");

      _client.createFlowConfig(flowConfig);

      testRequester.setName("testName3");
      _client.deleteFlowConfig(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_6));
    } catch (RestLiResponseException e) {
      Assert.assertEquals(e.getStatus(), HttpStatus.S_401_UNAUTHORIZED.getCode());
    }
  }

  @Test (dependsOnMethods = "testDelete")
  public void testGroupUpdateRejected() throws Exception {
   _requesterService.setRequester(TEST_REQUESTER);
   Map<String, String> flowProperties = Maps.newHashMap();

   FlowConfig flowConfig = new FlowConfig().setId(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_7))
       .setTemplateUris(TEST_TEMPLATE_URI)
       .setProperties(new StringMap(flowProperties))
       .setOwningGroup("testGroup");

   _client.createFlowConfig(flowConfig);

   // Update should be rejected because testName is not part of dummyGroup
   flowConfig.setOwningGroup("dummyGroup");
   try {
     _client.updateFlowConfig(flowConfig);
     Assert.fail("Expected update to be rejected");
   } catch (RestLiResponseException e) {
     Assert.assertEquals(e.getStatus(), HttpStatus.S_401_UNAUTHORIZED.getCode());
   }
  }

  @Test (dependsOnMethods = "testDelete")
  public void testRequesterUpdate() throws Exception {
    _requesterService.setRequester(TEST_REQUESTER);
    Map<String, String> flowProperties = Maps.newHashMap();

    FlowId flowId = new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_8);
    FlowConfig flowConfig = new FlowConfig().setId(flowId)
        .setTemplateUris(TEST_TEMPLATE_URI)
        .setProperties(new StringMap(flowProperties))
        .setOwningGroup("testGroup");

    _client.createFlowConfig(flowConfig);

    // testName2 takes ownership of the flow
    flowProperties.put(RequesterService.REQUESTER_LIST, RequesterService.serialize(Lists.newArrayList(TEST_REQUESTER2)));
    flowConfig.setProperties(new StringMap(flowProperties));
    _requesterService.setRequester(TEST_REQUESTER2);
    _client.updateFlowConfig(flowConfig);

    // Check that the requester list was actually updated
    FlowConfig updatedFlowConfig = _client.getFlowConfig(flowId);
    Assert.assertEquals(RequesterService.deserialize(updatedFlowConfig.getProperties().get(RequesterService.REQUESTER_LIST)),
        Lists.newArrayList(TEST_REQUESTER2));
  }

  @Test (dependsOnMethods = "testDelete")
  public void testRequesterUpdateRejected() throws Exception {
    _requesterService.setRequester(TEST_REQUESTER);
    Map<String, String> flowProperties = Maps.newHashMap();

    FlowConfig flowConfig = new FlowConfig().setId(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(TEST_FLOW_NAME_9))
        .setTemplateUris(TEST_TEMPLATE_URI)
        .setProperties(new StringMap(flowProperties));

    _client.createFlowConfig(flowConfig);

    // Update should be rejected because testName is not allowed to update the owner to testName2
    flowProperties.put(RequesterService.REQUESTER_LIST, RequesterService.serialize(Lists.newArrayList(TEST_REQUESTER2)));
    flowConfig.setProperties(new StringMap(flowProperties));
    try {
      _client.updateFlowConfig(flowConfig);
      Assert.fail("Expected update to be rejected");
    } catch (RestLiResponseException e) {
      Assert.assertEquals(e.getStatus(), HttpStatus.S_401_UNAUTHORIZED.getCode());
    }
  }

  @Test (dependsOnMethods = "testDelete")
  public void testInvalidFlowId() throws Exception {
    Map<String, String> flowProperties = Maps.newHashMap();
    flowProperties.put("param1", "value1");
    StringBuilder sb1 = new StringBuilder();
    StringBuilder sb2 = new StringBuilder();
    int maxFlowNameLength = ServiceConfigKeys.MAX_FLOW_NAME_LENGTH;
    int maxFlowGroupLength = ServiceConfigKeys.MAX_FLOW_GROUP_LENGTH;
    while(maxFlowGroupLength-- >= 0) {
      sb1.append("A");
    }
    while(maxFlowNameLength-- >= 0) {
      sb2.append("A");
    }
    String TOO_LONG_FLOW_GROUP = sb1.toString();
    String TOO_LONG_FLOW_NAME = sb2.toString();

    FlowConfig flowConfig = new FlowConfig().setId(new FlowId().setFlowGroup(TOO_LONG_FLOW_GROUP).setFlowName(TOO_LONG_FLOW_NAME))
        .setTemplateUris(TEST_TEMPLATE_URI).setProperties(new StringMap(flowProperties));
    try {
      _client.createFlowConfig(flowConfig);
    } catch (RestLiResponseException e) {
      Assert.assertEquals(e.getStatus(), HttpStatus.S_422_UNPROCESSABLE_ENTITY.getCode());
      Assert.assertTrue(e.getMessage().contains("is out of range"));
      return;
    }

    Assert.fail();
  }

  @Test (dependsOnMethods = "testDelete")
  public void testRunFlow() throws Exception {
    String flowName = "testRunFlow";
    FlowId flowId = new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(flowName);
    _requesterService.setRequester(TEST_REQUESTER);

    Map<String, String> flowProperties = Maps.newHashMap();
    flowProperties.put("param1", "value1");

    FlowConfig flowConfig = new FlowConfig().setId(new FlowId().setFlowGroup(TEST_GROUP_NAME).setFlowName(flowName))
        .setTemplateUris(TEST_TEMPLATE_URI).setSchedule(new Schedule().setCronSchedule(TEST_SCHEDULE).setRunImmediately(false))
        .setProperties(new StringMap(flowProperties));

    // Create initial flowConfig
    _client.createFlowConfig(flowConfig);

    // Trigger flow
    _client.runImmediately(flowId);

    // Verify runImmediately was changed to true
    Assert.assertTrue(_client.getFlowConfig(flowId).getSchedule().isRunImmediately());
  }

  @AfterClass(alwaysRun = true)
  public void tearDown() throws Exception {
    if (_client != null) {
      _client.close();
    }
    if (_server != null) {
      _server.stopAsync();
      _server.awaitTerminated();
    }
    _testDirectory.delete();
    cleanUpDir(TEST_SPEC_STORE_DIR);
  }

  public static class TestRequesterService extends RequesterService {
    @Setter
    private ServiceRequester requester;

    public TestRequesterService(Config config) {
      super(config);
    }

    @Override
    public List<ServiceRequester> findRequesters(BaseResource resource) {
      return requester == null ? Lists.newArrayList() : Lists.newArrayList(requester);
    }

    @Override
    public boolean isRequesterAllowed(
        List<ServiceRequester> originalRequesterList, List<ServiceRequester> currentRequesterList) {
      for (ServiceRequester s: currentRequesterList) {
        if (originalRequesterList.contains(s)) {
          return true;
        }
      }
      return false;
    }
  }
}
