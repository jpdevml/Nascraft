package me.bounser.nascraft;

import me.bounser.nascraft.api.NascraftAPI;
import me.bounser.nascraft.commands.admin.nascraft.NascraftCommand;
import me.bounser.nascraft.commands.admin.nascraft.NascraftLogListener;
import me.bounser.nascraft.commands.discord.DiscordCommand;
import me.bounser.nascraft.commands.market.MarketCommand;
import me.bounser.nascraft.commands.sell.SellHandCommand;
import me.bounser.nascraft.commands.sell.SellAllCommand;
import me.bounser.nascraft.commands.sell.sellinv.SellInvListener;
import me.bounser.nascraft.commands.sell.sellinv.SellInvCommand;
import me.bounser.nascraft.database.DatabaseExecutor;
import me.bounser.nascraft.database.DatabaseManager;
import me.bounser.nascraft.discord.DiscordBot;
import me.bounser.nascraft.commands.discord.LinkCommand;
import me.bounser.nascraft.discord.linking.LinkingMethod;
import me.bounser.nascraft.inventorygui.InventoryListener;
import me.bounser.nascraft.inventorygui.MarketMenuManager;
import me.bounser.nascraft.managers.EventsManager;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.config.Config;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import net.milkbowl.vault.economy.Economy;

public final class Nascraft extends JavaPlugin {

    private static Nascraft main;
    private static NascraftAPI apiInstance;
    private static Economy economy = null;
    private static Permission perms = null;

    private BukkitAudiences adventure;

    public static Nascraft getInstance() { return main; }

    public static NascraftAPI getAPI() { return apiInstance == null ? apiInstance = new NascraftAPI() : apiInstance; }

    @Override
    public void onEnable() {

        main = this;

        Config config = Config.getInstance();

        this.adventure = BukkitAudiences.create(this);

        if (!setupEconomy()) {
            getLogger().severe("Vault (or an economy plugin) is not installed! Nascraft needs Vault to handle money.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        setupPermissions();

        try {
            DatabaseManager.get();
        } catch (Exception ex) {
            getLogger().severe("Database unavailable; refusing to open markets: " + ex);
            DatabaseExecutor.shutdownIfPresent();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            MarketManager.getInstance();
        } catch (Exception ex) {
            getLogger().severe("Could not load markets; refusing to trade: " + ex);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // MarketManager disables the plugin when ports.yml has no valid ports.
        if (!isEnabled()) return;

        if (config.getDiscordEnabled()) {
            getLogger().info("Enabling discord extension...");

            try {
                new DiscordBot();

                if (Config.getInstance().getLinkingMethod().equals(LinkingMethod.NATIVE)
                && config.isCommandEnabled("link")) new LinkCommand();

                if (config.isCommandEnabled("discord")) new DiscordCommand();

                getLogger().info("Discord extension loaded!");
            } catch (Exception e) {
                getLogger().severe("Failed to start the Discord bot (bad token?): " + e.getMessage());
                getLogger().severe("Continuing without Discord.");
            }
        }

        if (config.isCommandEnabled("nascraft")) {
            new NascraftCommand();
            Bukkit.getPluginManager().registerEvents(new NascraftLogListener(), this);
        }

        if (config.isCommandEnabled("market")) {
            new MarketCommand();
            Bukkit.getPluginManager().registerEvents(new InventoryListener(), this);
        }

        if (config.isCommandEnabled("sellhand")) new SellHandCommand();

        if (config.isCommandEnabled("sell-menu")) {
            new SellInvCommand();
            Bukkit.getPluginManager().registerEvents(new SellInvListener(), this);
        }

        if (config.isCommandEnabled("sellall")) new SellAllCommand();

        Bukkit.getPluginManager().registerEvents(new EventsManager(), this);
    }

    @Override
    public void onDisable() {

        // Return items held in open sell menus and close plugin GUIs before saving.
        if (MarketMenuManager.getInstanceIfPresent() != null)
            MarketMenuManager.getInstance().closeAllMenus();

        // Guarded accessors: if enabling failed early (e.g. no Vault), don't
        // lazily bootstrap the database or market during shutdown.
        DatabaseManager databaseManager = DatabaseManager.getIfPresent();

        if (databaseManager != null) {

            if (databaseManager.getDatabase().isConnected())
                databaseManager.getDatabase().saveEverything();

            getLogger().info("Shutting down async database executor...");
            DatabaseExecutor.getInstance().shutdown();
            getLogger().info("Done!");

            getLogger().info("Saving and closing connection with database...");
            databaseManager.getDatabase().disconnect();
            getLogger().info("Done!");
        }

        if (DiscordBot.getInstance() != null && DiscordBot.getInstance().getJDA() != null) {
            DiscordBot.getInstance().getJDA().shutdown();
        }

        if (this.adventure != null) {
            this.adventure.close();
            this.adventure = null;
        }
    }

    public static Economy getEconomy() { return economy; }

    public static Permission getPermissions() { return perms; }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) { return false; }

        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) { return false; }

        economy = rsp.getProvider();
        return economy != null;
    }

    private boolean setupPermissions() {
        RegisteredServiceProvider<Permission> rsp = getServer().getServicesManager().getRegistration(Permission.class);
        if (rsp == null) return false;
        perms = rsp.getProvider();
        return perms != null;
    }

    public BukkitAudiences adventure() {
        if (this.adventure == null) {
            throw new IllegalStateException("Tried to access Adventure when the plugin was disabled!");
        }
        return this.adventure;
    }
}
