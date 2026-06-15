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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

@ConfigGroup(Rs3InterfaceConfig.GROUP)
public interface Rs3InterfaceConfig extends Config
{
	String GROUP = "rs3interface";

	@ConfigItem(
		keyName = "editModeHotkey",
		name = "Edit mode hotkey",
		description = "Hotkey to toggle layout edit mode. While editing, drag panels to dock/undock them.",
		position = 0
	)
	default Keybind editModeHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "snapToGrid",
		name = "Snap to grid",
		description = "Snap docks to a grid while dragging in edit mode.",
		position = 1
	)
	default boolean snapToGrid()
	{
		return false;
	}

	@ConfigItem(
		keyName = "gridSize",
		name = "Grid size",
		description = "Grid size in pixels for snap-to-grid.",
		position = 2
	)
	default int gridSize()
	{
		return 8;
	}

	@ConfigSection(
		name = "Panels",
		description = "Per-panel enable toggles. Disabled panels are never managed and behave like vanilla.",
		position = 10
	)
	String panelsSection = "panels";

	@ConfigItem(keyName = "panelCombat", name = "Combat", description = "Allow managing the Combat tab.", position = 0, section = panelsSection)
	default boolean panelCombat()
	{
		return true;
	}

	@ConfigItem(keyName = "panelSkills", name = "Skills", description = "Allow managing the Skills tab.", position = 1, section = panelsSection)
	default boolean panelSkills()
	{
		return true;
	}

	@ConfigItem(keyName = "panelQuests", name = "Quests/Journal", description = "Allow managing the Quests tab.", position = 2, section = panelsSection)
	default boolean panelQuests()
	{
		return true;
	}

	@ConfigItem(keyName = "panelInventory", name = "Inventory", description = "Allow managing the Inventory tab.", position = 3, section = panelsSection)
	default boolean panelInventory()
	{
		return true;
	}

	@ConfigItem(keyName = "panelEquipment", name = "Equipment", description = "Allow managing the Equipment tab.", position = 4, section = panelsSection)
	default boolean panelEquipment()
	{
		return true;
	}

	@ConfigItem(keyName = "panelPrayer", name = "Prayer", description = "Allow managing the Prayer tab.", position = 5, section = panelsSection)
	default boolean panelPrayer()
	{
		return true;
	}

	@ConfigItem(keyName = "panelMagic", name = "Magic", description = "Allow managing the Magic tab.", position = 6, section = panelsSection)
	default boolean panelMagic()
	{
		return true;
	}

	@ConfigItem(keyName = "panelFriendsChat", name = "Friends/Clan chat", description = "Allow managing the Friends Chat tab.", position = 7, section = panelsSection)
	default boolean panelFriendsChat()
	{
		return true;
	}

	@ConfigItem(keyName = "panelIgnores", name = "Ignores", description = "Allow managing the Ignores tab.", position = 8, section = panelsSection)
	default boolean panelIgnores()
	{
		return true;
	}

	@ConfigItem(keyName = "panelFriends", name = "Friends", description = "Allow managing the Friends tab.", position = 9, section = panelsSection)
	default boolean panelFriends()
	{
		return true;
	}

	@ConfigItem(keyName = "panelLogout", name = "Logout", description = "Allow managing the Logout tab (quirky, off by default).", position = 10, section = panelsSection)
	default boolean panelLogout()
	{
		return false;
	}

	@ConfigItem(keyName = "panelSettings", name = "Settings", description = "Allow managing the Settings tab (quirky, off by default).", position = 11, section = panelsSection)
	default boolean panelSettings()
	{
		return false;
	}

	@ConfigItem(keyName = "panelEmotes", name = "Emotes", description = "Allow managing the Emotes tab.", position = 12, section = panelsSection)
	default boolean panelEmotes()
	{
		return true;
	}

	@ConfigItem(keyName = "panelMusic", name = "Music", description = "Allow managing the Music tab.", position = 13, section = panelsSection)
	default boolean panelMusic()
	{
		return true;
	}
}
