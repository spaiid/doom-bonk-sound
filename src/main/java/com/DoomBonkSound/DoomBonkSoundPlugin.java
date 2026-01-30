package com.DoomBonkSound;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import javax.inject.Inject;

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
import net.runelite.client.audio.AudioPlayer;



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
	@Inject private AudioPlayer audioPlayer;


	@Provides
	DoomBonkSoundConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DoomBonkSoundConfig.class);
	}


	private boolean doomWasCharging = false;
	private int lastMeleeSwingTick = -9999;
	private int lastInterruptTick = -9999;

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


	private void playInterruptSound(float gainDb)
	{
		if (audioPlayer == null)
		{
			return;
		}

		try
		{
			// Plays an audio stream loaded from a class resource path
			audioPlayer.play(getClass(), "/sounds/doom_interrupt.wav", gainDb);
		}
		catch (Exception e)
		{
			log.debug("Failed to play interrupt sound", e);
		}
	}

}
