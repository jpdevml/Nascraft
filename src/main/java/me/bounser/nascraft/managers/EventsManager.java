package me.bounser.nascraft.managers;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import me.bounser.nascraft.database.DatabaseExecutor;
import me.bounser.nascraft.database.commands.UserNames;
import me.bounser.nascraft.inventorygui.MarketMenuManager;

public class EventsManager implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        String txKey = "join-" + uuid;
        DatabaseExecutor.getInstance().executeIdempotent(txKey, conn -> {
            try {
                UserNames.saveOrUpdateNick(conn, uuid, name);
            } catch (java.sql.SQLException e) {
                me.bounser.nascraft.Nascraft.getInstance().getLogger().warning("Failed to save username for " + name + ": " + e.getMessage());
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        MarketMenuManager.getInstance().clearSession(event.getPlayer());
        // Positions are cached per player forever otherwise; on a shared database another shard may also change
        // them while the player is away, so reload on next use.
        me.bounser.nascraft.market.Portfolio.get().forget(event.getPlayer().getUniqueId());
    }
}
