/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.securityanalytics.mapper.action.mapping;

import org.opensearch.action.ActionListener;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.action.support.master.AcknowledgedResponse;
import org.opensearch.client.Client;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.Settings;
import org.opensearch.securityanalytics.mapper.MapperApplier;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

public class TransportCreateIndexMappingsAction extends HandledTransportAction<CreateIndexMappingsRequest, AcknowledgedResponse> {

    private Client client;
    private MapperApplier mapperApplier;
    private ClusterService clusterService;

    @Inject
    public TransportCreateIndexMappingsAction(
            TransportService transportService,
            Client client,
            ActionFilters actionFilters,
            CreateIndexMappingsAction createIndexMappingsAction,
            MapperApplier mapperApplier,
            ClusterService clusterService,
            Settings settings
    ) {
        super(createIndexMappingsAction.NAME, transportService, actionFilters, CreateIndexMappingsRequest::new);
        this.client = client;
        this.clusterService = clusterService;
        this.mapperApplier = mapperApplier;
    }

    @Override
    protected void doExecute(Task task, CreateIndexMappingsRequest request, ActionListener<AcknowledgedResponse> actionListener) {
        IndexMetadata index = clusterService.state().metadata().index(request.indexName);
        if (index == null) {
            actionListener.onFailure(new IllegalStateException("Could not find index [" + request.indexName + "]"));
            return;
        }
        mapperApplier.createMappingAction(request.indexName, request.ruleTopic, actionListener);
    }
}