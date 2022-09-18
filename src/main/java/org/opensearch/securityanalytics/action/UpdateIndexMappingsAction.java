/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.securityanalytics.action;

import org.opensearch.action.ActionType;
import org.opensearch.action.support.master.AcknowledgedResponse;

public class UpdateIndexMappingsAction extends ActionType<AcknowledgedResponse>{

    public static final String NAME = "cluster:admin/opendistro/securityanalytics/mapping/update";
    public static final UpdateIndexMappingsAction INSTANCE = new UpdateIndexMappingsAction();


    public UpdateIndexMappingsAction() {
        super(NAME, AcknowledgedResponse::new);
    }
}
