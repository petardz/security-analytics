/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.securityanalytics.findings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.ActionListener;
import org.opensearch.client.Client;
import org.opensearch.client.node.NodeClient;
import org.opensearch.commons.alerting.AlertingPluginInterface;
import org.opensearch.commons.alerting.model.DocLevelQuery;
import org.opensearch.commons.alerting.model.FindingWithDocs;
import org.opensearch.commons.alerting.model.Table;
import org.opensearch.rest.RestStatus;
import org.opensearch.securityanalytics.action.FindingDto;
import org.opensearch.securityanalytics.action.GetDetectorAction;
import org.opensearch.securityanalytics.action.GetDetectorRequest;
import org.opensearch.securityanalytics.action.GetDetectorResponse;
import org.opensearch.securityanalytics.action.GetFindingsResponse;
import org.opensearch.securityanalytics.config.monitors.DetectorMonitorConfig;
import org.opensearch.securityanalytics.model.Detector;
import org.opensearch.securityanalytics.util.SecurityAnalyticsException;

/**
 * Implements searching/fetching of findings
 */
public class FindingsService {

    private Client client;

    private static final Logger log = LogManager.getLogger(FindingsService.class);


    public FindingsService() {}

    public FindingsService(Client client) {
        this.client = client;
    }

    /**
     * Searches findings generated by specific Detector
     * @param detectorId id of Detector
     * @param table group of search related parameters
     * @param listener ActionListener to get notified on response or error
     */
    public void getFindingsByDetectorId(String detectorId, Table table, ActionListener<GetFindingsResponse> listener ) {
        this.client.execute(GetDetectorAction.INSTANCE, new GetDetectorRequest(detectorId, -3L), new ActionListener<>() {

            @Override
            public void onResponse(GetDetectorResponse getDetectorResponse) {
                // Get all monitor ids from detector
                Detector detector = getDetectorResponse.getDetector();
                List<String> monitorIds = detector.getMonitorIds();
                ActionListener<GetFindingsResponse> getFindingsResponseListener = new ActionListener<>() {
                    @Override
                    public void onResponse(GetFindingsResponse resp) {
                        Integer totalFindings = 0;
                        List<FindingDto> findings = new ArrayList<>();
                        // Merge all findings into one response
                        totalFindings += resp.getTotalFindings();
                        findings.addAll(resp.getFindings());

                        GetFindingsResponse masterResponse = new GetFindingsResponse(
                                totalFindings,
                                findings
                        );
                        // Send master response back
                        listener.onResponse(masterResponse);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        log.error("Failed to fetch findings for detector " + detectorId, e);
                        listener.onFailure(SecurityAnalyticsException.wrap(e));
                    }
                };

                // monitor --> detectorId mapping
                Map<String, Detector> monitorToDetectorMapping = new HashMap<>();
                detector.getMonitorIds().forEach(
                        monitorId -> monitorToDetectorMapping.put(monitorId, detector)
                );
                // Get findings for all monitor ids
                FindingsService.this.getFindingsByMonitorIds(
                        monitorToDetectorMapping,
                        monitorIds,
                        DetectorMonitorConfig.getAllFindingsIndicesPattern(detector.getDetectorType()),
                        table,
                        getFindingsResponseListener
                );
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }
        });
    }

    /**
     * Searches findings generated by specific Monitor
     * @param monitorToDetectorMapping monitorId --&gt; detectorId mapper
     * @param monitorIds id of Monitor
     * @param table group of search related parameters
     * @param listener ActionListener to get notified on response or error
     */
    public void getFindingsByMonitorIds(
            Map<String, Detector> monitorToDetectorMapping,
            List<String> monitorIds,
            String findingIndexName,
            Table table,
            ActionListener<GetFindingsResponse> listener
    ) {

        org.opensearch.commons.alerting.action.GetFindingsRequest req =
                new org.opensearch.commons.alerting.action.GetFindingsRequest(
                null,
                table,
                null,
                findingIndexName,
                monitorIds
        );

        AlertingPluginInterface.INSTANCE.getFindings((NodeClient) client, req, new ActionListener<>() {
                    @Override
                    public void onResponse(
                            org.opensearch.commons.alerting.action.GetFindingsResponse getFindingsResponse
                    ) {
                        // Convert response to SA's GetFindingsResponse
                        listener.onResponse(new GetFindingsResponse(
                                getFindingsResponse.getTotalFindings(),
                                getFindingsResponse.getFindings()
                                        .stream().map(e -> mapFindingWithDocsToFindingDto(
                                                e,
                                                monitorToDetectorMapping.get(e.getFinding().getMonitorId())
                                        )).collect(Collectors.toList())
                        ));
                    }

                    @Override
                    public void onFailure(Exception e) {
                        listener.onFailure(e);
                    }
                }
        );

     }

    void setIndicesAdminClient(Client client) {
        this.client = client;
    }

    public void getFindings(
            List<Detector> detectors,
            Detector.DetectorType detectorType,
            Table table,
            ActionListener<GetFindingsResponse> listener
    ) {
        if (detectors.size() == 0) {
            throw new OpenSearchStatusException("detector list is empty!", RestStatus.NOT_FOUND);
        }

        List<String> allMonitorIds = new ArrayList<>();
        // Used to convert monitorId back to detectorId to store in result FindingDto
        Map<String, Detector> monitorToDetectorMapping = new HashMap<>();
        detectors.forEach(detector -> {
            // monitor --> detector map
            detector.getMonitorIds().forEach(
                monitorId -> monitorToDetectorMapping.put(monitorId, detector)
            );
            // all monitorIds
            allMonitorIds.addAll(detector.getMonitorIds());
        });

         // Execute GetFindingsAction
        FindingsService.this.getFindingsByMonitorIds(
            monitorToDetectorMapping,
            allMonitorIds,
            DetectorMonitorConfig.getAllFindingsIndicesPattern(detectorType.getDetectorType()),
            table,
            new ActionListener<>() {
                @Override
                public void onResponse(GetFindingsResponse getFindingsResponse) {
                    listener.onResponse(getFindingsResponse);
                }

                @Override
                public void onFailure(Exception e) {
                    log.error("Failed to fetch findings for detectors: [" +
                            detectors.stream().map(d -> d.getId()).collect(Collectors.joining(",")) + "]", e);
                    listener.onFailure(SecurityAnalyticsException.wrap(e));
                }
            }
        );
    }

    public FindingDto mapFindingWithDocsToFindingDto(FindingWithDocs findingWithDocs, Detector detector) {
        List<DocLevelQuery> docLevelQueries = findingWithDocs.getFinding().getDocLevelQueries();
        if (docLevelQueries.isEmpty()) { // this is finding generated by a bucket level monitor
            for (Map.Entry<String, String> entry : detector.getRuleIdMonitorIdMap().entrySet()) {
                if(entry.getValue().equals(findingWithDocs.getFinding().getMonitorId())) {
                    docLevelQueries = Collections.singletonList(new DocLevelQuery(entry.getKey(),"","",Collections.emptyList()));
                }
            }
        }
        return new FindingDto(
                detector.getId(),
                findingWithDocs.getFinding().getId(),
                findingWithDocs.getFinding().getRelatedDocIds(),
                findingWithDocs.getFinding().getIndex(),
                docLevelQueries,
                findingWithDocs.getFinding().getTimestamp(),
                findingWithDocs.getDocuments()
        );
    }
}
