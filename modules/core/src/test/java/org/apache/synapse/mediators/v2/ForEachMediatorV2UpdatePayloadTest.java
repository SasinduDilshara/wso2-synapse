/*
 *  Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import junit.framework.TestCase;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMDocument;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.core.axis2.Axis2SynapseEnvironment;
import org.apache.synapse.mediators.base.SequenceMediator;
import org.apache.synapse.mediators.eip.EIPConstants;
import org.apache.synapse.mediators.eip.SharedDataHolder;
import org.apache.synapse.mediators.eip.Target;
import org.apache.synapse.mediators.eip.aggregator.ForEachAggregate;
import org.apache.synapse.util.xpath.SynapseExpression;
import org.jaxen.JaxenException;

import org.apache.synapse.mediators.eip.EIPUtils;

import java.lang.reflect.Method;

/**
 * Unit tests for ForEachMediatorV2.updateOriginalPayload() covering:
 *  - Issue #4165: variable-backed collection + parallel-execution=true + update-original=true
 *    must NOT throw PathNotFoundException and must update the variable (not the body).
 *  - Regression guard: body-backed collection still updates the body correctly.
 */
public class ForEachMediatorV2UpdatePayloadTest extends TestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Configure JsonPath to use the Gson provider, matching what ServerManager does at runtime.
        // Without this, DefaultsImpl cannot be initialised in the test classloader.
        EIPUtils.setJsonPathConfiguration();
    }

    /**
     * Creates a minimal Axis2MessageContext with an empty SOAP envelope (no JSON body).
     * This simulates a GET request (no body), which was the scenario in issue #4165.
     */
    private Axis2MessageContext createEmptyBodyMessageContext() throws Exception {

        SynapseConfiguration synCfg = new SynapseConfiguration();
        AxisConfiguration axisConfig = new AxisConfiguration();
        synCfg.setAxisConfiguration(axisConfig);
        ConfigurationContext cfgCtx = new ConfigurationContext(axisConfig);

        org.apache.axis2.context.MessageContext axis2Mc = new org.apache.axis2.context.MessageContext();
        axis2Mc.setConfigurationContext(cfgCtx);

        Axis2SynapseEnvironment synapseEnv = new Axis2SynapseEnvironment(cfgCtx, synCfg);
        Axis2MessageContext synCtx = new Axis2MessageContext(axis2Mc, synCfg, synapseEnv);

        SOAPEnvelope envelope = OMAbstractFactory.getSOAP11Factory().getDefaultEnvelope();
        OMDocument omDoc = OMAbstractFactory.getSOAP11Factory().createOMDocument();
        omDoc.addChild(envelope);
        synCtx.setEnvelope(envelope);
        synCtx.setMessageID("test-msg-id-" + System.nanoTime());

        return synCtx;
    }

    /**
     * Creates a minimal Axis2MessageContext with a JSON body.
     */
    private Axis2MessageContext createJsonBodyMessageContext(String json) throws Exception {

        Axis2MessageContext synCtx = createEmptyBodyMessageContext();
        JsonUtil.getNewJsonPayload(synCtx.getAxis2MessageContext(), json, true, true);
        return synCtx;
    }

    /**
     * Creates a ForEachMediatorV2 with the supplied SynapseExpression as collection expression,
     * parallelExecution=true, and updateOriginal=true.
     */
    private ForEachMediatorV2 buildMediator(String expression, boolean parallelExecution) throws JaxenException {

        ForEachMediatorV2 mediator = new ForEachMediatorV2();
        mediator.setCollectionExpression(new SynapseExpression(expression));
        mediator.setParallelExecution(parallelExecution);
        mediator.setUpdateOriginal(true);

        // Supply a minimal target with an inline sequence so the mediator is valid
        Target target = new Target();
        target.setSequence(new SequenceMediator());
        mediator.setTarget(target);

        return mediator;
    }

    /**
     * Builds a ForEachAggregate carrying iterated messages whose payloads are the given
     * JSON strings.  Each message also carries:
     *  - EIPConstants.AGGREGATE_CORRELATION.<id> -> correlationId
     *  - EIPConstants.MESSAGE_SEQUENCE.<id>      -> "<index>:<total>"
     *  - EIPConstants.EIP_SHARED_DATA_HOLDER.<id> -> SharedDataHolder wrapping originalCtx
     */
    private ForEachAggregate buildAggregate(String mediatorId,
                                             MessageContext originalCtx,
                                             String correlationId,
                                             String... iteratedPayloads) throws Exception {

        ForEachAggregate aggregate = new ForEachAggregate(correlationId, mediatorId);
        int total = iteratedPayloads.length;
        for (int i = 0; i < total; i++) {
            Axis2MessageContext iterCtx = createJsonBodyMessageContext(iteratedPayloads[i]);
            iterCtx.setProperty(EIPConstants.AGGREGATE_CORRELATION + "." + mediatorId, correlationId);
            iterCtx.setProperty(EIPConstants.MESSAGE_SEQUENCE + "." + mediatorId,
                    i + EIPConstants.MESSAGE_SEQUENCE_DELEMITER + total);
            // Point back to the original context so completeAggregate can retrieve it
            iterCtx.setProperty(EIPConstants.EIP_SHARED_DATA_HOLDER + "." + mediatorId,
                    new SharedDataHolder(originalCtx));
            aggregate.addMessage(iterCtx);
        }
        return aggregate;
    }

    /**
     * Invokes the private updateOriginalPayload method via reflection.
     */
    private void invokeUpdateOriginalPayload(ForEachMediatorV2 mediator,
                                              MessageContext originalCtx,
                                              ForEachAggregate aggregate) throws Exception {

        Method method = ForEachMediatorV2.class.getDeclaredMethod(
                "updateOriginalPayload", MessageContext.class, ForEachAggregate.class);
        method.setAccessible(true);
        method.invoke(mediator, originalCtx, aggregate);
    }

    // -----------------------------------------------------------------------
    // Scenario A — Issue #4165 bug scenario
    // Variable-backed nested collection: vars.arrayVar.array
    // parallel-execution=true, update-original=true, NO message body (GET).
    // Before the fix: PathNotFoundException. After the fix: variable updated correctly.
    // -----------------------------------------------------------------------

    /**
     * Tests that updateOriginalPayload with a variable-backed collection expression
     * (vars.arrayVar.array) does NOT throw PathNotFoundException and correctly
     * updates the variable in the original message context.
     *
     * This is the exact reproduction of issue #4165.
     */
    public void testUpdateOriginalPayload_VariableBackedCollection_ParallelExecution_NoBody() throws Exception {

        // Prepare the original context — GET request, no JSON body
        Axis2MessageContext originalCtx = createEmptyBodyMessageContext();

        // Set arrayVar = {"array":[{"id":"011"},{"id":"012"}]} as a JSON variable
        String varJson = "{\"array\":[{\"id\":\"011\"},{\"id\":\"012\"}]}";
        JsonElement varValue = JsonParser.parseString(varJson);
        originalCtx.setVariable("arrayVar", varValue);

        // Mediator configured with collection="${vars.arrayVar.array}"
        ForEachMediatorV2 mediator = buildMediator("vars.arrayVar.array", true);
        String mediatorId = mediator.getId();

        String correlationId = originalCtx.getMessageID();

        // Simulate two iterated contexts — each carries the (unchanged) item as payload
        String[] payloads = {"{\"id\":\"011\"}", "{\"id\":\"012\"}"};
        ForEachAggregate aggregate = buildAggregate(mediatorId, originalCtx, correlationId, payloads);

        // This must NOT throw PathNotFoundException (that was the bug)
        try {
            invokeUpdateOriginalPayload(mediator, originalCtx, aggregate);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            fail("updateOriginalPayload threw an unexpected exception: " + cause.getClass().getName()
                    + " : " + cause.getMessage());
        }

        // After the fix: the variable 'arrayVar' must be updated with the iterated payloads.
        Object updatedVar = originalCtx.getVariable("arrayVar");
        assertNotNull("Variable 'arrayVar' must not be null after updateOriginalPayload", updatedVar);

        // The updated variable must contain the array with both items
        JsonObject updatedObj = JsonParser.parseString(updatedVar.toString()).getAsJsonObject();
        assertTrue("Updated variable must have 'array' key", updatedObj.has("array"));
        JsonArray updatedArray = updatedObj.getAsJsonArray("array");
        assertEquals("Array must have 2 elements", 2, updatedArray.size());
        assertEquals("{\"id\":\"011\"}", updatedArray.get(0).toString());
        assertEquals("{\"id\":\"012\"}", updatedArray.get(1).toString());

        // The message body must remain empty (not modified)
        String bodyPayload = JsonUtil.jsonPayloadToString(originalCtx.getAxis2MessageContext());
        // Empty body should be null or empty string — we only check it's not the array JSON
        if (bodyPayload != null) {
            assertFalse("Body must not contain the array items (variable path, not body path)",
                    bodyPayload.contains("\"id\""));
        }
    }

    /**
     * Edge case: variable holds a flat array (no nested path), i.e. collection="${vars.list}".
     * After update, the variable itself must be the updated JsonArray.
     */
    public void testUpdateOriginalPayload_VariableBackedFlatArray_ParallelExecution_NoBody() throws Exception {

        Axis2MessageContext originalCtx = createEmptyBodyMessageContext();

        // Set list = [{"name":"guava"},{"name":"beet"}] as a JSON variable
        JsonElement varValue = JsonParser.parseString("[{\"name\":\"guava\"},{\"name\":\"beet\"}]");
        originalCtx.setVariable("list", varValue);

        ForEachMediatorV2 mediator = buildMediator("vars.list", true);
        String mediatorId = mediator.getId();
        String correlationId = originalCtx.getMessageID();

        // Simulate two iterated contexts with modified payloads (e.g. _name added)
        String[] payloads = {"{\"_name\":\"guava\",\"age\":5}", "{\"_name\":\"beet\",\"age\":5}"};
        ForEachAggregate aggregate = buildAggregate(mediatorId, originalCtx, correlationId, payloads);

        try {
            invokeUpdateOriginalPayload(mediator, originalCtx, aggregate);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            fail("Unexpected exception: " + ite.getCause());
        }

        Object updatedVar = originalCtx.getVariable("list");
        assertNotNull("Variable 'list' must not be null after update", updatedVar);
        JsonArray updatedArray = JsonParser.parseString(updatedVar.toString()).getAsJsonArray();
        assertEquals(2, updatedArray.size());
        assertEquals("{\"_name\":\"guava\",\"age\":5}", updatedArray.get(0).toString());
        assertEquals("{\"_name\":\"beet\",\"age\":5}", updatedArray.get(1).toString());
    }

    /**
     * Edge case: single-element variable array. Must not throw and must update correctly.
     */
    public void testUpdateOriginalPayload_VariableBackedSingleElementArray_NoBody() throws Exception {

        Axis2MessageContext originalCtx = createEmptyBodyMessageContext();

        String varJson = "{\"array\":[{\"id\":\"only\"}]}";
        originalCtx.setVariable("singleVar", JsonParser.parseString(varJson));

        ForEachMediatorV2 mediator = buildMediator("vars.singleVar.array", true);
        String mediatorId = mediator.getId();
        String correlationId = originalCtx.getMessageID();

        ForEachAggregate aggregate = buildAggregate(mediatorId, originalCtx, correlationId, "{\"id\":\"only\",\"processed\":true}");

        try {
            invokeUpdateOriginalPayload(mediator, originalCtx, aggregate);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            fail("Unexpected exception for single-element array: " + ite.getCause());
        }

        Object updatedVar = originalCtx.getVariable("singleVar");
        assertNotNull(updatedVar);
        JsonObject obj = JsonParser.parseString(updatedVar.toString()).getAsJsonObject();
        JsonArray arr = obj.getAsJsonArray("array");
        assertEquals(1, arr.size());
        assertTrue(arr.get(0).getAsJsonObject().has("processed"));
    }

    // -----------------------------------------------------------------------
    // Scenario B — Regression guard: body-backed collection still works
    // collection="${payload.items}", parallel-execution=true, update-original=true
    // Body has JSON, and the body should be updated after foreach.
    // -----------------------------------------------------------------------

    /**
     * Regression guard: body-backed foreach with parallel-execution=true and
     * update-original=true must still update the message body correctly.
     *
     * Verifies that the refactoring of updateOriginalPayload() did not break
     * the existing body-update path.
     */
    public void testUpdateOriginalPayload_BodyBackedCollection_ParallelExecution_UpdatesBody() throws Exception {

        // Prepare the original context WITH a JSON body
        String bodyJson = "{\"items\":[{\"name\":\"item1\"},{\"name\":\"item2\"}]}";
        Axis2MessageContext originalCtx = createJsonBodyMessageContext(bodyJson);

        // Mediator configured with collection="${payload.items}"
        ForEachMediatorV2 mediator = buildMediator("payload.items", true);
        String mediatorId = mediator.getId();
        String correlationId = originalCtx.getMessageID();

        // Simulate two iterated contexts whose payloads are the transformed items
        String[] payloads = {"{\"name\":\"item1\",\"processed\":true}", "{\"name\":\"item2\",\"processed\":true}"};
        ForEachAggregate aggregate = buildAggregate(mediatorId, originalCtx, correlationId, payloads);

        try {
            invokeUpdateOriginalPayload(mediator, originalCtx, aggregate);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            fail("Unexpected exception in body-backed path: " + ite.getCause());
        }

        // The body must have been updated with the processed items
        String updatedBody = JsonUtil.jsonPayloadToString(originalCtx.getAxis2MessageContext());
        assertNotNull("Body must not be null after update", updatedBody);
        JsonObject updatedObj = JsonParser.parseString(updatedBody).getAsJsonObject();
        assertTrue("Body must have 'items' key", updatedObj.has("items"));
        JsonArray updatedItems = updatedObj.getAsJsonArray("items");
        assertEquals(2, updatedItems.size());
        assertTrue(updatedItems.get(0).getAsJsonObject().get("processed").getAsBoolean());
        assertTrue(updatedItems.get(1).getAsJsonObject().get("processed").getAsBoolean());
    }

    /**
     * Negative test: updateOriginalPayload with a variable-backed collection must NOT
     * modify the JSON body even when a JSON body exists in the original context.
     * The variable path must write to the variable, not the body.
     */
    public void testUpdateOriginalPayload_VariableBackedCollection_DoesNotTouchBody() throws Exception {

        // Original context has a JSON body (e.g. a POST request that also sets a variable)
        String bodyJson = "{\"other\":\"data\"}";
        Axis2MessageContext originalCtx = createJsonBodyMessageContext(bodyJson);

        // Set a variable array
        originalCtx.setVariable("myVar",
                JsonParser.parseString("{\"arr\":[{\"id\":1},{\"id\":2}]}"));

        ForEachMediatorV2 mediator = buildMediator("vars.myVar.arr", true);
        String mediatorId = mediator.getId();
        String correlationId = originalCtx.getMessageID();

        String[] payloads = {"{\"id\":1,\"done\":true}", "{\"id\":2,\"done\":true}"};
        ForEachAggregate aggregate = buildAggregate(mediatorId, originalCtx, correlationId, payloads);

        invokeUpdateOriginalPayload(mediator, originalCtx, aggregate);

        // Body must remain unchanged
        String updatedBody = JsonUtil.jsonPayloadToString(originalCtx.getAxis2MessageContext());
        JsonObject bodyObj = JsonParser.parseString(updatedBody).getAsJsonObject();
        assertEquals("data", bodyObj.get("other").getAsString());
        assertFalse("Body must not have 'arr' key", bodyObj.has("arr"));

        // Variable must be updated
        Object updatedVar = originalCtx.getVariable("myVar");
        assertNotNull(updatedVar);
        JsonObject varObj = JsonParser.parseString(updatedVar.toString()).getAsJsonObject();
        JsonArray arr = varObj.getAsJsonArray("arr");
        assertTrue(arr.get(0).getAsJsonObject().get("done").getAsBoolean());
        assertTrue(arr.get(1).getAsJsonObject().get("done").getAsBoolean());
    }
}
