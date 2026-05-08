/*
 *  Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied. See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.synapse.mediators.v2;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.GsonJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.GsonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import junit.framework.TestCase;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.core.axis2.Axis2SynapseEnvironment;
import org.apache.synapse.mediators.eip.EIPConstants;
import org.apache.synapse.mediators.eip.aggregator.ForEachAggregate;
import org.apache.synapse.util.xpath.SynapseExpression;
import org.junit.Assert;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Set;

/**
 * Runtime-level unit tests for {@link ForEachMediatorV2#updateOriginalPayload(MessageContext, ForEachAggregate)}
 * — the post-aggregation step that broke with a {@code com.jayway.jsonpath.PathNotFoundException}
 * when the {@code foreach} collection was a variable expression like
 * {@code ${vars.arrayVar.array}} on a request with an empty/non-JSON body
 * (the exact failing scenario in product-integrator-mi issue #4165).
 *
 * Before the fix, {@code updateOriginalPayload} unconditionally evaluated the JSONPath against
 * the message body — even for variable-backed collections — causing the GET-with-no-body
 * reproducer to throw and abort the {@code <respond/>} continuation.
 *
 * After the fix, the variable-backed branch reads the variable's current JSON value, applies
 * the JSONPath update against THAT, and writes the result back via {@code setVariable}.
 *
 * These tests drive {@code updateOriginalPayload} directly via reflection so the assertions
 * focus on the variable/payload split that contains the bug, without depending on the full
 * continuation machinery (callbacks, SharedDataHolder, OperationContext, etc.).
 */
public class ForEachMediatorV2Test extends TestCase {

    private static final String VAR_NAME = "arrayVar";
    private static final JsonParser jsonParser = new JsonParser();

    /**
     * Install Gson-backed JsonPath providers before any test runs.
     *
     * <p>The default {@code com.jayway.jsonpath} configuration tries to initialise
     * {@code net.minidev.json}-backed providers, which are not available on this
     * module's test classpath (the production classpath gets them transitively from
     * other modules at runtime, but unit tests run with a leaner set). Following the
     * same pattern as {@code org.apache.synapse.util.synapse.expression.TestUtils},
     * we wire Gson providers explicitly so {@code JsonPath.parse(...)} works during
     * the test execution.</p>
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Configuration.setDefaults(new Configuration.Defaults() {
            private final JsonProvider jsonProvider =
                    new GsonJsonProvider(new GsonBuilder().serializeNulls().create());
            private final MappingProvider mappingProvider = new GsonMappingProvider();

            @Override
            public JsonProvider jsonProvider() {
                return jsonProvider;
            }

            @Override
            public MappingProvider mappingProvider() {
                return mappingProvider;
            }

            @Override
            public Set<Option> options() {
                return EnumSet.noneOf(Option.class);
            }
        });
    }

    // --------------------------------------------------------------------- //
    // Test fixtures                                                          //
    // --------------------------------------------------------------------- //

    /** Build a JSON-shaped Axis2MessageContext with the given body string (may be empty/null). */
    private MessageContext newJsonContext(String body) throws Exception {
        SynapseConfiguration synCfg = new SynapseConfiguration();
        AxisConfiguration axisCfg = new AxisConfiguration();
        ConfigurationContext cfgCtx = new ConfigurationContext(axisCfg);
        SynapseEnvironment env = new Axis2SynapseEnvironment(cfgCtx, synCfg);
        Axis2MessageContext synCtx = new Axis2MessageContext(
                new org.apache.axis2.context.MessageContext(), synCfg, env);
        SOAPEnvelope envelope = OMAbstractFactory.getSOAP11Factory().getDefaultEnvelope();
        synCtx.setEnvelope(envelope);
        if (body != null) {
            JsonUtil.getNewJsonPayload(synCtx.getAxis2MessageContext(), body, true, true);
        }
        return synCtx;
    }

