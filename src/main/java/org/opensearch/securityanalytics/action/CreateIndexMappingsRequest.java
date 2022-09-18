/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.securityanalytics.action;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.xcontent.ToXContentObject;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentParser;
import org.opensearch.common.xcontent.XContentParserUtils;

import java.io.IOException;
import java.util.Locale;

import static org.opensearch.action.ValidateActions.addValidationError;

public class CreateIndexMappingsRequest extends ActionRequest implements ToXContentObject {

    public static final String INDEX_NAME_FIELD = "index_name";
    public static final String RULE_TOPIC_FIELD = "rule_topic";

    String indexName;
    String ruleTopic;

    public CreateIndexMappingsRequest(String indexName, String ruleTopic) {
        super();
        this.indexName = indexName;
        this.ruleTopic = ruleTopic;
    }

    public CreateIndexMappingsRequest(StreamInput sin) throws IOException {
        this(
                sin.readString(),
                sin.readString()
        );
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (indexName == null || indexName.length() == 0) {
            validationException = addValidationError(String.format(Locale.getDefault(), "%s is missing", INDEX_NAME_FIELD), validationException);
        }
        if (ruleTopic == null || ruleTopic.length() == 0) {
            validationException = addValidationError(String.format(Locale.getDefault(), "%s is missing", RULE_TOPIC_FIELD), validationException);
        }
        return validationException;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(indexName);
        out.writeString(ruleTopic);
    }

    public static CreateIndexMappingsRequest parse(XContentParser xcp) throws IOException {
        String indexName = null;
        String ruleTopic = null;

        XContentParserUtils.ensureExpectedToken(XContentParser.Token.START_OBJECT, xcp.currentToken(), xcp);
        while (xcp.nextToken() != XContentParser.Token.END_OBJECT) {
            String fieldName = xcp.currentName();
            xcp.nextToken();

            switch (fieldName) {
                case INDEX_NAME_FIELD:
                    indexName = xcp.text();
                    break;
                case RULE_TOPIC_FIELD:
                    ruleTopic = xcp.text();
                    break;
                default:
                    xcp.skipChildren();
            }
        }
        return new CreateIndexMappingsRequest(indexName, ruleTopic);
    }

    public CreateIndexMappingsRequest indexName(String indexName) {
        this.indexName = indexName;
        return this;
    }

    public CreateIndexMappingsRequest ruleTopic(String ruleTopic) {
        this.ruleTopic = ruleTopic;
        return this;
    }

    public String getRuleTopic() {
        return this.ruleTopic;
    }

    public String getIndexName() {
        return this.indexName;
    }

    public void setRuleTopic(String ruleTopic) {
        this.ruleTopic = ruleTopic;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return builder.startObject()
                .field(INDEX_NAME_FIELD, indexName)
                .field(RULE_TOPIC_FIELD, ruleTopic)
                .endObject();
    }
}
