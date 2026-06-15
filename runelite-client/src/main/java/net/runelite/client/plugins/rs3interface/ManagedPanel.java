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

import java.util.function.Function;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.InterfaceID.ToplevelOsrsStretch;

/**
 * The 14 OSRS side-tab panels in resizable ("stretch") mode.
 *
 * <p>The component IDs come from {@code net.runelite.api.gameval.InterfaceID.ToplevelOsrsStretch}.
 * Each tab N has a content container {@code SIDE{N}}, a tab stone button {@code STONE{N}}, and the
 * game's active-tab var ({@code VarClientID.TOPLEVEL_PANEL}) holds the index N of the active tab.
 * The tab indices and stone assignments were cross-checked against
 * {@code interfacestyles.WidgetOffset} (RESIZABLE_2010_* highlights map STONE0..STONE13 to tabs)
 * and {@code itemstats.ItemStatPlugin} (which uses {@code SIDE3} for the inventory container).</p>
 */
@Getter
@RequiredArgsConstructor
enum ManagedPanel
{
	COMBAT(0, "Combat", ToplevelOsrsStretch.SIDE0, ToplevelOsrsStretch.STONE0, Rs3InterfaceConfig::panelCombat),
	SKILLS(1, "Skills", ToplevelOsrsStretch.SIDE1, ToplevelOsrsStretch.STONE1, Rs3InterfaceConfig::panelSkills),
	QUESTS(2, "Quests", ToplevelOsrsStretch.SIDE2, ToplevelOsrsStretch.STONE2, Rs3InterfaceConfig::panelQuests),
	INVENTORY(3, "Inventory", ToplevelOsrsStretch.SIDE3, ToplevelOsrsStretch.STONE3, Rs3InterfaceConfig::panelInventory),
	EQUIPMENT(4, "Equipment", ToplevelOsrsStretch.SIDE4, ToplevelOsrsStretch.STONE4, Rs3InterfaceConfig::panelEquipment),
	PRAYER(5, "Prayer", ToplevelOsrsStretch.SIDE5, ToplevelOsrsStretch.STONE5, Rs3InterfaceConfig::panelPrayer),
	MAGIC(6, "Magic", ToplevelOsrsStretch.SIDE6, ToplevelOsrsStretch.STONE6, Rs3InterfaceConfig::panelMagic),
	FRIENDS_CHAT(7, "Friends Chat", ToplevelOsrsStretch.SIDE7, ToplevelOsrsStretch.STONE7, Rs3InterfaceConfig::panelFriendsChat),
	IGNORES(8, "Ignores", ToplevelOsrsStretch.SIDE8, ToplevelOsrsStretch.STONE8, Rs3InterfaceConfig::panelIgnores),
	FRIENDS(9, "Friends", ToplevelOsrsStretch.SIDE9, ToplevelOsrsStretch.STONE9, Rs3InterfaceConfig::panelFriends),
	LOGOUT(10, "Logout", ToplevelOsrsStretch.SIDE10, ToplevelOsrsStretch.STONE10, Rs3InterfaceConfig::panelLogout),
	SETTINGS(11, "Settings", ToplevelOsrsStretch.SIDE11, ToplevelOsrsStretch.STONE11, Rs3InterfaceConfig::panelSettings),
	EMOTES(12, "Emotes", ToplevelOsrsStretch.SIDE12, ToplevelOsrsStretch.STONE12, Rs3InterfaceConfig::panelEmotes),
	MUSIC(13, "Music", ToplevelOsrsStretch.SIDE13, ToplevelOsrsStretch.STONE13, Rs3InterfaceConfig::panelMusic);

	/**
	 * The game's active-tab index. This matches the value held by
	 * {@code VarClientID.TOPLEVEL_PANEL} when this tab is selected.
	 */
	private final int tabIndex;
	private final String displayName;
	/** {@code ToplevelOsrsStretch.SIDE{tabIndex}} content container component id. */
	private final int containerComponentId;
	/** {@code ToplevelOsrsStretch.STONE{tabIndex}} tab stone button component id. */
	private final int stoneComponentId;
	private final Function<Rs3InterfaceConfig, Boolean> enabledFn;

	boolean isEnabled(Rs3InterfaceConfig config)
	{
		return enabledFn.apply(config);
	}

	static ManagedPanel forTabIndex(int tabIndex)
	{
		for (ManagedPanel panel : values())
		{
			if (panel.tabIndex == tabIndex)
			{
				return panel;
			}
		}
		return null;
	}
}