    /**
     * Build an iterated message context that mimics what {@code getIteratedMessage(...)}
     * would produce — a JSON body holding the iteration item plus the
     * {@code messageSequence.<id>} property used by {@code updateOriginalPayload}
     * to know which slot to write back into.
     */
    private MessageContext newIteratedContext(String mediatorId, int idx, int total, String jsonBody)
            throws Exception {
        MessageContext iterated = newJsonContext(jsonBody);
        iterated.setProperty(EIPConstants.MESSAGE_SEQUENCE + "." + mediatorId,
                idx + EIPConstants.MESSAGE_SEQUENCE_DELEMITER + total);
        return iterated;
    }

    /**
     * Configure a {@code ForEachMediatorV2} the way the failing API definition does.
     *
     * <p>Note: {@link SynapseExpression} expects the bare expression text without the
     * {@code ${...}} wrapper — the wrapper is stripped earlier by {@code SynapsePathFactory}
     * before the expression reaches the mediator. So a configured value of
     * {@code ${vars.arrayVar.array}} ends up here as just {@code vars.arrayVar.array},
     * which is exactly what {@code isCollectionReferencedByVariable} in the mediator
     * checks for via {@code startsWith("vars.")}.</p>
     */
    private ForEachMediatorV2 newVariableBackedMediator(String collectionExpr) throws Exception {
        ForEachMediatorV2 m = new ForEachMediatorV2();
        m.setCollectionExpression(new SynapseExpression(collectionExpr));
        m.setParallelExecution(true);
        m.setUpdateOriginal(true);
        m.setContinueWithoutAggregation(false);
        return m;
    }

    /** Reflectively invoke the private {@code updateOriginalPayload} so we can assert on its behaviour. */
    private void invokeUpdateOriginalPayload(ForEachMediatorV2 m, MessageContext ctx,
                                             ForEachAggregate aggregate) throws Exception {
        Method method = ForEachMediatorV2.class.getDeclaredMethod(
                "updateOriginalPayload", MessageContext.class, ForEachAggregate.class);
        method.setAccessible(true);
        method.invoke(m, ctx, aggregate);
    }

    // --------------------------------------------------------------------- //
    // Bug-scenario test (would fail before the fix, passes after)            //
    // --------------------------------------------------------------------- //

    /**
     * The exact failing scenario from issue #4165.
     *
     * <ul>
     *   <li>A JSON variable {@code arrayVar = {"array":[{"id":"011"},{"id":"012"}]}} is set on
     *       the original message context.</li>
     *   <li>The collection expression is {@code ${vars.arrayVar.array}}.</li>
     *   <li>The original message body is empty (mirrors the {@code GET /sample/} reproducer
     *       which has no payload).</li>
     *   <li>Iterations have already produced two updated objects with mutated {@code id}s.</li>
     * </ul>
     *
     * Before the fix, {@code updateOriginalPayload} would call
     * {@code JsonPath.parse(emptyBody).set("$.array", ...)}, throwing
     * {@code com.jayway.jsonpath.PathNotFoundException}. After the fix, the JSONPath update
     * is applied against the variable's current JSON value and written back via
     * {@code setVariable}, leaving the message body untouched.
     */
    public void testVariableBackedCollectionWithEmptyBodyDoesNotThrow() throws Exception {
        ForEachMediatorV2 mediator = newVariableBackedMediator("vars." + VAR_NAME + ".array");

        MessageContext original = newJsonContext("{}");                // empty/no-array body
        original.setVariable(VAR_NAME,
                jsonParser.parse("{\"array\":[{\"id\":\"011\"},{\"id\":\"012\"}]}"));

        // Two iterated messages, each with a JSON body that the iteration mutated.
        ForEachAggregate aggregate = new ForEachAggregate("corr-1", mediator.getId());
        aggregate.addMessage(newIteratedContext(mediator.getId(), 0, 2, "{\"id\":\"011-mutated\"}"));
        aggregate.addMessage(newIteratedContext(mediator.getId(), 1, 2, "{\"id\":\"012-mutated\"}"));

        // Before the fix, this call threw PathNotFoundException because $.array is not present
        // in the empty/{} request body. After the fix, it must complete cleanly.
        invokeUpdateOriginalPayload(mediator, original, aggregate);

        // The variable should now contain the aggregated, mutated items.
        Object updated = original.getVariable(VAR_NAME);
        Assert.assertNotNull("Variable must still be present after foreach update", updated);

        JsonObject expected = (JsonObject) jsonParser.parse(
                "{\"array\":[{\"id\":\"011-mutated\"},{\"id\":\"012-mutated\"}]}");
        // The variable may now be stored as a JsonElement or a String — accept either shape.
        JsonObject actual;
        if (updated instanceof JsonObject) {
            actual = (JsonObject) updated;
        } else {
            actual = (JsonObject) jsonParser.parse(updated.toString());
        }
        Assert.assertEquals("Variable should contain the aggregated, mutated items",
                expected, actual);
    }

