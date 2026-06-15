/*
 * Copyright (c) 2026, Rebecca Davies <rebeccad1558@gmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.objecthider;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class ObjectHiderPluginTest
{
	private final Gson gson = new Gson();

	@Test
	public void testHiddenObjectJsonRoundTrip()
	{
		HiddenObject point = new HiddenObject(1234, "Tree", 12850, 32, 16, 0);

		String json = gson.toJson(Set.of(point));
		Set<HiddenObject> loaded = gson.fromJson(json, new TypeToken<Set<HiddenObject>>()
		{
		}.getType());

		Assert.assertEquals(1, loaded.size());
		HiddenObject result = loaded.iterator().next();
		Assert.assertEquals(point, result);
		Assert.assertEquals(1234, result.getId());
		Assert.assertEquals("Tree", result.getName());
		Assert.assertEquals(12850, result.getRegionId());
		Assert.assertEquals(32, result.getRegionX());
		Assert.assertEquals(16, result.getRegionY());
		Assert.assertEquals(0, result.getZ());
	}

	@Test
	public void testHiddenObjectEquality()
	{
		// Persistence is keyed by id + region coordinates + plane, so two points sharing those
		// fields must be equal (and thus de-duplicated in the persisted Set).
		HiddenObject a = new HiddenObject(1234, "Tree", 12850, 32, 16, 0);
		HiddenObject b = new HiddenObject(1234, "Tree", 12850, 32, 16, 0);
		HiddenObject differentTile = new HiddenObject(1234, "Tree", 12850, 33, 16, 0);

		Assert.assertEquals(a, b);
		Assert.assertEquals(a.hashCode(), b.hashCode());
		Assert.assertNotEquals(a, differentTile);

		Set<HiddenObject> set = Set.of(a);
		Assert.assertTrue(set.contains(b));
		Assert.assertFalse(set.contains(differentTile));
	}
}
