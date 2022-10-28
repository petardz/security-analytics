/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.securityanalytics.resthandler;

import org.apache.http.HttpStatus;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.junit.Assert;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.common.settings.Settings;
import org.opensearch.rest.RestStatus;
import org.opensearch.search.SearchHit;
import org.opensearch.securityanalytics.SecurityAnalyticsPlugin;
import org.opensearch.securityanalytics.SecurityAnalyticsRestTestCase;
import org.opensearch.securityanalytics.config.monitors.DetectorMonitorConfig;
import org.opensearch.securityanalytics.model.Detector;
import org.opensearch.securityanalytics.model.DetectorInput;
import org.opensearch.securityanalytics.model.DetectorRule;
import org.opensearch.securityanalytics.model.Rule;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.opensearch.securityanalytics.TestHelpers.randomDetectorWithInputs;
import static org.opensearch.securityanalytics.TestHelpers.randomDoc;
import static org.opensearch.securityanalytics.TestHelpers.randomEditedRule;
import static org.opensearch.securityanalytics.TestHelpers.randomIndex;
import static org.opensearch.securityanalytics.TestHelpers.randomRule;
import static org.opensearch.securityanalytics.TestHelpers.randomRuleWithErrors;
import static org.opensearch.securityanalytics.TestHelpers.windowsIndexMapping;

public class RuleRestApiIT extends SecurityAnalyticsRestTestCase {

        public void testCreatingARule_validationFail_noRuleIndex() throws IOException {

            String rule = randomRule();

            Response createResponse = makeRequest(client(), "POST", SecurityAnalyticsPlugin.RULE_BASE_URI, Collections.singletonMap("category", "windows"),
                    new StringEntity(rule), new BasicHeader("Content-Type", "application/json"));
            Assert.assertEquals("Create rule succeeded but it should've failed validation", RestStatus.INTERNAL_SERVER_ERROR, restStatus(createResponse));
            Map<String, Object> responseBody = asMap(createResponse);
            Assert.assertEquals("", RestStatus.INTERNAL_SERVER_ERROR, restStatus(createResponse));
        }

        public void testCreatingARule_validationFail_ruleFieldsMissingFromMappings() throws IOException {

            createIndex(
                    DetectorMonitorConfig.getRuleIndex(Detector.DetectorType.WINDOWS.getDetectorType()),
                    Settings.builder().put("index.hidden", true).build(),
                    "\"properties\": { \"dummy_field\":{\"type\":\"long\"}}"
            );

            String rule = randomRule();

            Response createResponse = makeRequest(client(), "POST", SecurityAnalyticsPlugin.RULE_BASE_URI, Collections.singletonMap("category", "windows"),
                    new StringEntity(rule), new BasicHeader("Content-Type", "application/json"));
            Assert.assertEquals("Create rule succeeded but it should've failed validation", RestStatus.INTERNAL_SERVER_ERROR, restStatus(createResponse));
            Map<String, Object> responseBody = asMap(createResponse);
        }

        public void testCreatingARule() throws IOException {

        createIndex(
                DetectorMonitorConfig.getRuleIndex(Detector.DetectorType.WINDOWS.getDetectorType()),
                Settings.builder().put("index.hidden", true).build(),
                "\"properties\": { \"event_uid\":{\"type\":\"long\"}}"
                );

        String rule = randomRule();

        Response createResponse = makeRequest(client(), "POST", SecurityAnalyticsPlugin.RULE_BASE_URI, Collections.singletonMap("category", "windows"),
                new StringEntity(rule), new BasicHeader("Content-Type", "application/json"));
        Assert.assertEquals("Create rule failed", RestStatus.CREATED, restStatus(createResponse));

        Map<String, Object> responseBody = asMap(createResponse);

        String createdId = responseBody.get("_id").toString();
        int createdVersion = Integer.parseInt(responseBody.get("_version").toString());
        Assert.assertNotEquals("response is missing Id", Detector.NO_ID, createdId);
        Assert.assertTrue("incorrect version", createdVersion > 0);
        Assert.assertEquals("Incorrect Location header", String.format(Locale.getDefault(), "%s/%s", SecurityAnalyticsPlugin.RULE_BASE_URI, createdId), createResponse.getHeader("Location"));

        String index = Rule.CUSTOM_RULES_INDEX;
        String request = "{\n" +
                "  \"query\": {\n" +
                "    \"nested\": {\n" +
                "      \"path\": \"rule\",\n" +
                "      \"query\": {\n" +
                "        \"bool\": {\n" +
                "          \"must\": [\n" +
                "            { \"match\": {\"rule.category\": \"windows\"}}\n" +
                "          ]\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";
        List<SearchHit> hits = executeSearch(index, request);
        Assert.assertEquals(1, hits.size());

        request = "{\n" +
                "  \"query\": {\n" +
                "    \"nested\": {\n" +
                "      \"path\": \"rule\",\n" +
                "      \"query\": {\n" +
                "        \"bool\": {\n" +
                "          \"must\": [\n" +
                "            { \"match\": {\"rule.category\": \"application\"}}\n" +
                "          ]\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";
        hits = executeSearch(index, request);
        Assert.assertEquals(0, hits.size());
    }

