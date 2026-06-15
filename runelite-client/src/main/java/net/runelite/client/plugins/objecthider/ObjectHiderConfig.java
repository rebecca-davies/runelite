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

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ObjectHiderConfig.CONFIG_GROUP)
public interface ObjectHiderConfig extends Config
{
	String CONFIG_GROUP = "objecthider";

	@ConfigItem(
		position = 0,
		keyName = "enabled",
		name = "Hide objects",
		description = "Removes hidden game objects from the scene so they no longer render."
	)
	default boolean enabled()
	{
		return true;
	}

	@ConfigItem(
		position = 1,
		keyName = "revealHidden",
		name = "Reveal hidden objects",
		description = "Re-renders hidden objects with a tint so they can be right-clicked and unhidden."
	)
	default boolean revealHidden()
	{
		return false;
	}

	@Alpha
	@ConfigItem(
		position = 2,
		keyName = "revealColor",
		name = "Reveal tint",
		description = "The tint used to highlight revealed hidden objects."
	)
	default Color revealColor()
	{
		return new Color(255, 0, 0, 100);
	}
}
