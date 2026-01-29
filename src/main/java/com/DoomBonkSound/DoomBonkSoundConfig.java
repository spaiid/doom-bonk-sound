package com.DoomBonkSound;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("doombonksound")
public interface DoomBonkSoundConfig extends Config
{
	@ConfigItem(
			keyName = "enabled",
			name = "Enable sound",
			description = "Plays a bonk sound when Doom of Mokhaiotl is interrupted"
	)
	default boolean enabled()
	{
		return true;
	}

	@Range(min = -60, max = 12)
	@ConfigItem(
			keyName = "gainDb",
			name = "Volume (dB)",
			description = "0 = normal volume, negative is quieter, positive is louder"
	)
	default int gainDb()
	{
		return 0;
	}

}
