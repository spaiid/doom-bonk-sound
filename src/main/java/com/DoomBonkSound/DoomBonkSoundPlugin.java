package com.DoomBonkSound;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.inject.Inject;
import javax.sound.sampled.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.game.ItemManager;

@Slf4j
@PluginDescriptor(
		name = "Doom Bonk Sound",
		description = "Plays a sound when Doom of Mokhaiotl is interrupted by melee.",
		tags = {"pvm", "sound", "doom", "mokhaiotl"}
)
public class DoomBonkSoundPlugin extends Plugin
{
	private static final ImmutableSet<Integer> DOOM_IDS = ImmutableSet.of(14707, 14708, 14709);
	private static final int CHARGE_ANIM_ID = 12409;

	@Inject private Client client;
	@Inject private DoomBonkSoundConfig config;
	@Inject private ItemManager itemManager;

	@Provides
	DoomBonkSoundConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DoomBonkSoundConfig.class);
	}

	private Clip interruptClip;

	private boolean doomWasCharging = false;
	private int lastMeleeSwingTick = -9999;
	private int lastInterruptTick = -9999;

	@Override
	protected void startUp()
	{
		try
		{
			interruptClip = loadClip("/sounds/doom_interrupt.wav");
		}
		catch (Exception e)
		{
			log.warn("Failed to load interrupt clip", e);
			interruptClip = null;
		}
	}

	@Override
	protected void shutDown()
	{
		if (interruptClip != null)
		{
			interruptClip.stop();
			interruptClip.close();
			interruptClip = null;
		}

		doomWasCharging = false;
		lastMeleeSwingTick = -9999;
		lastInterruptTick = -9999;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!config.enabled())
		{
			return;
		}

		final Player me = client.getLocalPlayer();
		if (me == null)
		{
			return;
		}

		final int now = client.getTickCount();
		final float gainDb = (float) config.gainDb();

		// 1) Is Doom charging right now?
		boolean doomChargingNow = false;
		for (NPC npc : client.getNpcs())
		{
			if (npc != null && DOOM_IDS.contains(npc.getId()) && npc.getAnimation() == CHARGE_ANIM_ID)
			{
				doomChargingNow = true;
				break;
			}
		}

		// 2) While Doom is charging: record "I swung melee at Doom recently"
		if (doomChargingNow && isMeleeWeaponEquipped() && me.getInteracting() instanceof NPC)
		{
			final NPC target = (NPC) me.getInteracting();
			if (target != null && DOOM_IDS.contains(target.getId()))
			{
				final int dist = me.getWorldArea().distanceTo(target.getWorldArea());
				if (dist <= 2 && me.getAnimation() != -1)
				{
					lastMeleeSwingTick = now;
				}
			}
		}

		// 3) Transition detection: charging -> not charging means "charge ended"
		if (doomChargingNow)
		{
			if (!doomWasCharging)
			{
				// new charge window; clear prior swing time
				lastMeleeSwingTick = -9999;
			}
			doomWasCharging = true;
			return;
		}

		// leaving charge: if you swung melee very recently, treat as your interrupt
		if (doomWasCharging)
		{
			doomWasCharging = false;

			final int ticksSinceSwing = now - lastMeleeSwingTick;
			if (now != lastInterruptTick && ticksSinceSwing >= 0 && ticksSinceSwing <= 4)
			{
				lastInterruptTick = now;
				playInterruptSound(gainDb);
			}
		}
	}

	private boolean isMeleeWeaponEquipped()
	{
		final ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equipment == null)
		{
			return false;
		}

		final Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (weapon == null || weapon.getId() <= 0)
		{
			return false;
		}

		final String name = itemManager.getItemComposition(weapon.getId()).getName();
		if (name == null)
		{
			return false;
		}

		final String n = name.toLowerCase();

		// Ranged-ish names
		if (n.contains("bow") || n.contains("crossbow") || n.contains("blowpipe") ||
				n.contains("ballista") || n.contains("chinchompa") ||
				n.contains("dart") || n.contains("knife") || n.contains("javelin") ||
				n.contains("thrown") || n.contains("throwing") || n.contains("toktz-xil-ul"))
		{
			return false;
		}

		// Magic-ish names
		if (n.contains("staff") || n.contains("wand") || n.contains("trident") ||
				n.contains("sceptre") || n.contains("scepter") || n.contains("tome") ||
				n.contains("kodai"))
		{
			return false;
		}

		return true; // assume melee
	}

	private Clip loadClip(String resourcePath)
			throws IOException, UnsupportedAudioFileException, LineUnavailableException
	{
		try (InputStream is = getClass().getResourceAsStream(resourcePath))
		{
			if (is == null)
			{
				throw new IOException("Missing resource: " + resourcePath);
			}

			try (AudioInputStream ais = AudioSystem.getAudioInputStream(new BufferedInputStream(is)))
			{
				final Clip clip = AudioSystem.getClip();
				clip.open(ais);
				return clip;
			}
		}
	}

	private void applyGain(Clip clip, float gainDb)
	{
		if (clip == null)
		{
			return;
		}

		if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN))
		{
			final FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
			final float clamped = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), gainDb));
			gain.setValue(clamped);
		}
	}

	private void playInterruptSound(float gainDb)
	{
		if (interruptClip == null)
		{
			return;
		}

		applyGain(interruptClip, gainDb);

		interruptClip.stop();
		interruptClip.setFramePosition(0);
		interruptClip.start();
	}
}
