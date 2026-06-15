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

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A dock = a screen position + an ordered set of member panels + one active member.
 *
 * <p>Members are stored by {@link ManagedPanel#name()} so the JSON form is stable and human
 * readable. The active member is also stored by name; it is kept valid by the mutating ops.</p>
 */
@Data
class Dock
{
	/** Top-left x of the dock content area, in canvas pixels. */
	private int x;
	/** Top-left y of the dock content area, in canvas pixels. */
	private int y;
	/** Ordered member panels, by {@link ManagedPanel#name()}. */
	private List<String> members = new ArrayList<>();
	/** The active member's {@link ManagedPanel#name()}; shown above the strip. */
	private String activeMember;

	Dock()
	{
	}

	Dock(int x, int y, ManagedPanel panel)
	{
		this.x = x;
		this.y = y;
		this.members.add(panel.name());
		this.activeMember = panel.name();
	}

	boolean contains(ManagedPanel panel)
	{
		return members.contains(panel.name());
	}

	void addMember(ManagedPanel panel)
	{
		if (!members.contains(panel.name()))
		{
			members.add(panel.name());
		}
		// New member becomes active.
		activeMember = panel.name();
	}

	/**
	 * Removes a member. Returns true if the dock still has members afterwards; false if it is now
	 * empty (and should be discarded by the caller).
	 */
	boolean removeMember(ManagedPanel panel)
	{
		members.remove(panel.name());
		if (panel.name().equals(activeMember))
		{
			activeMember = members.isEmpty() ? null : members.get(0);
		}
		return !members.isEmpty();
	}

	void setActive(ManagedPanel panel)
	{
		if (members.contains(panel.name()))
		{
			activeMember = panel.name();
		}
	}

	ManagedPanel activePanel()
	{
		if (activeMember == null)
		{
			return null;
		}
		try
		{
			return ManagedPanel.valueOf(activeMember);
		}
		catch (IllegalArgumentException ex)
		{
			return null;
		}
	}

	List<ManagedPanel> memberPanels()
	{
		List<ManagedPanel> out = new ArrayList<>(members.size());
		for (String name : members)
		{
			try
			{
				out.add(ManagedPanel.valueOf(name));
			}
			catch (IllegalArgumentException ignored)
			{
				// drop unknown panel names (e.g. removed in a future version)
			}
		}
		return out;
	}
}
