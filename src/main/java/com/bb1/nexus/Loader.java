package com.bb1.nexus;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Scanner;
import java.util.UUID;

import com.massivecraft.factions.*;
import com.massivecraft.factions.struct.Relation;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.massivecraft.factions.event.FactionCreateEvent;
import com.massivecraft.factions.event.FactionDisbandEvent;
import com.massivecraft.factions.event.FactionDisbandEvent.PlayerDisbandReason;
import com.massivecraft.factions.event.FactionRenameEvent;
import com.massivecraft.factions.event.LandUnclaimAllEvent;
import com.massivecraft.factions.event.LandUnclaimEvent;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import static com.massivecraft.factions.zcore.fperms.Access.*;
import static com.massivecraft.factions.zcore.fperms.PermissableAction.*;

public class Loader extends JavaPlugin implements Listener {

	private Map<Location, Faction> nexusMap = new HashMap<Location, Faction>();
	private Map<OfflinePlayer, Location> playersThatCanMakeFaction = new HashMap<OfflinePlayer, Location>();
	private LocaleManager localeManager;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private ItemStack nexusItemStack() {
		ItemStack itemStack = new ItemStack(Material.BEACON);
		ItemMeta itemMeta = itemStack.getItemMeta();
		itemMeta.setDisplayName(localeManager.getMessage("item.nexus.name"));
		itemMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
		itemStack.setItemMeta(itemMeta);
		return itemStack;
	}