    @SuppressWarnings("unchecked")
    public void testCreatingARuleWithWrongSyntax() throws IOException {
        String rule = randomRuleWithErrors();

        try {
            makeRequest(client(), "POST", SecurityAnalyticsPlugin.RULE_BASE_URI, Collections.singletonMap("category", "windows"),
                    new StringEntity(rule), new BasicHeader("Content-Type", "application/json"));
        } catch (ResponseException ex) {
            Map<String, Object> responseBody = asMap(ex.getResponse());
            String reason = ((Map<String, Object>) responseBody.get("error")).get("reason").toString();
            Assert.assertEquals("{\"error\":\"Sigma rule must have a log source\",\"error\":\"Sigma rule must have a detection definitions\"}", reason);
        }
    }

    @SuppressWarnings("unchecked")
    public void testSearchingPrepackagedRules() throws IOException {
        String request = "{\n" +
                "  \"query\": {\n" +
                "    \"nested\": {\n" +
                "      \"path\": \"rule\",\n" +
                "      \"query\": {\n" +
                "        \"bool\": {\n" +
                "          \"must\": [\n" +
                "            { \"match\": {\"rule.category\": \"windows\"}}\n" +
                "          ]\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        Response searchResponse = makeRequest(client(), "POST", String.format(Locale.getDefault(), "%s/_search", SecurityAnalyticsPlugin.RULE_BASE_URI), Collections.singletonMap("pre_packaged", "true"),
                new StringEntity(request), new BasicHeader("Content-Type", "application/json"));
        Assert.assertEquals("Searching rules failed", RestStatus.OK, restStatus(searchResponse));