    /**
     * Whole-variable shortcut: collection expression is {@code ${vars.arrayVar}} (the entire
     * variable is the array). After the fix, this should take the {@code isWholeContent}
     * branch and write the aggregated array directly back to the variable — without ever
     * touching the message body.
     */
    public void testVariableBackedWholeVariableShortcut() throws Exception {
        ForEachMediatorV2 mediator = newVariableBackedMediator("vars." + VAR_NAME);

        MessageContext original = newJsonContext("{}");
        original.setVariable(VAR_NAME,
                jsonParser.parse("[{\"id\":\"031\"},{\"id\":\"032\"}]"));

        ForEachAggregate aggregate = new ForEachAggregate("corr-2", mediator.getId());
        aggregate.addMessage(newIteratedContext(mediator.getId(), 0, 2, "{\"id\":\"031-mutated\"}"));
        aggregate.addMessage(newIteratedContext(mediator.getId(), 1, 2, "{\"id\":\"032-mutated\"}"));

        invokeUpdateOriginalPayload(mediator, original, aggregate);

        Object updated = original.getVariable(VAR_NAME);
        Assert.assertNotNull("Variable must still be present", updated);
        JsonArray expected = (JsonArray) jsonParser.parse(
                "[{\"id\":\"031-mutated\"},{\"id\":\"032-mutated\"}]");
        JsonArray actual = (updated instanceof JsonArray)
                ? (JsonArray) updated
                : (JsonArray) jsonParser.parse(updated.toString());
        Assert.assertEquals("Whole-variable branch should write the aggregated array back",
                expected, actual);
    }

    // --------------------------------------------------------------------- //
    // Regression: payload-backed branch must still work                      //
    // --------------------------------------------------------------------- //

    /**
     * Payload-backed happy path: the previously-working flow for
     * {@code ${payload.array}} on a real JSON body must remain unchanged after the fix.
     */
    public void testPayloadBackedCollectionStillUpdatesBody() throws Exception {
        ForEachMediatorV2 mediator = new ForEachMediatorV2();
        mediator.setCollectionExpression(new SynapseExpression("payload.array"));
        mediator.setParallelExecution(true);
        mediator.setUpdateOriginal(true);
        mediator.setContinueWithoutAggregation(false);

        MessageContext original = newJsonContext("{\"array\":[{\"id\":\"a1\"},{\"id\":\"a2\"}]}");

        ForEachAggregate aggregate = new ForEachAggregate("corr-3", mediator.getId());
        aggregate.addMessage(newIteratedContext(mediator.getId(), 0, 2, "{\"id\":\"a1-mutated\"}"));
        aggregate.addMessage(newIteratedContext(mediator.getId(), 1, 2, "{\"id\":\"a2-mutated\"}"));

        invokeUpdateOriginalPayload(mediator, original, aggregate);

        String bodyJson = JsonUtil.jsonPayloadToString(
                ((Axis2MessageContext) original).getAxis2MessageContext());
        Assert.assertEquals(
                jsonParser.parse("{\"array\":[{\"id\":\"a1-mutated\"},{\"id\":\"a2-mutated\"}]}"),
                jsonParser.parse(bodyJson));
    }

