/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.securityanalytics.mapper;

import java.util.Collection;
import java.util.Optional;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.ActionListener;
import org.opensearch.action.admin.indices.mapping.get.GetMappingsRequest;
import org.opensearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.opensearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.opensearch.action.support.GroupedActionListener;
import org.opensearch.action.support.master.AcknowledgedResponse;
import org.opensearch.client.IndicesAdminClient;
import org.opensearch.cluster.metadata.MappingMetadata;
import org.opensearch.common.collect.ImmutableOpenMap;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.rest.RestStatus;
import org.opensearch.securityanalytics.action.GetIndexMappingsResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.opensearch.securityanalytics.action.GetMappingsViewResponse;
import org.opensearch.securityanalytics.util.SecurityAnalyticsException;


import static org.opensearch.securityanalytics.mapper.MapperUtils.PATH;
import static org.opensearch.securityanalytics.mapper.MapperUtils.PROPERTIES;

public class MapperService {

    private static final Logger log = LogManager.getLogger(MapperService.class);

    IndicesAdminClient indicesClient;

    public MapperService() {}

    public MapperService(IndicesAdminClient indicesClient) {
        this.indicesClient = indicesClient;
    }

    void setIndicesAdminClient(IndicesAdminClient client) {
        this.indicesClient = client;
    }

    public void createMappingAction(String indexName, String ruleTopic, boolean partial, ActionListener<AcknowledgedResponse> actionListener) {
        this.createMappingAction(indexName, ruleTopic, null, partial, actionListener);
    }

    public void createMappingAction(String indexName, String ruleTopic, String aliasMappings, boolean partial, ActionListener<AcknowledgedResponse> actionListener) {

        GetMappingsRequest getMappingsRequest = new GetMappingsRequest().indices(indexName);
        indicesClient.getMappings(getMappingsRequest, new ActionListener<>() {
            @Override
            public void onResponse(GetMappingsResponse getMappingsResponse) {
                createMappingActionContinuation(getMappingsResponse.getMappings(), ruleTopic, aliasMappings, partial, actionListener);
            }

            @Override
            public void onFailure(Exception e) {
                actionListener.onFailure(e);
            }
        });
    }

    private void createMappingActionContinuation(ImmutableOpenMap<String, MappingMetadata> indexMappings, String ruleTopic, String aliasMappings, boolean partial, ActionListener<AcknowledgedResponse> actionListener) {

        int numOfIndices =  indexMappings.size();

        GroupedActionListener doCreateMappingActionsListener = new GroupedActionListener(new ActionListener<Collection<AcknowledgedResponse>>() {
            @Override
            public void onResponse(Collection<AcknowledgedResponse> response) {
                // We will return ack==false if one of the requests returned that
                // else return ack==true
                Optional<AcknowledgedResponse> notAckd = response.stream().filter(e -> e.isAcknowledged() == false).findFirst();
                AcknowledgedResponse ack = new AcknowledgedResponse(
                        notAckd.isPresent() ? false : true
                );
                actionListener.onResponse(ack);
            }

            @Override
            public void onFailure(Exception e) {
                actionListener.onFailure(
                    new SecurityAnalyticsException(
                        e.getMessage(), RestStatus.INTERNAL_SERVER_ERROR, e)
                );
            }
        }, numOfIndices);

        indexMappings.forEach(iter -> {
            String indexName = iter.key;
            MappingMetadata mappingMetadata = iter.value;
            // Try to apply mapping to index
            doCreateMapping(indexName, mappingMetadata, ruleTopic, aliasMappings, partial, doCreateMappingActionsListener);
        });
    }

