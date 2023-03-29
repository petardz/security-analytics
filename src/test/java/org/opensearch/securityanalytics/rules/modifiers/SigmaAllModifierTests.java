/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.securityanalytics.rules.modifiers;

import org.junit.Assert;
import org.opensearch.securityanalytics.rules.parser.exceptions.SigmaModifierError;
import org.opensearch.securityanalytics.rules.parser.exceptions.SigmaRegularExpressionError;
import org.opensearch.securityanalytics.rules.parser.exceptions.SigmaValueError;
import org.opensearch.securityanalytics.rules.parser.modifiers.SigmaAllModifier;
import org.opensearch.securityanalytics.rules.parser.objects.SigmaDetectionItem;
import org.opensearch.securityanalytics.rules.parser.types.SigmaNumber;
import org.opensearch.securityanalytics.rules.parser.types.SigmaRegularExpression;
import org.opensearch.securityanalytics.rules.parser.types.SigmaString;
import org.opensearch.securityanalytics.rules.parser.types.SigmaType;
import org.opensearch.securityanalytics.rules.parser.utils.Either;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SigmaAllModifierTests extends SigmaModifierTests {

    public void testAll() throws SigmaRegularExpressionError, SigmaValueError, SigmaModifierError {
        List<SigmaType> values = new ArrayList<>();
        values.add(new SigmaString("*foobar*"));
        values.add(new SigmaNumber(123));
        values.add(new SigmaRegularExpression(".*foobar.*"));

        SigmaDetectionItem detectionItem = dummyDetectionItem();
        values = new SigmaAllModifier(detectionItem, Collections.emptyList()).apply(Either.right(values));
        Assert.assertTrue(values.get(0) instanceof SigmaString && values.get(0).toString().equals("*foobar*"));
        Assert.assertTrue(values.get(1) instanceof SigmaNumber && values.get(1).toString().equals("123"));
        Assert.assertTrue(values.get(2) instanceof SigmaRegularExpression && values.get(2).toString().equals(".*foobar.*"));

        Assert.assertTrue(detectionItem.getValueLinking().isLeft());
    }
}