/*
 * Copyright (c) 2026, RuneLite contributors
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
package net.runelite.client.plugins.rs3interface;

import com.google.gson.Gson;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class LayoutManagerTest
{
	private LayoutManager layoutManager;

	@Before
	public void before()
	{
		layoutManager = new LayoutManager(new Gson());
	}

	@Test
	public void testJsonRoundTrip()
	{
		Dock a = new Dock(100, 200, ManagedPanel.INVENTORY);
		a.addMember(ManagedPanel.PRAYER);
		a.setActive(ManagedPanel.INVENTORY);
		Dock b = new Dock(300, 50, ManagedPanel.MAGIC);

		String json = layoutManager.serialize(List.of(a, b));
		List<Dock> parsed = layoutManager.deserialize(json);

		assertEquals(2, parsed.size());

		Dock pa = parsed.get(0);
		assertEquals(100, pa.getX());
		assertEquals(200, pa.getY());
		assertEquals(List.of("INVENTORY", "PRAYER"), pa.getMembers());
		assertEquals("INVENTORY", pa.getActiveMember());
		assertEquals(ManagedPanel.INVENTORY, pa.activePanel());

		Dock pb = parsed.get(1);
		assertEquals(300, pb.getX());
		assertEquals(ManagedPanel.MAGIC, pb.activePanel());
	}

	@Test
	public void testDeserializeEmptyAndNull()
	{
		assertTrue(layoutManager.deserialize(null).isEmpty());
		assertTrue(layoutManager.deserialize("").isEmpty());
		assertTrue(layoutManager.deserialize("not valid json {{{").isEmpty());
	}

	@Test
	public void testClamp()
	{
		// within range
		assertEquals(50, LayoutManager.clamp(50, 100, 800));
		// negative -> 0
		assertEquals(0, LayoutManager.clamp(-20, 100, 800));
		// past right edge -> extent - size
		assertEquals(700, LayoutManager.clamp(999, 100, 800));
		// element larger than the viewport -> pinned to 0
		assertEquals(0, LayoutManager.clamp(40, 900, 800));
	}

	@Test
	public void testClampAll()
	{
		Dock dock = new Dock(1000, -30, ManagedPanel.MAGIC);
		layoutManager.splitOut(ManagedPanel.MAGIC, 1000, -30);

		layoutManager.clampAll(800, 600, 200, 300);

		Dock d = layoutManager.getDocks().get(0);
		assertEquals(600, d.getX()); // 800 - 200
		assertEquals(0, d.getY());   // clamped from -30
	}

	@Test
	public void testSplitOutCreatesDock()
	{
		Dock dock = layoutManager.splitOut(ManagedPanel.INVENTORY, 10, 20);

		assertEquals(1, layoutManager.getDocks().size());
		assertEquals(List.of("INVENTORY"), dock.getMembers());
		assertEquals(ManagedPanel.INVENTORY, dock.activePanel());
		assertTrue(layoutManager.isManaged(ManagedPanel.INVENTORY));
	}

	@Test
	public void testMergeAddsAndActivates()
	{
		Dock target = layoutManager.splitOut(ManagedPanel.INVENTORY, 10, 20);
		layoutManager.merge(ManagedPanel.PRAYER, target);

		assertEquals(1, layoutManager.getDocks().size());
		assertEquals(List.of("INVENTORY", "PRAYER"), target.getMembers());
		// newly merged member becomes active
		assertEquals(ManagedPanel.PRAYER, target.activePanel());
	}

	@Test
	public void testMergeMovesPanelBetweenDocks()
	{
		Dock dockA = layoutManager.splitOut(ManagedPanel.INVENTORY, 10, 20);
		Dock dockB = layoutManager.splitOut(ManagedPanel.PRAYER, 100, 20);
		assertEquals(2, layoutManager.getDocks().size());

		// move PRAYER (a lone dock) into dockA -> dockB becomes empty and is discarded
		layoutManager.merge(ManagedPanel.PRAYER, dockA);

		assertEquals(1, layoutManager.getDocks().size());
		assertEquals(dockA, layoutManager.getDocks().get(0));
		assertTrue(dockA.contains(ManagedPanel.PRAYER));
		assertFalse(layoutManager.getDocks().contains(dockB));
	}

	@Test
	public void testSplitOutOfExistingDockRemovesFromOld()
	{
		Dock dock = layoutManager.splitOut(ManagedPanel.INVENTORY, 10, 20);
		layoutManager.merge(ManagedPanel.PRAYER, dock);
		assertEquals(List.of("INVENTORY", "PRAYER"), dock.getMembers());

		// split PRAYER back out to its own dock
		Dock newDock = layoutManager.splitOut(ManagedPanel.PRAYER, 200, 200);

		assertEquals(2, layoutManager.getDocks().size());
		assertEquals(List.of("INVENTORY"), dock.getMembers());
		assertEquals(List.of("PRAYER"), newDock.getMembers());
	}

	@Test
	public void testSetActive()
	{
		Dock dock = layoutManager.splitOut(ManagedPanel.INVENTORY, 10, 20);
		layoutManager.merge(ManagedPanel.PRAYER, dock);
		assertEquals(ManagedPanel.PRAYER, dock.activePanel());

		layoutManager.setActive(ManagedPanel.INVENTORY);
		assertEquals(ManagedPanel.INVENTORY, dock.activePanel());

		// setting active for an unmanaged panel is a no-op
		layoutManager.setActive(ManagedPanel.MAGIC);
		assertEquals(ManagedPanel.INVENTORY, dock.activePanel());
	}

	@Test
	public void testRemoveLastMemberDiscardsDock()
	{
		Dock dock = layoutManager.splitOut(ManagedPanel.INVENTORY, 10, 20);
		// move the sole member into a fresh dock; the old dock should be discarded
		layoutManager.splitOut(ManagedPanel.INVENTORY, 300, 300);

		assertEquals(1, layoutManager.getDocks().size());
		assertFalse(layoutManager.getDocks().contains(dock));
	}

	@Test
	public void testDockOf()
	{
		Dock dock = layoutManager.splitOut(ManagedPanel.MAGIC, 0, 0);
		assertEquals(dock, layoutManager.dockOf(ManagedPanel.MAGIC));
		assertNull(layoutManager.dockOf(ManagedPanel.INVENTORY));
		assertNotNull(layoutManager.dockOf(ManagedPanel.MAGIC));
	}
}