	private Recipe nexusRecipe() {
		ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(this, "nexus"), nexusItemStack().clone());
		recipe.shape("ggg", "gdg", "ooo");
		recipe.setIngredient('g', Material.GLASS);
		recipe.setIngredient('d', Material.DIAMOND);
		recipe.setIngredient('o', Material.OBSIDIAN);
		return recipe;
	}

	@Override
	public void onEnable() {
		new File(getDataFolder().getAbsolutePath()).mkdirs();
		this.localeManager = new LocaleManager(this);
		localeManager.loadMessages();

		Bukkit.getPluginManager().registerEvents(this, this);
		Bukkit.getServer().addRecipe(nexusRecipe());
		Bukkit.getScheduler().scheduleSyncDelayedTask(this, this::load);
	}

	@Override
	public void onDisable() {
		save();
	}

	@EventHandler
	public void FactionCreateEvent(FactionCreateEvent event) {
		if (!playersThatCanMakeFaction.containsKey(event.getFPlayer().getPlayer()) ||
				event.getFactionTag().equals(localeManager.getMessage("faction.delete_name"))) {
			event.setCancelled(true);
			event.getFPlayer().getPlayer().sendMessage(localeManager.getMessage("error.need_nexus_to_create_faction"));
		} else {
			final Location nexus = playersThatCanMakeFaction.get(event.getFPlayer().getPlayer()).clone();
			playersThatCanMakeFaction.remove(event.getFPlayer().getPlayer());
			Bukkit.getScheduler().scheduleSyncDelayedTask(this, () -> {
				FPlayer fPlayer = event.getFPlayer();
				Faction faction = event.getFPlayer().getFaction();
				nexusMap.put(nexus, faction);
				faction.setPowerBoost(faction.getPowerBoost() + 1);
				fPlayer.alterPower(1);
				fPlayer.attemptClaim(faction, nexus, false);
				nexus.getWorld().playSound(nexus, Sound.valueOf(localeManager.getMessage("sound.nexus_activate")), 500, 1);
			}, 4L);
		}
	}

	@EventHandler
	public void LandUnclaimEvent(LandUnclaimEvent event) {
		Chunk chunk = event.getLocation().getChunk();
		nexusMap.forEach((k, v) -> {
			if (event.isCancelled()) return;
			Chunk chunk2 = k.getChunk();
			if (!chunk2.isLoaded()) {
				return;
			} else if (k.getChunk().equals(chunk)) {
				event.setCancelled(true);
				event.getfPlayer().getPlayer().sendMessage(localeManager.getMessage("error.cannot_unclaim_nexus_chunk"));
			}
		});
	}

	@EventHandler
	public void LandUnclaimAllEvent(LandUnclaimAllEvent event) {
		event.setCancelled(true);
		for (Entry<Location, Faction> entry : nexusMap.entrySet()) {
			if (entry.getValue().getId().equals(event.getFaction().getId())) {
				event.getfPlayer().attemptClaim(entry.getValue(), entry.getKey(), false);
				return;
			}
		}
	}

	@EventHandler
	public void FactionRenameEvent(FactionRenameEvent event) {
		for (Entry<Location, Faction> entry : nexusMap.entrySet()) {
			if (entry.getValue().getId().equals(event.getFaction().getId())) {
				if (event.getFactionTag().equals(localeManager.getMessage("faction.delete_name"))) {
					event.setCancelled(true);
				}
				return;
			}
		}
	}

	@EventHandler
	public void FactionDisbandEvent(FactionDisbandEvent event) {
		if (!(event.getFaction().getTag().equals(localeManager.getMessage("faction.delete_name")))) {
			event.setCancelled(true);
			event.getFPlayer().getPlayer().sendMessage(localeManager.getMessage("error.disband_by_breaking_nexus"));
		}
	}

	@EventHandler
	public void BlockPlaceEvent(BlockPlaceEvent event) {
		ItemStack itemStack = event.getItemInHand();
		if (itemStack.hasItemMeta() &&
				itemStack.getItemMeta().getDisplayName().equals(localeManager.getMessage("item.nexus.name")) &&
				itemStack.getType().equals(Material.BEACON)) {

			if (FPlayers.getInstance().getByPlayer(event.getPlayer()).hasFaction()) {
				event.setCancelled(true);
				event.getPlayer().sendMessage(localeManager.getMessage("error.cannot_place_nexus_in_faction"));
			} else if (Board.getInstance().getFactionAt(new FLocation(event.getBlock().getLocation())) != Factions.getInstance().getWilderness()) {
				event.setCancelled(true);
				event.getPlayer().sendMessage(localeManager.getMessage("error.cannot_place_nexus_not_wilderness"));
			} else {
				playersThatCanMakeFaction.put(event.getPlayer(), event.getBlock().getLocation());
			}
		}
	}

	@EventHandler
	public void BlockBreakEvent(BlockBreakEvent event) {
		if (event.getBlock() != null && event.getBlock().getType().equals(Material.BEACON)) {
			FPlayer fplayer = FPlayers.getInstance().getByPlayer(event.getPlayer());
			Faction breakerPlayerFaction = fplayer.getFaction();
			Faction brokenBlockFaction = Board.getInstance().getFactionAt(new FLocation(event.getBlock().getLocation()));
			Relation fRelation = brokenBlockFaction.getRelationTo(breakerPlayerFaction);
			if (!nexusMap.containsKey(event.getBlock().getLocation()))
			{
				event.setCancelled(true);
				return;
			}

			switch (fRelation) {
				case MEMBER:
				case ALLY:
					if (brokenBlockFaction.getAccess(fplayer, DISBAND) != ALLOW)
					{
						event.getPlayer().sendMessage(localeManager.getMessage("error.deny_destroy_disband"));
						event.setCancelled(true);
						return;
					}
					break;
				case TRUCE:
					if (brokenBlockFaction.getAccess(fplayer, DISBAND) != ALLOW)
					{
						event.getPlayer().sendMessage(localeManager.getMessage("error.deny_destroy_truce"));
						event.setCancelled(true);
						return;
					}
					break;
				case ENEMY:
				case NEUTRAL:
					if (brokenBlockFaction.getAccess(fplayer, DESTROY) != ALLOW)
					{
						event.setCancelled(true);
						return;
					}
					break;
			}

			Faction faction = nexusMap.get(event.getBlock().getLocation());
			nexusMap.remove(event.getBlock().getLocation());
			Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
					localeManager.getMessage("faction.defeated").replace("{faction}", faction.getTag())));
			faction.setTag(localeManager.getMessage("faction.delete_name"));
			faction.disband(faction.getFPlayerLeader().getPlayer(), PlayerDisbandReason.PLUGIN);
			faction.remove();
			event.setDropItems(false);
			if (playersThatCanMakeFaction.containsValue(event.getBlock().getLocation())) event.setCancelled(true);
		}
	}

	@EventHandler
	public void EntityExplodeEvent(EntityExplodeEvent event) {
		if (!Boolean.parseBoolean(localeManager.getMessage("settings.block_explosions"))) return;
		List<Block> removedBlocks = new ArrayList<Block>();
		event.blockList().forEach(b -> {
			Location loc = b.getLocation().clone();
			if (playersThatCanMakeFaction.containsValue(loc) || nexusMap.containsKey(loc)) {
				removedBlocks.add(b);
			}
		});
		event.blockList().removeAll(removedBlocks);
	}

	@EventHandler
	public void BlockExplodeEvent(BlockExplodeEvent event) {
		if (!Boolean.parseBoolean(localeManager.getMessage("settings.block_explosions"))) return;
		List<Block> removedBlocks = new ArrayList<Block>();
		event.blockList().forEach(b -> {
			Location loc = b.getLocation().clone();
			if (playersThatCanMakeFaction.containsValue(loc) || nexusMap.containsKey(loc)) {
				removedBlocks.add(b);
			}
		});
		event.blockList().removeAll(removedBlocks);
	}

	@EventHandler
	public void onAnvil(InventoryClickEvent event) {
		if (event.getWhoClicked() == null || !(event.getWhoClicked() instanceof Player) ||
				event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR ||
				event.getInventory().getType() != InventoryType.ANVIL) {
			return;
		}
		if (event.getSlotType() == InventoryType.SlotType.RESULT &&
				event.getCurrentItem().getType().equals(Material.BEACON) &&
				event.getCurrentItem().getItemMeta().getItemFlags().contains(ItemFlag.HIDE_ATTRIBUTES)) {
			event.setCancelled(true);
			event.getWhoClicked().sendMessage(localeManager.getMessage("error.cannot_rename_nexus"));
		}
	}

	@EventHandler
	public void PlayerMoveEvent(PlayerMoveEvent event) {
		if (playersThatCanMakeFaction.containsKey(event.getPlayer())) {
			event.setCancelled(true);
			event.getPlayer().spigot().sendMessage(
					ChatMessageType.ACTION_BAR,
					new TextComponent(localeManager.getMessage("actionbar.create_faction")));
		}
	}

	private void save() {
		try {
			File file = new File(getDataFolder().getAbsolutePath(), "nexus.json");
			file.createNewFile();
			JsonObject jsonObject = new JsonObject();
			for (Entry<Location, Faction> entry : nexusMap.entrySet()) {
				jsonObject.addProperty(entry.getValue().getId(), loc(entry.getKey()));
			}
			try (BufferedWriter b = new BufferedWriter(new FileWriter(file))) {
				b.write(GSON.toJson(jsonObject));
			}

			File file2 = new File(getDataFolder().getAbsolutePath(), "extra.json");
			file2.createNewFile();
			JsonObject jsonObject2 = new JsonObject();
			for (Entry<OfflinePlayer, Location> entry : playersThatCanMakeFaction.entrySet()) {
				jsonObject2.addProperty(entry.getKey().getUniqueId().toString(), loc(entry.getValue()));
			}
			try (BufferedWriter b2 = new BufferedWriter(new FileWriter(file2))) {
				b2.write(GSON.toJson(jsonObject2));
			}
		} catch (Exception e) {
			getLogger().severe("Failed to save data: " + e.getMessage());
		}
	}

	private void load() {
		try {
			File file = new File(getDataFolder().getAbsolutePath(), "nexus.json");
			if (file.exists()) {
				Scanner s = new Scanner(file);
				ArrayList<String> r = new ArrayList<>();
				while (s.hasNext()) {
					r.add(s.nextLine());
				}
				s.close();
				JsonObject jsonObject = new JsonParser().parse(String.join("", r)).getAsJsonObject();
				Factions factions = Factions.getInstance();
				for (Entry<String, JsonElement> entry : jsonObject.entrySet()) {
					nexusMap.put(loc(entry.getValue().getAsString()), factions.getFactionById(entry.getKey()));
				}
			}

			File file2 = new File(getDataFolder().getAbsolutePath(), "extra.json");
			if (file2.exists()) {
				Scanner s2 = new Scanner(file2);
				ArrayList<String> r2 = new ArrayList<>();
				while (s2.hasNext()) {
					r2.add(s2.nextLine());
				}
				s2.close();
				JsonObject jsonObject2 = new JsonParser().parse(String.join("", r2)).getAsJsonObject();
				for (Entry<String, JsonElement> entry : jsonObject2.entrySet()) {
					playersThatCanMakeFaction.put(
							Bukkit.getOfflinePlayer(UUID.fromString(entry.getKey())),
							loc(entry.getValue().getAsString()));
				}
			}
		} catch (Exception e) {
			getLogger().severe("Failed to load data: " + e.getMessage());
		}
	}

	private String loc(Location location) {
		return location.getWorld().getUID().toString() + ";" +
				location.getBlockX() + ";" +
				location.getBlockY() + ";" +
				location.getBlockZ();
	}

	private Location loc(String str) {
		String[] s = str.split(";");
		return new Location(
				Bukkit.getWorld(UUID.fromString(s[0])),
				Integer.parseInt(s[1]),
				Integer.parseInt(s[2]),
				Integer.parseInt(s[3]));
	}

	private static class LocaleManager {
		private final JavaPlugin plugin;
		private final Properties messages = new Properties();

		public LocaleManager(JavaPlugin plugin) {
			this.plugin = plugin;
		}

		public void loadMessages() {
			File messagesFile = new File(plugin.getDataFolder(), "messages.properties");
			if (!messagesFile.exists()) {
				plugin.saveResource("messages.properties", false);
			}

			try (InputStreamReader reader = new InputStreamReader(
					new FileInputStream(messagesFile), StandardCharsets.UTF_8)) {
				messages.load(reader);
			} catch (IOException e) {
				plugin.getLogger().severe("Failed to load messages: " + e.getMessage());
			}
		}

		public String getMessage(String key) {
			return messages.getProperty(key, "Missing message for key: " + key);
		}
	}
}