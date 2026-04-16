/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.synapse.registry;

import junit.framework.TestCase;
import org.apache.axiom.om.OMNode;
import org.apache.synapse.config.Entry;
import org.apache.synapse.mediators.TestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Unit tests for AbstractRegistry.getResource() when a cached resource is deleted
 * at runtime after being cached (Issue #4889).
 *
 * The bug: when a registry resource file is deleted after being cached, the next
 * getResource() call after cache expiry throws NullPointerException because
 * getRegistryEntry() returns null but the caller does not null-check the result
 * before calling re.getVersion().
 *
 * The fix: add a null guard on the return value of getRegistryEntry() in the
 * isExpired() branch of AbstractRegistry.getResource(), returning null when the
 * resource no longer exists.
 */
public class AbstractRegistryDeletedResourceTest extends TestCase {

    private static final String RESOURCE_KEY = "test/flag";
    private static final String XML_CONTENT = "<flag>true</flag>";
    private static final long CACHE_DURATION_MS = 500L;

    private SimpleInMemoryRegistry registry;

    @Override
    public void setUp() {
        Map<String, OMNode> data = new HashMap<String, OMNode>();
        data.put(RESOURCE_KEY, TestUtils.createOMElement(XML_CONTENT));
        registry = new SimpleInMemoryRegistry(data, CACHE_DURATION_MS);
    }

    /**
     * Bug scenario (Issue #4889): a resource is cached, then deleted from the
     * registry, then the cache TTL expires. getResource() must return null instead
     * of throwing NullPointerException.
     *
     * This test fails on the unfixed AbstractRegistry (NPE on re.getVersion()) and
     * passes after the null-guard fix is applied.
     */
    public void testGetResourceReturnsNullAfterCacheExpiryWhenResourceDeleted() {
        Entry entry = new Entry(RESOURCE_KEY);
        entry.setType(Entry.REMOTE_ENTRY);

        // Initial load — populates the cache
        Object firstResult = registry.getResource(entry, new Properties());
        assertNotNull("Resource should be loaded on first access", firstResult);
        assertTrue("Entry should be cached after first load", entry.isCached());

        // Simulate runtime deletion of the registry resource (e.g. file removed from disk)
        registry.delete(RESOURCE_KEY);

        // Force cache expiry without sleeping
        entry.setExpiryTime(System.currentTimeMillis() - 1);
        assertTrue("Entry cache should be expired", entry.isExpired());

        // Without the fix this throws NullPointerException; with the fix it must return null
        Object resultAfterDeletion = registry.getResource(entry, new Properties());
        assertNull("getResource() must return null when the cached resource has been deleted "
                + "and cache TTL has expired", resultAfterDeletion);
    }

    /**
     * Edge case: resource still exists and is unmodified when the cache expires.
     * getResource() must renew the cache and return the (unchanged) cached value.
     */
    public void testGetResourceRenewsCacheWhenResourceExistsAndUnchanged() {
        Entry entry = new Entry(RESOURCE_KEY);
        entry.setType(Entry.REMOTE_ENTRY);

        Object first = registry.getResource(entry, new Properties());
        assertNotNull("First access should return the resource", first);

        // Force expiry without deleting the resource
        entry.setExpiryTime(System.currentTimeMillis() - 1);
        assertTrue("Entry should be expired before second access", entry.isExpired());

        Object second = registry.getResource(entry, new Properties());
        assertNotNull("Resource should still be returned after cache renewal when it was not deleted", second);
        assertFalse("Entry should no longer be expired after cache renewal", entry.isExpired());
    }

    /**
     * Negative case: a key that was never present in the registry.
     * getResource() must return null without throwing.
     */
    public void testGetResourceReturnsNullForNonExistentResource() {
        Entry entry = new Entry("no/such/resource");
        entry.setType(Entry.REMOTE_ENTRY);

        Object result = registry.getResource(entry, new Properties());
        assertNull("Non-existent resource must return null", result);
    }

    /**
     * Edge case: resource is deleted before the very first access (never cached).
     * getResource() must return null without throwing.
     */
    public void testGetResourceReturnsNullWhenResourceDeletedBeforeFirstAccess() {
        // Delete before any getResource() call
        registry.delete(RESOURCE_KEY);

        Entry entry = new Entry(RESOURCE_KEY);
        entry.setType(Entry.REMOTE_ENTRY);

        Object result = registry.getResource(entry, new Properties());
        assertNull("Resource deleted before first access must return null", result);
    }
}