        Map<String, Object> responseBody = asMap(searchResponse);
        Assert.assertEquals(1579, ((Map<String, Object>) ((Map<String, Object>) responseBody.get("hits")).get("total")).get("value"));
    }

    @SuppressWarnings("unchecked")
    public void testSearchingPrepackagedRulesByMitreAttackID() throws IOException {
        String request = "{\n" +
                "  \"query\": {\n" +
                "    \"nested\": {\n" +
                "      \"path\": \"rule.references\",\n" +
                "      \"query\": {\n" +
                "        \"bool\": {\n" +
                "          \"must\": [\n" +
                "            { \"match\": {\"rule.references.value\": \"TA0008\"}}\n" +
                "          ]\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        Response searchResponse = makeRequest(client(), "POST", String.format(Locale.getDefault(), "%s/_search", SecurityAnalyticsPlugin.RULE_BASE_URI), Collections.singletonMap("pre_packaged", "true"),
                new StringEntity(request), new BasicHeader("Content-Type", "application/json"));
        Assert.assertEquals("Searching rules failed", RestStatus.OK, restStatus(searchResponse));

        Map<String, Object> responseBody = asMap(searchResponse);
        Assert.assertEquals(9, ((Map<String, Object>) ((Map<String, Object>) responseBody.get("hits")).get("total")).get("value"));
    }

    @SuppressWarnings("unchecked")
    public void testSearchingPrepackagedRulesByPages() throws IOException {
        String request = "{\n" +
                "  \"from\": 10\n," +
                "  \"size\": 20\n," +
                "  \"query\": {\n" +
                "    \"nested\": {\n" +
                "      \"path\": \"rule\",\n" +
                "      \"query\": {\n" +
                "        \"bool\": {\n" +
                "          \"must\": [\n" +
                "            { \"match\": {\"rule.category\": \"windows\"}}\n" +
                "          ]\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        Response searchResponse = makeRequest(client(), "POST", String.format(Locale.getDefault(), "%s/_search", SecurityAnalyticsPlugin.RULE_BASE_URI), Collections.singletonMap("pre_packaged", "true"),
                new StringEntity(request), new BasicHeader("Content-Type", "application/json"));
        Assert.assertEquals("Searching rules failed", RestStatus.OK, restStatus(searchResponse));

        Map<String, Object> responseBody = asMap(searchResponse);
        Assert.assertEquals(20, ((List<SearchHit>) ((Map<String, Object>) responseBody.get("hits")).get("hits")).size());
    }

    @SuppressWarnings("unchecked")
    public void testSearchingPrepackagedRulesByAuthor() throws IOException {
        String request = "{\n" +
                "  \"query\": {\n" +
                "    \"nested\": {\n" +
                "      \"path\": \"rule\",\n" +
                "      \"query\": {\n" +
                "        \"bool\": {\n" +
                "          \"must\": [\n" +
                "            { \"match\": {\"rule.author\": \"Sagie Dulce\"}}\n" +
                "          ]\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        Response searchResponse = makeRequest(client(), "POST", String.format(Locale.getDefault(), "%s/_search", SecurityAnalyticsPlugin.RULE_BASE_URI), Collections.singletonMap("pre_packaged", "true"),
                new StringEntity(request), new BasicHeader("Content-Type", "application/json"));
        Assert.assertEquals("Searching rules failed", RestStatus.OK, restStatus(searchResponse));

        Map<String, Object> responseBody = asMap(searchResponse);
        Assert.assertEquals(17, ((Map<String, Object>) ((Map<String, Object>) responseBody.get("hits")).get("total")).get("value"));
    }

    @SuppressWarnings("unchecked")
    public void testSearchingCustomRules() throws IOException {

        String fieldMapping = "{\"properties\": { \"event_uid\":{\"type\":\"long\"}}}";
        createRuleTopicIndex(Detector.DetectorType.WINDOWS.getDetectorType(), fieldMapping);

        String rule = randomRule();

        Response createResponse = makeRequest(client(), "POST", SecurityAnalyticsPlugin.RULE_BASE_URI, Collections.singletonMap("category", "windows"),
                new StringEntity(rule), new BasicHeader("Content-Type", "application/json"));
        Assert.assertEquals("Create rule failed", RestStatus.CREATED, restStatus(createResponse));

        String request = "{\n" +
                "  \"query\": {\n" +
                "    \"nested\": {\n" +
                "      \"path\": \"rule\",\n" +
                "      \"query\": {\n" +
                "        \"bool\": {\n" +
                "          \"must\": [\n" +
                "            { \"match\": {\"rule.category\": \"windows\"}}\n" +
                "          ]\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        Response searchResponse = makeRequest(client(), "POST", String.format(Locale.getDefault(), "%s/_search", SecurityAnalyticsPlugin.RULE_BASE_URI), Collections.singletonMap("pre_packaged", "false"),
                new StringEntity(request), new BasicHeader("Content-Type", "application/json"));
        Assert.assertEquals("Searching rules failed", RestStatus.OK, restStatus(searchResponse));

        Map<String, Object> responseBody = asMap(searchResponse);
        Assert.assertEquals(1, ((Map<String, Object>) ((Map<String, Object>) responseBody.get("hits")).get("total")).get("value"));
    }

    public void testUpdatingUnusedRule() throws IOException {

        String fieldMapping = "{\"properties\": { \"event_uid\":{\"type\":\"long\"}}}";
        createRuleTopicIndex(Detector.DetectorType.WINDOWS.getDetectorType(), fieldMapping);

        String index = createTestIndex(randomIndex(), windowsIndexMapping());

        // Execute CreateMappingsAction to add alias mapping for index
        Request createMappingRequest = new Request("POST", SecurityAnalyticsPlugin.MAPPER_BASE_URI);
        // both req params and req body are supported
        createMappingRequest.setJsonEntity(
                "{ \"index_name\":\"" + index + "\"," +
                        "  \"rule_topic\":\"windows\", " +
                        "  \"partial\":true" +
                        "}"
        );

        Response response = client().performRequest(createMappingRequest);
        assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());

        String rule = randomRule();

        Response createResponse = makeRequest(client(), "POST", SecurityAnalyticsPlugin.RULE_BASE_URI, Collections.singletonMap("category", "windows"),
                new StringEntity(rule), new BasicHeader("Content-Type", "application/json"));
        Assert.assertEquals("Create rule failed", RestStatus.CREATED, restStatus(createResponse));

        Map<String, Object> responseBody = asMap(createResponse);
        String createdId = responseBody.get("_id").toString();

        Response updateResponse = makeRequest(client(), "PUT", SecurityAnalyticsPlugin.RULE_BASE_URI + "/" + createdId, Map.of("category", "windows"),
                new StringEntity(randomEditedRule()), new BasicHeader("Content-Type", "application/json"));
        Assert.assertEquals("Update rule failed", RestStatus.OK, restStatus(updateResponse));
    }

    public void testUpdatingUnusedRuleAfterDetectorIndexCreated() throws IOException {

        String fieldMapping = "{\"properties\": { \"event_uid\":{\"type\":\"long\"}}}";
        createRuleTopicIndex(Detector.DetectorType.WINDOWS.getDetectorType(), fieldMapping);

        String index = createTestIndex(randomIndex(), windowsIndexMapping());

        // Execute CreateMappingsAction to add alias mapping for index
        Request createMappingRequest = new Request("POST", SecurityAnalyticsPlugin.MAPPER_BASE_URI);
        // both req params and req body are supported
        createMappingRequest.setJsonEntity(
                "{ \"index_name\":\"" + index + "\"," +
                        "  \"rule_topic\":\"windows\", " +
                        "  \"partial\":true" +
                        "}"
        );

        Response response = client().performRequest(createMappingRequest);
        assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());

        String rule = randomRule();

        Response createResponse = makeRequest(client(), "POST", SecurityAnalyticsPlugin.RULE_BASE_URI, Collections.singletonMap("category", "windows"),
                new StringEntity(rule), new BasicHeader("Content-Type", "application/json"));
        Assert.assertEquals("Create rule failed", RestStatus.CREATED, restStatus(createResponse));

        Map<String, Object> responseBody = asMap(createResponse);

        String createdId = responseBody.get("_id").toString();

        DetectorInput input = new DetectorInput("windows detector for security analytics", List.of("windows"), List.of(),
                getRandomPrePackagedRules().stream().map(DetectorRule::new).collect(Collectors.toList()));
        Detector detector = randomDetectorWithInputs(List.of(input));

        createResponse = makeRequest(client(), "POST", SecurityAnalyticsPlugin.DETECTOR_BASE_URI, Collections.emptyMap(), toHttpEntity(detector));
        Assert.assertEquals("Create detector failed", RestStatus.CREATED, restStatus(createResponse));

        Response updateResponse = makeRequest(client(), "PUT", SecurityAnalyticsPlugin.RULE_BASE_URI + "/" + createdId, Map.of("category", "windows"),
                new StringEntity(randomEditedRule()), new BasicHeader("Content-Type", "application/json"));
        Assert.assertEquals("Update rule failed", RestStatus.OK, restStatus(updateResponse));
    }

    @SuppressWarnings("unchecked")
    public void testUpdatingUsedRule() throws IOException {

        String fieldMapping = "{\"properties\": { \"event_uid\":{\"type\":\"long\"}}}";
        createRuleTopicIndex(Detector.DetectorType.WINDOWS.getDetectorType(), fieldMapping);

        String index = createTestIndex(randomIndex(), windowsIndexMapping());

        // Execute CreateMappingsAction to add alias mapping for index
        Request createMappingRequest = new Request("POST", SecurityAnalyticsPlugin.MAPPER_BASE_URI);
        // both req params and req body are supported
        createMappingRequest.setJsonEntity(
                "{ \"index_name\":\"" + index + "\"," +
                        "  \"rule_topic\":\"windows\", " +
                        "  \"partial\":true" +
                        "}"
        );

        Response response = client().performRequest(createMappingRequest);
        assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());

        String rule = randomRule();

        Response createResponse = makeRequest(client(), "POST", SecurityAnalyticsPlugin.RULE_BASE_URI, Collections.singletonMap("category", "windows"),
                new StringEntity(rule), new BasicHeader("Content-Type", "application/json"));
        Assert.assertEquals("Create rule failed", RestStatus.CREATED, restStatus(createResponse));

        Map<String, Object> responseBody = asMap(createResponse);

        String createdId = responseBody.get("_id").toString();

        DetectorInput input = new DetectorInput("windows detector for security analytics", List.of("windows"), List.of(new DetectorRule(createdId)),
                getRandomPrePackagedRules().stream().map(DetectorRule::new).collect(Collectors.toList()));
        Detector detector = randomDetectorWithInputs(List.of(input));

        createResponse = makeRequest(client(), "POST", SecurityAnalyticsPlugin.DETECTOR_BASE_URI, Collections.emptyMap(), toHttpEntity(detector));
        Assert.assertEquals("Create detector failed", RestStatus.CREATED, restStatus(createResponse));
        responseBody = asMap(createResponse);
        String detectorId = responseBody.get("_id").toString();

        createResponse = makeRequest(client(), "POST", SecurityAnalyticsPlugin.DETECTOR_BASE_URI, Collections.emptyMap(), toHttpEntity(detector));
        Assert.assertEquals("Create detector failed", RestStatus.CREATED, restStatus(createResponse));

        String request = "{\n" +
                "   \"query\" : {\n" +
                "     \"match\":{\n" +
                "        \"_id\": \"" + detectorId + "\"\n" +
                "     }\n" +
                "   }\n" +
                "}";
        List<SearchHit> hits = executeSearch(Detector.DETECTORS_INDEX, request);
        SearchHit hit = hits.get(0);

        String monitorId = ((List<String>) ((Map<String, Object>) hit.getSourceAsMap().get("detector")).get("monitor_id")).get(0);

        indexDoc(index, "1", randomDoc());

        Response executeResponse = executeAlertingMonitor(monitorId, Collections.emptyMap());
        Map<String, Object> executeResults = entityAsMap(executeResponse);

        int noOfSigmaRuleMatches = ((List<Map<String, Object>>) ((Map<String, Object>) executeResults.get("input_results")).get("results")).get(0).size();
        Assert.assertEquals(6, noOfSigmaRuleMatches);

        try {
            makeRequest(client(), "PUT", SecurityAnalyticsPlugin.RULE_BASE_URI + "/" + createdId, Collections.singletonMap("category", "windows"),
                    new StringEntity(randomEditedRule()), new BasicHeader("Content-Type", "application/json"));
        } catch (ResponseException ex) {
            Assert.assertTrue(new String(ex.getResponse().getEntity().getContent().readAllBytes())
                    .contains(String.format(Locale.getDefault(), "Rule with id %s is actively used by detectors. Update can be forced by setting forced flag to true", createdId)));
        }

        Response updateResponse = makeRequest(client(), "PUT", SecurityAnalyticsPlugin.RULE_BASE_URI + "/" + createdId, Map.of("category", "windows", "forced", "true"),
                new StringEntity(randomEditedRule()), new BasicHeader("Content-Type", "application/json"));
        Assert.assertEquals("Update rule failed", RestStatus.OK, restStatus(updateResponse));

        request = "{\n" +
                "   \"query\" : {\n" +
                "     \"match\":{\n" +
                "        \"_id\": \"" + detectorId + "\"\n" +
                "     }\n" +
                "   }\n" +
                "}";
        hits = executeSearch(Detector.DETECTORS_INDEX, request);
        hit = hits.get(0);

        monitorId = ((List<String>) ((Map<String, Object>) hit.getSourceAsMap().get("detector")).get("monitor_id")).get(0);

        indexDoc(index, "2", randomDoc());

        executeResponse = executeAlertingMonitor(monitorId, Collections.emptyMap());
        executeResults = entityAsMap(executeResponse);

        noOfSigmaRuleMatches = ((List<Map<String, Object>>) ((Map<String, Object>) executeResults.get("input_results")).get("results")).get(0).size();
        Assert.assertEquals(5, noOfSigmaRuleMatches);
    }

    public void testDeletingUnusedRule() throws IOException {

        String fieldMapping = "{\"properties\": { \"event_uid\":{\"type\":\"long\"}}}";
        createRuleTopicIndex(Detector.DetectorType.WINDOWS.getDetectorType(), fieldMapping);

        String index = createTestIndex(randomIndex(), windowsIndexMapping());

        // Execute CreateMappingsAction to add alias mapping for index
        Request createMappingRequest = new Request("POST", SecurityAnalyticsPlugin.MAPPER_BASE_URI);
        // both req params and req body are supported
        createMappingRequest.setJsonEntity(
                "{ \"index_name\":\"" + index + "\"," +
                        "  \"rule_topic\":\"windows\", " +
                        "  \"partial\":true" +
                        "}"
        );

        Response response = client().performRequest(createMappingRequest);
        assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());

        String rule = randomRule();

        Response createResponse = makeRequest(client(), "POST", SecurityAnalyticsPlugin.RULE_BASE_URI, Collections.singletonMap("category", "windows"),
                new StringEntity(rule), new BasicHeader("Content-Type", "application/json"));
        Assert.assertEquals("Create rule failed", RestStatus.CREATED, restStatus(createResponse));

        Map<String, Object> responseBody = asMap(createResponse);
        String createdId = responseBody.get("_id").toString();

        Response deleteResponse = makeRequest(client(), "DELETE", SecurityAnalyticsPlugin.RULE_BASE_URI + "/" + createdId, Collections.emptyMap(), null);
        Assert.assertEquals("Delete rule failed", RestStatus.OK, restStatus(deleteResponse));
    }

    public void testDeletingUnusedRuleAfterDetectorIndexCreated() throws IOException {

        String fieldMapping = "{\"properties\": { \"event_uid\":{\"type\":\"long\"}}}";
        createRuleTopicIndex(Detector.DetectorType.WINDOWS.getDetectorType(), fieldMapping);

        String index = createTestIndex(randomIndex(), windowsIndexMapping());

        // Execute CreateMappingsAction to add alias mapping for index
        Request createMappingRequest = new Request("POST", SecurityAnalyticsPlugin.MAPPER_BASE_URI);
        // both req params and req body are supported
        createMappingRequest.setJsonEntity(
                "{ \"index_name\":\"" + index + "\"," +
                        "  \"rule_topic\":\"windows\", " +
                        "  \"partial\":true" +
                        "}"
        );

        Response response = client().performRequest(createMappingRequest);
        assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());

        String rule = randomRule();

        Response createResponse = makeRequest(client(), "POST", SecurityAnalyticsPlugin.RULE_BASE_URI, Collections.singletonMap("category", "windows"),
                new StringEntity(rule), new BasicHeader("Content-Type", "application/json"));
        Assert.assertEquals("Create rule failed", RestStatus.CREATED, restStatus(createResponse));

        Map<String, Object> responseBody = asMap(createResponse);
        String createdId = responseBody.get("_id").toString();

        DetectorInput input = new DetectorInput("windows detector for security analytics", List.of("windows"), List.of(),
                getRandomPrePackagedRules().stream().map(DetectorRule::new).collect(Collectors.toList()));
        Detector detector = randomDetectorWithInputs(List.of(input));

        createResponse = makeRequest(client(), "POST", SecurityAnalyticsPlugin.DETECTOR_BASE_URI, Collections.emptyMap(), toHttpEntity(detector));
        Assert.assertEquals("Create detector failed", RestStatus.CREATED, restStatus(createResponse));

        Response deleteResponse = makeRequest(client(), "DELETE", SecurityAnalyticsPlugin.RULE_BASE_URI + "/" + createdId, Collections.emptyMap(), null);
        Assert.assertEquals("Delete rule failed", RestStatus.OK, restStatus(deleteResponse));
    }

    public void testDeletingUsedRule() throws IOException {

        String fieldMapping = "{\"properties\": { \"event_uid\":{\"type\":\"long\"}}}";
        createRuleTopicIndex(Detector.DetectorType.WINDOWS.getDetectorType(), fieldMapping);

        String index = createTestIndex(randomIndex(), windowsIndexMapping());

        // Execute CreateMappingsAction to add alias mapping for index
        Request createMappingRequest = new Request("POST", SecurityAnalyticsPlugin.MAPPER_BASE_URI);
        // both req params and req body are supported
        createMappingRequest.setJsonEntity(
                "{ \"index_name\":\"" + index + "\"," +
                        "  \"rule_topic\":\"windows\", " +
                        "  \"partial\":true" +
                        "}"
        );

        Response response = client().performRequest(createMappingRequest);
        assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());

        String rule = randomRule();

        Response createResponse = makeRequest(client(), "POST", SecurityAnalyticsPlugin.RULE_BASE_URI, Collections.singletonMap("category", "windows"),
                new StringEntity(rule), new BasicHeader("Content-Type", "application/json"));
        Assert.assertEquals("Create rule failed", RestStatus.CREATED, restStatus(createResponse));

        Map<String, Object> responseBody = asMap(createResponse);

        String createdId = responseBody.get("_id").toString();

        DetectorInput input = new DetectorInput("windows detector for security analytics", List.of("windows"), List.of(new DetectorRule(createdId)),
                getRandomPrePackagedRules().stream().map(DetectorRule::new).collect(Collectors.toList()));
        Detector detector = randomDetectorWithInputs(List.of(input));

        createResponse = makeRequest(client(), "POST", SecurityAnalyticsPlugin.DETECTOR_BASE_URI, Collections.emptyMap(), toHttpEntity(detector));
        Assert.assertEquals("Create detector failed", RestStatus.CREATED, restStatus(createResponse));

        createResponse = makeRequest(client(), "POST", SecurityAnalyticsPlugin.DETECTOR_BASE_URI, Collections.emptyMap(), toHttpEntity(detector));
        Assert.assertEquals("Create detector failed", RestStatus.CREATED, restStatus(createResponse));

        try {
            makeRequest(client(), "DELETE", SecurityAnalyticsPlugin.RULE_BASE_URI + "/" + createdId, Collections.emptyMap(), null);
        } catch (ResponseException ex) {
            Assert.assertTrue(new String(ex.getResponse().getEntity().getContent().readAllBytes())
                    .contains(String.format(Locale.getDefault(), "Rule with id %s is actively used by detectors. Deletion can be forced by setting forced flag to true", createdId)));
        }

        String request = "{\n" +
                "  \"query\": {\n" +
                "    \"script\": {\n" +
                "      \"script\": \"doc['_id'][0].indexOf('" + createdId + "') > -1\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
        List<SearchHit> hits = executeSearch(DetectorMonitorConfig.getRuleIndex("windows"), request);
        Assert.assertEquals(2, hits.size());

        Response deleteResponse = makeRequest(client(), "DELETE", SecurityAnalyticsPlugin.RULE_BASE_URI + "/" + createdId, Collections.singletonMap("forced", "true"), null);
        Assert.assertEquals("Delete rule failed", RestStatus.OK, restStatus(deleteResponse));

        request = "{\n" +
                "  \"query\": {\n" +
                "    \"script\": {\n" +
                "      \"script\": \"doc['_id'][0].indexOf('" + createdId + "') > -1\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
        hits = executeSearch(DetectorMonitorConfig.getRuleIndex("windows"), request);
        Assert.assertEquals(0, hits.size());

        index = Rule.CUSTOM_RULES_INDEX;
        request = "{\n" +
                "  \"query\": {\n" +
                "    \"nested\": {\n" +
                "      \"path\": \"rule\",\n" +
                "      \"query\": {\n" +
                "        \"bool\": {\n" +
                "          \"must\": [\n" +
                "            { \"match\": {\"rule.category\": \"windows\"}}\n" +
                "          ]\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";
        hits = executeSearch(index, request);
        Assert.assertEquals(0, hits.size());
    }
}