    // --------------------------------------------------------------------- //
    // Edge cases                                                             //
    // --------------------------------------------------------------------- //

    /**
     * Iteration order edge case: aggregator may receive messages in a different order than
     * the original collection (likely under {@code parallel-execution=true}). The
     * {@code MESSAGE_SEQUENCE} property is what determines the slot — the result must be
     * indexed by sequence number, NOT by aggregator arrival order.
     */
    public void testVariableBackedReorderedAggregation() throws Exception {
        ForEachMediatorV2 mediator = newVariableBackedMediator("vars." + VAR_NAME + ".array");

        MessageContext original = newJsonContext("{}");
        original.setVariable(VAR_NAME,
                jsonParser.parse("{\"array\":[{\"id\":\"011\"},{\"id\":\"012\"},{\"id\":\"013\"}]}"));

        // Insert in reversed order.
        ForEachAggregate aggregate = new ForEachAggregate("corr-4", mediator.getId());
        aggregate.addMessage(newIteratedContext(mediator.getId(), 2, 3, "{\"id\":\"013-mutated\"}"));
        aggregate.addMessage(newIteratedContext(mediator.getId(), 0, 3, "{\"id\":\"011-mutated\"}"));
        aggregate.addMessage(newIteratedContext(mediator.getId(), 1, 3, "{\"id\":\"012-mutated\"}"));

        invokeUpdateOriginalPayload(mediator, original, aggregate);

        JsonObject expected = (JsonObject) jsonParser.parse(
                "{\"array\":[{\"id\":\"011-mutated\"},{\"id\":\"012-mutated\"},{\"id\":\"013-mutated\"}]}");
        Object updated = original.getVariable(VAR_NAME);
        JsonObject actual = (updated instanceof JsonObject)
                ? (JsonObject) updated
                : (JsonObject) jsonParser.parse(updated.toString());
        Assert.assertEquals("Result must be indexed by MESSAGE_SEQUENCE, not arrival order",
                expected, actual);
    }

    /**
     * Variable-backed single-element collection: confirms the smallest possible aggregation
     * still flows through the variable-write path without touching the body.
     */
    public void testVariableBackedSingleElement() throws Exception {
        ForEachMediatorV2 mediator = newVariableBackedMediator("vars." + VAR_NAME + ".array");

        MessageContext original = newJsonContext("{}");
        original.setVariable(VAR_NAME,
                jsonParser.parse("{\"array\":[{\"id\":\"only\"}]}"));

        ForEachAggregate aggregate = new ForEachAggregate("corr-5", mediator.getId());
        aggregate.addMessage(newIteratedContext(mediator.getId(), 0, 1, "{\"id\":\"only-mutated\"}"));

        invokeUpdateOriginalPayload(mediator, original, aggregate);

        Object updated = original.getVariable(VAR_NAME);
        JsonObject actual = (updated instanceof JsonObject)
                ? (JsonObject) updated
                : (JsonObject) jsonParser.parse(updated.toString());
        Assert.assertEquals(
                jsonParser.parse("{\"array\":[{\"id\":\"only-mutated\"}]}"),
                actual);
    }

    // --------------------------------------------------------------------- //
    // Negative case: non-JSON body must NOT block variable-backed update     //
    // --------------------------------------------------------------------- //