    /**
     * Applies alias mappings to index.
     * @param indexName Index name
     * @param mappingMetadata Index mappings
     * @param ruleTopic Rule topic spcifying specific alias templates
     * @param aliasMappings User-supplied alias mappings
     * @param partial Partial flag indicating if we should apply mappings partially, in case source index doesn't have all paths specified in alias mappings
     * @param actionListener actionListener used to return response/error
     */
    private void doCreateMapping(String indexName, MappingMetadata mappingMetadata, String ruleTopic, String aliasMappings, boolean partial, ActionListener<AcknowledgedResponse> actionListener) {

        PutMappingRequest request;
        try {

            String aliasMappingsJSON;
            // aliasMappings parameter has higher priority then ruleTopic
            if (aliasMappings != null) {
                aliasMappingsJSON = aliasMappings;
            } else {
                aliasMappingsJSON = MapperTopicStore.aliasMappings(ruleTopic);
            }

            List<String> missingPathsInIndex = MapperUtils.validateIndexMappings(indexName, mappingMetadata, aliasMappingsJSON);

            if(missingPathsInIndex.size() > 0) {
                // If user didn't allow partial apply, we should error out here
                if (!partial) {
                    actionListener.onFailure(
                            new IllegalArgumentException("Not all paths were found in index mappings: " +
                                    missingPathsInIndex.stream()
                                            .collect(Collectors.joining(", ", "[", "]")))
                    );
                }
                // Filter out missing paths from alias mappings so that our PutMappings request succeeds
                List<Pair<String, String>> pathsToSkip =
                        missingPathsInIndex.stream()
                                .map(e -> Pair.of(PATH, e))
                                .collect(Collectors.toList());
                MappingsTraverser mappingsTraverser = new MappingsTraverser(aliasMappingsJSON, pathsToSkip);
                Map<String, Object> filteredMappings = mappingsTraverser.traverseAndCopyAsFlat();

                request = new PutMappingRequest(indexName).source(filteredMappings);
            } else {
                request = new PutMappingRequest(indexName).source(
                        aliasMappingsJSON, XContentType.JSON
                );
            }

            indicesClient.putMapping(request, new ActionListener<>() {
                @Override
                public void onResponse(AcknowledgedResponse acknowledgedResponse) {
                    actionListener.onResponse(acknowledgedResponse);
                }

                @Override
                public void onFailure(Exception e) {
                    actionListener.onFailure(e);
                }
            });
        } catch (IOException | IllegalArgumentException e) {
            actionListener.onFailure(e);
        }
    }

    public void updateMappingAction(String indexName, String field, String alias, ActionListener<AcknowledgedResponse> actionListener) {
        PutMappingRequest request = new PutMappingRequest(indexName).source(field, alias);
        indicesClient.putMapping(request, new ActionListener<>() {
            @Override
            public void onResponse(AcknowledgedResponse acknowledgedResponse) {
                actionListener.onResponse(acknowledgedResponse);
            }

            @Override
            public void onFailure(Exception e) {
                actionListener.onFailure(e);
            }
        });
    }

    public void getMappingAction(String indexName, ActionListener<GetIndexMappingsResponse> actionListener) {
        GetMappingsRequest getMappingsRequest = new GetMappingsRequest().indices(indexName);
        indicesClient.getMappings(getMappingsRequest, new ActionListener<>() {
            @Override
            public void onResponse(GetMappingsResponse getMappingsResponse) {
                try {
                    // Extract indexName and MappingMetadata
                    String indexName = getMappingsResponse.mappings().iterator().next().key;
                    MappingMetadata mappingMetadata = getMappingsResponse.mappings().iterator().next().value;
                    // List of all found applied aliases on index
                    List<String> appliedAliases = new ArrayList<>();
                    // Get list of alias -> path pairs from index mappings
                    List<Pair<String, String>> indexAliasPathPairs = MapperUtils.getAllAliasPathPairs(mappingMetadata);

                    Map<String, String> aliasMappingsMap = MapperTopicStore.getAliasMappingsMap();
                    for (String mapperTopic : aliasMappingsMap.keySet()) {
                        // Get stored Alias Mappings as JSON string
                        String aliasMappingsJson = MapperTopicStore.aliasMappings(mapperTopic);
                        // Get list of alias -> path pairs from stored alias mappings
                        List<Pair<String, String>> aliasPathPairs = MapperUtils.getAllAliasPathPairs(aliasMappingsJson);
                        // Try to find any alias mappings in index mappings which are present in stored alias mappings
                        for (Pair<String, String> p1 : indexAliasPathPairs) {
                            for (Pair<String, String> p2 : aliasPathPairs) {
                                // Match by alias only here since user can match alias to some other path
                                if (p1.getKey().equals(p2.getKey())) {
                                    // Maintain list of found alias mappings
                                    appliedAliases.add(p1.getKey());
                                }
                            }
                        }
                        // If we found all aliases we can stop searching further
                        if (indexAliasPathPairs.size() == appliedAliases.size()) {
                            break;
                        }
                    }

                    if (appliedAliases.size() == 0) {
                        actionListener.onFailure(SecurityAnalyticsException.wrap(
                                new OpenSearchStatusException("No applied aliases not found", RestStatus.NOT_FOUND))
                        );
                        return;
                    }

                    // Traverse mappings and do copy with excluded type=alias properties
                    MappingsTraverser mappingsTraverser = new MappingsTraverser(mappingMetadata);
                    // Resulting mapping after filtering
                    Map<String, Object> filteredMapping = mappingsTraverser.traverseAndCopyWithFilter(appliedAliases);


                    // Construct filtered mappings and return them as result
                    ImmutableOpenMap.Builder<String, MappingMetadata> outIndexMappings = ImmutableOpenMap.builder();
                    Map<String, Object> root = Map.of(org.opensearch.index.mapper.MapperService.SINGLE_MAPPING_NAME, filteredMapping);
                    MappingMetadata outMappingMetadata = new MappingMetadata(org.opensearch.index.mapper.MapperService.SINGLE_MAPPING_NAME, root);
                    outIndexMappings.put(indexName, outMappingMetadata);

                    actionListener.onResponse(new GetIndexMappingsResponse(outIndexMappings.build()));
                } catch (IOException e) {
                    actionListener.onFailure(e);
                }
            }
                @Override
            public void onFailure(Exception e) {
                actionListener.onFailure(e);
            }
        });
    }

