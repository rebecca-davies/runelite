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

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Owns the list of {@link Dock}s and persists them as JSON via {@link ConfigManager}.
 *
 * <p>Default layout = vanilla: no docks, nothing managed. The plugin only takes over a panel once
 * the user docks it in edit mode.</p>
 *
 * <p>The mutating ops (move / split-out / merge / set-active) and {@link #clamp} are pure and have
 * no client dependency, so they are unit-tested directly.</p>
 */
@Slf4j
class LayoutManager
{
	static final String CONFIG_KEY = "docks";

	private final ConfigManager configManager;
	private final Gson gson;

	private List<Dock> docks = new ArrayList<>();

	@Inject
	LayoutManager(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	/** Test-only constructor: no ConfigManager-backed persistence. */
	LayoutManager(Gson gson)
	{
		this.configManager = null;
		this.gson = gson;
	}

	List<Dock> getDocks()
	{
		return docks;
	}

	void load()
	{
		docks = deserialize(configManager == null ? null
			: configManager.getConfiguration(Rs3InterfaceConfig.GROUP, CONFIG_KEY));
	}

	void save()
	{
		if (configManager == null)
		{
			return;
		}
		if (docks.isEmpty())
		{
			configManager.unsetConfiguration(Rs3InterfaceConfig.GROUP, CONFIG_KEY);
		}
		else
		{
			configManager.setConfiguration(Rs3InterfaceConfig.GROUP, CONFIG_KEY, serialize(docks));
		}
	}

	void reset()
	{
		docks = new ArrayList<>();
		save();
	}

	// --- serialization ------------------------------------------------------

	String serialize(List<Dock> docks)
	{
		return gson.toJson(docks);
	}

	List<Dock> deserialize(@Nullable String json)
	{
		if (Strings.isNullOrEmpty(json))
		{
			return new ArrayList<>();
		}
		try
		{
			List<Dock> parsed = gson.fromJson(json, new TypeToken<List<Dock>>()
			{
			}.getType());
			return parsed == null ? new ArrayList<>() : parsed;
		}
		catch (RuntimeException ex)
		{
			log.warn("Failed to parse rs3interface dock layout, resetting", ex);
			return new ArrayList<>();
		}
	}

	// --- queries ------------------------------------------------------------

	@Nullable
	Dock dockOf(ManagedPanel panel)
	{
		for (Dock dock : docks)
		{
			if (dock.contains(panel))
			{
				return dock;
			}
		}
		return null;
	}

	boolean isManaged(ManagedPanel panel)
	{
		return dockOf(panel) != null;
	}

	// --- mutating operations ------------------------------------------------

	/**
	 * Splits a panel out into its own new dock at (x, y). If the panel is already docked, it is
	 * first removed from its current dock (which is discarded if it becomes empty).
	 */
	Dock splitOut(ManagedPanel panel, int x, int y)
	{
		removeFromCurrentDock(panel);
		Dock dock = new Dock(x, y, panel);
		docks.add(dock);
		return dock;
	}

	/**
	 * Merges a panel into the target dock (adds its stone, makes it active). If the panel was in
	 * another dock it is removed from there first.
	 */
	void merge(ManagedPanel panel, Dock target)
	{
		if (target.contains(panel))
		{
			target.setActive(panel);
			return;
		}
		removeFromCurrentDock(panel);
		target.addMember(panel);
	}

	/** Moves a whole dock to a new top-left position. */
	void move(Dock dock, int x, int y)
	{
		dock.setX(x);
		dock.setY(y);
	}

	/** Sets the active member of the dock containing the panel. No-op if the panel isn't docked. */
	void setActive(ManagedPanel panel)
	{
		Dock dock = dockOf(panel);
		if (dock != null)
		{
			dock.setActive(panel);
		}
	}

	private void removeFromCurrentDock(ManagedPanel panel)
	{
		Dock current = dockOf(panel);
		if (current != null && !current.removeMember(panel))
		{
			docks.remove(current);
		}
	}

	// --- viewport clamping --------------------------------------------------

	/**
	 * Clamps every dock so its top-left stays within [0, viewportW - dockW] x [0, viewportH -
	 * dockH]. If a dock is larger than the viewport it is pinned to the top-left (0,0).
	 */
	void clampAll(int viewportW, int viewportH, int dockW, int dockH)
	{
		for (Dock dock : docks)
		{
			dock.setX(clamp(dock.getX(), dockW, viewportW));
			dock.setY(clamp(dock.getY(), dockH, viewportH));
		}
	}

	static int clamp(int pos, int size, int extent)
	{
		int max = extent - size;
		if (max < 0)
		{
			return 0;
		}
		if (pos < 0)
		{
			return 0;
		}
		return Math.min(pos, max);
	}
}
