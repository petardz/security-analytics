/*
Copyright OpenSearch Contributors
SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.securityanalytics.mapper.resthandler;

import org.opensearch.client.node.NodeClient;
import org.opensearch.common.xcontent.XContentParser;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestToXContentListener;
import org.opensearch.securityanalytics.SecurityAnalyticsPlugin;
import org.opensearch.securityanalytics.mapper.action.mapping.GetIndexMappingsAction;
import org.opensearch.securityanalytics.mapper.action.mapping.GetIndexMappingsRequest;

import java.io.IOException;
import java.util.List;

import static java.util.Collections.singletonList;
import static org.opensearch.rest.RestRequest.Method.GET;

public class RestGetIndexMappingsAction extends BaseRestHandler {

    @Override
    public String getName() {
        return "index_mappings_get_action";
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {

        GetIndexMappingsRequest req;
        if (request.hasContentOrSourceParam() == false) {
            req = new GetIndexMappingsRequest(
                    request.param("indexName")
            );
        } else {
            try (XContentParser parser = request.contentOrSourceParamParser()) {
                req = GetIndexMappingsRequest.parse(parser);
            }
        }

        return channel -> client.execute(
                GetIndexMappingsAction.INSTANCE,
                req,
                new RestToXContentListener<>(channel)
        );
    }

    @Override
    public List<Route> routes() {
        return singletonList(new Route(GET, SecurityAnalyticsPlugin.MAPPER_BASE_URI));
    }
}
