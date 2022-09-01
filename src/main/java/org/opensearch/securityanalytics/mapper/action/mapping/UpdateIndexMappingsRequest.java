/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.securityanalytics.mapper.action.mapping;

import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.action.support.clustermanager.ClusterManagerNodeRequest;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;

import java.io.IOException;

import static org.opensearch.action.ValidateActions.addValidationError;

public class UpdateIndexMappingsRequest extends ClusterManagerNodeRequest<UpdateIndexMappingsRequest> {

    String indexName;
    String ruleTopic;

    public UpdateIndexMappingsRequest() {}

    public UpdateIndexMappingsRequest(String indexName, String ruleTopic) {
        this.indexName = indexName;
        this.ruleTopic = ruleTopic;
    }

    public UpdateIndexMappingsRequest(StreamInput in) throws IOException {
        super(in);
        indexName = in.readString();
        ruleTopic = in.readString();
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (indexName == null || indexName.length() == 0) {
            validationException = addValidationError("indexName is missing", validationException);
        }
        if (ruleTopic == null || ruleTopic.length() == 0) {
            validationException = addValidationError("mappings are missing", validationException);
        }
        return validationException;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(indexName);
        out.writeString(ruleTopic);
    }

    public UpdateIndexMappingsRequest indexName(String indexName) {
        this.indexName = indexName;
        return this;
    }

    public UpdateIndexMappingsRequest ruleTopic(String ruleTopic) {
        this.ruleTopic = ruleTopic;
        return this;
    }
}
