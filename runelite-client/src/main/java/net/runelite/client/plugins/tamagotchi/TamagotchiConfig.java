package net.runelite.client.plugins.tamagotchi;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("tamagotchi")
public interface TamagotchiConfig extends Config
{
	@ConfigItem(
		keyName = "romPath",
		name = "ROM path",
		description = "Path to the Tamagotchi P1 ROM binary (tama.b).",
		position = 1
	)
	default String romPath()
	{
		return "";
	}

	@Range(
		min = 2,
		max = 8
	)
	@ConfigItem(
		keyName = "pixelSize",
		name = "Pixel size",
		description = "Size of each LCD pixel.",
		position = 2
	)
	default int pixelSize()
	{
		return 4;
	}

	@ConfigItem(
		keyName = "keyLeft",
		name = "Left button key",
		description = "Key name for the A (left) button.",
		position = 3
	)
	default String keyLeft()
	{
		return "1";
	}

	@ConfigItem(
		keyName = "keyMiddle",
		name = "Middle button key",
		description = "Key name for the B (middle) button.",
		position = 4
	)
	default String keyMiddle()
	{
		return "2";
	}

	@ConfigItem(
		keyName = "keyRight",
		name = "Right button key",
		description = "Key name for the C (right) button.",
		position = 5
	)
	default String keyRight()
	{
		return "3";
	}

	@ConfigItem(
		keyName = "muted",
		name = "Mute sound",
		description = "Mute the Tamagotchi beeper.",
		position = 6
	)
	default boolean muted()
	{
		return false;
	}
}