    public void getMappingsViewAction(
            String indexName,
            String mapperTopic,
            ActionListener<GetMappingsViewResponse> actionListener
    ) {
        GetMappingsRequest getMappingsRequest = new GetMappingsRequest().indices(indexName);
        indicesClient.getMappings(getMappingsRequest, new ActionListener<>() {
            @Override
            public void onResponse(GetMappingsResponse getMappingsResponse) {
                try {
                    // Extract MappingMetadata from GET _mapping response
                    MappingMetadata mappingMetadata = getMappingsResponse.mappings().iterator().next().value;
                    // Get list of all non-alias fields in index
                    List<String> allFieldsFromIndex = MapperUtils.getAllNonAliasFieldsFromIndex(mappingMetadata);
                    // Get stored Alias Mappings as JSON string
                    String aliasMappingsJson = MapperTopicStore.aliasMappings(mapperTopic);
                    // Get list of alias -> path pairs from stored alias mappings
                    List<Pair<String, String>> aliasPathPairs = MapperUtils.getAllAliasPathPairs(aliasMappingsJson);
                    // List of all found applied aliases on index
                    List<String> applyableAliases = new ArrayList<>();
                    // List of paths of found
                    List<String> pathsOfApplyableAliases = new ArrayList<>();
                    // List of unapplayable aliases
                    List<String> unmappedFieldAliases = new ArrayList<>();

                    for (Pair<String, String> p : aliasPathPairs) {
                        String alias = p.getKey();
                        String path = p.getValue();
                        if (allFieldsFromIndex.contains(path)) {
                            // Maintain list of found paths in index
                            applyableAliases.add(alias);
                            pathsOfApplyableAliases.add(path);
                        } else {
                            unmappedFieldAliases.add(alias);
                        }
                    }
                    // Gather all applyable alias mappings
                    Map<String, Object> aliasMappings =
                            MapperUtils.getAliasMappingsWithFilter(aliasMappingsJson, applyableAliases);
                    // Unmapped fields from index for which we don't have alias to apply to
                    List<String> unmappedIndexFields = allFieldsFromIndex
                            .stream()
                            .filter(e -> pathsOfApplyableAliases.contains(e) == false)
                            .collect(Collectors.toList());

                    actionListener.onResponse(
                            new GetMappingsViewResponse(aliasMappings, unmappedIndexFields, unmappedFieldAliases)
                    );
                } catch (Exception e) {
                    actionListener.onFailure(e);
                }
            }
            @Override
            public void onFailure(Exception e) {
                actionListener.onFailure(e);
            }
        });
    }
}