    /**
     * Negative path that exactly matches the original failure: the original message has
     * NO JSON body at all (a raw GET request with no payload). Before the fix, this would
     * throw because {@code JsonUtil.jsonPayloadToString} returned {@code null} / a
     * non-parseable string. After the fix, the body is never consulted on the variable
     * branch, so the call must succeed.
     */
    public void testVariableBackedWithNoJsonBodyAtAll() throws Exception {
        ForEachMediatorV2 mediator = newVariableBackedMediator("vars." + VAR_NAME + ".array");

        // Build an Axis2 context with NO JSON payload set at all (mimics a bare GET).
        SynapseConfiguration synCfg = new SynapseConfiguration();
        AxisConfiguration axisCfg = new AxisConfiguration();
        ConfigurationContext cfgCtx = new ConfigurationContext(axisCfg);
        SynapseEnvironment env = new Axis2SynapseEnvironment(cfgCtx, synCfg);
        Axis2MessageContext original = new Axis2MessageContext(
                new org.apache.axis2.context.MessageContext(), synCfg, env);
        SOAPEnvelope envelope = OMAbstractFactory.getSOAP11Factory().getDefaultEnvelope();
        original.setEnvelope(envelope);

        original.setVariable(VAR_NAME,
                jsonParser.parse("{\"array\":[{\"id\":\"x1\"},{\"id\":\"x2\"}]}"));

        ForEachAggregate aggregate = new ForEachAggregate("corr-6", mediator.getId());
        aggregate.addMessage(newIteratedContext(mediator.getId(), 0, 2, "{\"id\":\"x1-m\"}"));
        aggregate.addMessage(newIteratedContext(mediator.getId(), 1, 2, "{\"id\":\"x2-m\"}"));

        // No exception expected.
        invokeUpdateOriginalPayload(mediator, original, aggregate);

        Object updated = original.getVariable(VAR_NAME);
        Assert.assertNotNull("Variable must still be present", updated);
        JsonObject actual = (updated instanceof JsonObject)
                ? (JsonObject) updated
                : (JsonObject) jsonParser.parse(updated.toString());
        Assert.assertEquals(
                jsonParser.parse("{\"array\":[{\"id\":\"x1-m\"},{\"id\":\"x2-m\"}]}"),
                actual);
    }

    // --------------------------------------------------------------------- //
    // Companion test for the secondary FaultHandler null-message guard       //
    // --------------------------------------------------------------------- //

    /**
     * The fix also adds a null guard to {@code FaultHandler#handleFault(MessageContext, Exception)}:
     * {@code com.jayway.jsonpath.PathNotFoundException#getMessage()} returns {@code null},
     * which previously NPE'd at {@code e.getMessage().split("\n")[0]} and killed the fault thread.
     *
     * The guard turns that into an empty {@code ERROR_MESSAGE} property on the synCtx instead.
     */
    public void testFaultHandlerHandlesNullExceptionMessageWithoutNpe() throws Exception {
        // Anonymous concrete FaultHandler that does nothing in onFault.
        org.apache.synapse.FaultHandler handler = new org.apache.synapse.FaultHandler() {
            @Override
            public void onFault(MessageContext synCtx) {
                // no-op
            }
        };

        // Use the same lightweight context the existing v2 tests use.
        SynapseConfiguration synCfg = new SynapseConfiguration();
        SynapseEnvironment env = new Axis2SynapseEnvironment(synCfg);
        MessageContext synCtx = new Axis2MessageContext(
                new org.apache.axis2.context.MessageContext(), synCfg, env);
        SOAPEnvelope envelope = OMAbstractFactory.getSOAP11Factory().getDefaultEnvelope();
        synCtx.setEnvelope(envelope);

        Exception nullMessageException = new RuntimeException((String) null);
        Assert.assertNull("Sanity: this exception should have a null message",
                nullMessageException.getMessage());

        // Pre-fix: this call NPE'd at FaultHandler.java:88 inside the .split("\n") chain.
        // Post-fix: must complete cleanly and set ERROR_MESSAGE to an empty string.
        handler.handleFault(synCtx, nullMessageException);

        Object errorMessage = synCtx.getProperty(
                org.apache.synapse.SynapseConstants.ERROR_MESSAGE);
        Assert.assertEquals("Null exception message should be normalised to empty string",
                "", errorMessage);
    }
}
