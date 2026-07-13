/*
 * Copyright (c) 2025, bec
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
package net.runelite.client.plugins.objmodel;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("objmodel")
public interface ObjModelConfig extends Config
{
	@ConfigItem(
		keyName = "npcReplacements",
		name = "NPC Replacements",
		description = "One per line: npcName:model.obj:scale:rotation. "
			+ "Scale/rotation optional. Rotation: 0-3 (90° increments relative to NPC facing). "
			+ "Example: Banker:Yoshi/Yoshi.obj:1000:2",
		position = 0
	)
	default String npcReplacements()
	{
		return "";
	}

	@ConfigItem(
		keyName = "objectReplacements",
		name = "Object Replacements",
		description = "One per line: objectId:model.obj:scale:rotation. "
			+ "Scale/rotation optional. Rotation: 0-3 (90° increments). "
			+ "Example: 6:TackShooter/TackShooter.obj:1000:0",
		position = 1
	)
	default String objectReplacements()
	{
		return "";
	}

	@Range(min = 1, max = 100000)
	@ConfigItem(
		keyName = "scale",
		name = "Scale",
		description = "Scale factor applied to model vertices. "
			+ "128 = one OBJ unit maps to one OSRS unit (~1/128th of a tile).",
		position = 2
	)
	default int scale()
	{
		return 128;
	}
}
