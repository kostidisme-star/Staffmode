package com.coresmp.staffmode;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StaffMode extends JavaPlugin implements Listener, CommandExecutor {

    private record SavedState(GameMode gameMode, Location location) {}

    private final Map<UUID, SavedState> active = new HashMap<>();
    private final MiniMessage mm = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getCommand("staffmode") != null) {
            getCommand("staffmode").setExecutor(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // Put everyone back to normal so nobody stays stuck in spectator/vanish.
        for (UUID id : Map.copyOf(active).keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                disable(p, false);
            }
        }
        active.clear();
    }

    /** A player counts as staff if they have staffmode.use or are in one of the allowed LuckPerms groups. */
    private boolean isStaff(Player player) {
        if (player.hasPermission("staffmode.use")) {
            return true;
        }
        for (String group : getConfig().getStringList("allowed-groups")) {
            if (player.hasPermission("group." + group.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private void send(Player player, String key) {
        String msg = getConfig().getString("messages." + key, "");
        if (!msg.isEmpty()) {
            player.sendMessage(mm.deserialize(msg));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(mm.deserialize(getConfig().getString("messages.players-only", "")));
            return true;
        }
        if (!isStaff(player)) {
            send(player, "no-permission");
            return true;
        }
        if (active.containsKey(player.getUniqueId())) {
            disable(player, true);
        } else {
            enable(player);
        }
        return true;
    }

    private void enable(Player player) {
        active.put(player.getUniqueId(), new SavedState(player.getGameMode(), player.getLocation().clone()));
        player.setGameMode(GameMode.SPECTATOR);

        // Hide from everyone who isn't staff. Staff can still see each other.
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player) && !isStaff(other)) {
                other.hidePlayer(this, player);
            }
        }
        send(player, "enabled");
    }

    private void disable(Player player, boolean notify) {
        SavedState state = active.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            other.showPlayer(this, player);
        }
        if (getConfig().getBoolean("restore-location", true) && state.location() != null) {
            player.teleport(state.location());
        }
        player.setGameMode(state.gameMode());
        if (notify) {
            send(player, "disabled");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joiner = event.getPlayer();
        if (isStaff(joiner)) {
            return;
        }
        // Players who join while someone is vanished must not see them either.
        for (UUID id : active.keySet()) {
            Player vanished = Bukkit.getPlayer(id);
            if (vanished != null) {
                joiner.hidePlayer(this, vanished);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Don't leave someone stuck in spectator after they log off.
        if (active.containsKey(event.getPlayer().getUniqueId())) {
            disable(event.getPlayer(), false);
        }
    }
}
