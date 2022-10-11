/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.securityanalytics.alerts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.ActionListener;
import org.opensearch.action.support.GroupedActionListener;
import org.opensearch.client.Client;
import org.opensearch.client.node.NodeClient;
import org.opensearch.commons.alerting.AlertingPluginInterface;
import org.opensearch.commons.alerting.model.Alert;
import org.opensearch.commons.alerting.model.Table;
import org.opensearch.securityanalytics.action.GetAlertsResponse;
import org.opensearch.securityanalytics.action.GetDetectorAction;
import org.opensearch.securityanalytics.action.GetDetectorRequest;
import org.opensearch.securityanalytics.action.GetDetectorResponse;
import org.opensearch.securityanalytics.util.SecurityAnalyticsException;

/**
 * Implements searching/fetching of findings
 */
public class AlertsService {

    private Client client;

    private static final Logger log = LogManager.getLogger(AlertsService.class);


    public AlertsService() {}

    public AlertsService(Client client) {
        this.client = client;
    }

    /**
     * Searches alerts generated by specific Detector
     * @param detectorId id of Detector
     * @param table group of search related parameters
     * @param listener ActionListener to get notified on response or error
     */
    public void getAlertsByDetectorId(
            String detectorId,
            Table table,
            String severityLevel,
            String alertState,
            ActionListener<GetAlertsResponse> listener
    ) {
        this.client.execute(GetDetectorAction.INSTANCE, new GetDetectorRequest(detectorId, -3L), new ActionListener<>() {

            @Override
            public void onResponse(GetDetectorResponse getDetectorResponse) {
                // Get all monitor ids from detector
                List<String> monitorIds = getDetectorResponse.getDetector().getMonitorIds();
                // Using GroupedActionListener here as we're going to issue one GetFindingsActions for each monitorId
                ActionListener<GetAlertsResponse> multiGetAlertsListener = new GroupedActionListener<>(new ActionListener<>() {
                    @Override
                    public void onResponse(Collection<GetAlertsResponse> responses) {
                        Integer totalAlerts = 0;
                        List<Alert> alerts = new ArrayList<>();
                        // Merge all findings into one response
                        for(GetAlertsResponse resp : responses) {
                            totalAlerts += resp.getTotalAlerts();
                            alerts.addAll(resp.getAlerts());
                        }
                        GetAlertsResponse masterResponse = new GetAlertsResponse(
                                alerts,
                                totalAlerts,
                                getDetectorResponse.getId()
                        );
                        // Send master response back
                        listener.onResponse(masterResponse);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        log.error("Failed to fetch alerts for detector " + detectorId, e);
                        listener.onFailure(SecurityAnalyticsException.wrap(e));
                    }
                }, monitorIds.size());
                // Execute GetAlertsAction for each monitor
                for(String monitorId : monitorIds) {
                    AlertsService.this.getAlertsByMonitorId(
                            monitorId,
                            table,
                            severityLevel,
                            alertState,
                            multiGetAlertsListener
                    );
                }
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(SecurityAnalyticsException.wrap(e));
            }
        });
    }

    /**
     * Searches alerts generated by specific Monitor
     * @param monitorId id of Monitor
     * @param table group of search related parameters
     * @param listener ActionListener to get notified on response or error
     */
    public void getAlertsByMonitorId(
            String monitorId,
            Table table,
            String severityLevel,
            String alertState,
            ActionListener<GetAlertsResponse> listener
    ) {

        org.opensearch.commons.alerting.action.GetAlertsRequest req =
                new org.opensearch.commons.alerting.action.GetAlertsRequest(
                table,
                severityLevel,
                alertState,
                monitorId,
                null
        );

        AlertingPluginInterface.INSTANCE.getAlerts((NodeClient) client, req, new ActionListener<>() {
                    @Override
                    public void onResponse(
                            org.opensearch.commons.alerting.action.GetAlertsResponse getAlertsResponse
                    ) {
                        // Convert response to SA's GetAlertsResponse
                        listener.onResponse(new GetAlertsResponse(
                                getAlertsResponse.getAlerts(),
                                getAlertsResponse.getTotalAlerts(),
                                null
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
}