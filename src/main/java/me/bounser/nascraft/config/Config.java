package me.bounser.nascraft.config;

import de.tr7zw.changeme.nbtapi.NBT;
import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.database.DatabaseManager;
import me.bounser.nascraft.discord.linking.LinkingMethod;
import me.bounser.nascraft.market.GoodSettings;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.Port;
import me.bounser.nascraft.market.unit.Item;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;

public class Config {

    private FileConfiguration config;
    private FileConfiguration items;
    private FileConfiguration ports;
    private FileConfiguration inventorygui;

    private static Config instance;
    private Nascraft main;

    public static Config getInstance() {
        return instance == null ? instance = new Config() : instance;
    }

    private Config() {
        main = Nascraft.getInstance();
        main.saveDefaultConfig();
        this.config = Nascraft.getInstance().getConfig();

        items = setupFile("items.yml");
        ports = setupFile("ports.yml");
        inventorygui = setupFile("inventorygui.yml");
    }

    public YamlConfiguration setupFile(String name) {

        File file = new File(main.getDataFolder(), name);

        if (!file.exists()) main.saveResource(name, false);

        return YamlConfiguration.loadConfiguration(file);
    }

    public void reload() {

        config = YamlConfiguration.loadConfiguration(new File(main.getDataFolder(), "config.yml"));

        items = setupFile("items.yml");
        ports = setupFile("ports.yml");
        inventorygui = setupFile("inventorygui.yml");

        Lang.get().reload();

        MarketManager.getInstance().reload();
    }

    // Config:

    public List<String> getIgnoredKeys() {
        return config.getStringList("ignored-keys");
    }

    public int getDatabasePurgeDays() {
        return config.getInt("database.days-until-history-removed", 60);
    }

    public String getSelectedLanguage() {
        return config.getString("language", "en_US");
    }

    // Currency (Vault only):

    public String getCurrencyFormat() {
        return config.getString("currency.format", "[AMOUNT]$");
    }

    public String getCurrencyNotEnough() {
        return config.getString("currency.not-enough", "<red>You don't have enough money to buy that!</red>");
    }

    public int getCurrencyDecimals() {
        return config.getInt("currency.decimal-positions", 2);
    }

    public double getCurrencyTopLimit() {
        return config.getDouble("currency.top-limit", 9999999);
    }

    public double getCurrencyLowLimit() {
        return config.getDouble("currency.low-limit", 0.005);
    }

    // Price options:

    public boolean getPriceNoise() {
        return config.getBoolean("price-options.noise.enabled", true);
    }

    public int getNoiseTime() {
        return config.getInt("price-options.noise.time", 60);
    }

    public float getNoiseMultiplier() {
        return (float) config.getDouble("price-options.noise.intensity-multiplier", 1);
    }

    public float getElasticityMultiplier() {
        return (float) config.getDouble("price-options.elasticity-multiplier", 1);
    }

    public boolean isMarketClosed() {
        return config.getBoolean("market-control.closed", false);
    }

    public boolean takeIntoAccountTax() {
        return config.getBoolean("market-control.taxation.take-into-account-taxes", false);
    }

    public boolean getMarketPermissionRequirement() {
        return config.getBoolean("market-control.market-permission", true);
    }

    // Commands:

    public String getCommandAlias(String command) {
        return config.getString("commands." + command + ".alias", command);
    }

    public boolean isCommandEnabled(String command) {
        return config.getBoolean("commands." + command + ".enabled", false);
    }

    public int getGetSellMenuSize() {
        return config.getInt("commands.sell-menu.size", 45);
    }

    public boolean getHelpEnabled() {
        return config.getBoolean("commands.sell-menu.help.enabled", false);
    }

    public int getHelpSlot() {
        return config.getInt("commands.sell-menu.help.slot", 4);
    }

    public String getHelpTexture() {
        return config.getString("commands.sell-menu.help.texture", "");
    }

    public Material getFillerMaterial() {
        return materialOrDefault(config.getString("commands.sell-menu.filler.material"), Material.BLACK_STAINED_GLASS_PANE);
    }

    public int getSellButtonSlot() {
        return config.getInt("commands.sell-menu.sell-button.slot", 40);
    }

    public Material getSellButtonMaterial() {
        return materialOrDefault(config.getString("commands.sell-menu.sell-button.material"), Material.YELLOW_STAINED_GLASS_PANE);
    }

    public boolean getCloseButtonEnabled() {
        return config.getBoolean("commands.sell-menu.close-button.enabled", true);
    }

    public int getCloseButtonSlot() {
        return config.getInt("commands.sell-menu.close-button.slot", 8);
    }

    public Material getCloseButtonMaterial() {
        return materialOrDefault(config.getString("commands.sell-menu.close-button.material"), Material.RED_STAINED_GLASS_PANE);
    }

    // Discord:

    public boolean getDiscordEnabled() {
        return config.getBoolean("discord-bot.enabled", false);
    }

    public LinkingMethod getLinkingMethod() {
        try {
            return LinkingMethod.valueOf(config.getString("discord-bot.link-method", "NATIVE").toUpperCase());
        } catch (IllegalArgumentException e) {
            return LinkingMethod.NATIVE;
        }
    }

    public String getToken() {
        return config.getString("discord-bot.token", "");
    }

    public boolean getLogChannelEnabled() {
        return config.getBoolean("discord-bot.log-trades.enabled", false);
    }

    public String getLogChannel() {
        return config.getString("discord-bot.log-trades.channel", "");
    }

    public String getAdminRoleID() {
        return config.getString("discord-bot.admin-role-id", "");
    }

    // Ports:

    public Set<String> getPortIds() {
        ConfigurationSection section = ports.getConfigurationSection("ports");
        if (section == null) return Collections.emptySet();
        return section.getKeys(false);
    }

    public Port buildPort(String portId) {

        String base = "ports." + portId;

        if (!ports.contains(base + ".location.world") ||
                !ports.contains(base + ".location.x") ||
                !ports.contains(base + ".location.z")) {
            Nascraft.getInstance().getLogger().warning("Port " + portId + " is missing its location (world/x/z).");
            return null;
        }

        String defaultName = (Character.toUpperCase(portId.charAt(0)) + portId.substring(1)).replace("_", " ");

        return new Port(
                portId,
                ports.getString(base + ".display-name", defaultName),
                ports.getString(base + ".location.world"),
                ports.getDouble(base + ".location.x"),
                ports.getDouble(base + ".location.z"),
                ports.getDouble(base + ".location.radius", ports.getDouble("defaults.radius", 40)),
                ports.getInt(base + ".restock.min-minutes", ports.getInt("defaults.restock.min-minutes", 45)),
                ports.getInt(base + ".restock.max-minutes", ports.getInt("defaults.restock.max-minutes", 90))
        );
    }

    public Set<String> getPortGoods(String portId) {
        ConfigurationSection section = ports.getConfigurationSection("ports." + portId + ".goods");
        if (section == null) return Collections.emptySet();
        return section.getKeys(false);
    }

    /** Print every buy/sell to the server console. */
    public boolean getTradeLogConsole() {
        return config.getBoolean("trade-log.console", true);
    }

    /** Append every buy/sell to plugins/Nascraft/trades.log. */
    public boolean getTradeLogFile() {
        return config.getBoolean("trade-log.file", false);
    }

    public boolean getRestockAnnounceEnabled() {
        return config.getBoolean("ports.announce-restock.enabled", true);
    }

    /** true = announce to the whole server, false = only to players inside the port. */
    public boolean getRestockAnnounceGlobal() {
        return config.getBoolean("ports.announce-restock.global", false);
    }

    /**
     * Resolves the economic settings of a good at a port.
     * Order: ports.yml good override -> items.yml -> config.yml defaults.
     */
    public GoodSettings getGoodSettings(String portId, String identifier) {

        String override = "ports." + portId + ".goods." + identifier;
        String item = "items." + identifier;

        float initialPrice = (float) resolveDouble(override + ".initial-price", item + ".initial-price", 1);
        float elasticity = (float) resolveDouble(override + ".elasticity", item + ".elasticity",
                config.getDouble("price-options.default-elasticity", 1));
        float support = (float) resolveDouble(override + ".support", item + ".support", 0);
        float resistance = (float) resolveDouble(override + ".resistance", item + ".resistance", 0);
        float noiseIntensity = (float) resolveDouble(override + ".noise-intensity", item + ".noise-intensity",
                config.getDouble("price-options.noise.default-intensity", 1));

        float taxBuy = (float) resolveDouble(override + ".tax.buy", item + ".tax.buy",
                config.getDouble("market-control.taxation.buy", 0.04));
        float taxSell = (float) resolveDouble(override + ".tax.sell", item + ".tax.sell",
                config.getDouble("market-control.taxation.sell", 0.06));

        double lowLimit = resolveDouble(override + ".limit.low", item + ".limit.low", -1);
        double highLimit = resolveDouble(override + ".limit.high", item + ".limit.high", -1);

        boolean restricted;
        if (ports.contains(override + ".limit.restricted")) restricted = ports.getBoolean(override + ".limit.restricted");
        else if (items.contains(item + ".limit.restricted")) restricted = items.getBoolean(item + ".limit.restricted");
        else restricted = true;

        int startingStock = (int) resolveDouble(override + ".starting-stock", item + ".stock.starting",
                config.getInt("market-control.default-starting-stock", 100));

        int restockAmount = (int) resolveDouble(override + ".restock-amount", item + ".stock.restock-amount",
                ports.getDouble("ports." + portId + ".restock.amount",
                        ports.getDouble("defaults.restock.amount", 64)));

        return new GoodSettings(initialPrice, elasticity, support, resistance, noiseIntensity,
                taxBuy, taxSell, lowLimit, highLimit, restricted, startingStock, restockAmount);
    }

    private double resolveDouble(String portPath, String itemPath, double fallback) {
        if (ports.contains(portPath)) return ports.getDouble(portPath);
        if (items.contains(itemPath)) return items.getDouble(itemPath);
        return fallback;
    }

    // Items (global catalog):

    public Set<String> getAllMaterials() {
        ConfigurationSection section = items.getConfigurationSection("items");
        if (section == null) return Collections.emptySet();
        return section.getKeys(false);
    }

    public List<Item> getChilds(Item parent) {

        List<Item> childs = new ArrayList<>();

        ConfigurationSection section = items.getConfigurationSection("items." + parent.getIdentifier() + ".child");

        if (section == null) return childs;

        for (String childIdentifier : section.getKeys(false)) {

            ItemStack itemStack = getItemStackOfChild(parent.getIdentifier(), childIdentifier);

            if (itemStack == null) continue;

            float multiplier = (float) items.getDouble("items." + parent.getIdentifier() + ".child." + childIdentifier + ".multiplier", 1);

            String alias;

            if (items.contains("items." + parent.getIdentifier() + ".child." + childIdentifier + ".alias")) {
                alias = items.getString("items." + parent.getIdentifier() + ".child." + childIdentifier + ".alias");
            } else {
                alias = (Character.toUpperCase(childIdentifier.charAt(0)) + childIdentifier.substring(1)).replace("_", " ");
            }

            childs.add(new Item(parent, multiplier, itemStack, childIdentifier, alias));
        }

        return childs;
    }

    public ItemStack getItemStackOfItem(String identifier) {

        ItemStack itemStack = null;

        if (items.contains("items." + identifier + ".item-stack"))
            itemStack = items.getSerializable("items." + identifier + ".item-stack", ItemStack.class);

        if (itemStack == null) {
            String matName = items.contains("items." + identifier + ".material")
                    ? items.getString("items." + identifier + ".material").toUpperCase()
                    : identifier.replaceAll("\\d", "").toUpperCase();
            Material material = Material.getMaterial(matName);

            if (material == null) {
                Nascraft.getInstance().getLogger().severe("Couldn't load item with identifier: " + identifier);
                Nascraft.getInstance().getLogger().severe("Reason: Material " + matName + " is not valid!");
                return null;
            }

            itemStack = new ItemStack(material);
        }

        applyCustomMeta(itemStack, "items." + identifier);

        for (String ignoredKey : getIgnoredKeys()) {
            ItemStack finalItemStack = itemStack;
            NBT.modify(finalItemStack, nbt -> {
                nbt.removeKey(ignoredKey);
            });
        }

        return itemStack;
    }

    /**
     * Applies optional custom-model-data + display-name from config to a configured item,
     * so resource-pack items (e.g. PAPER + custom_model_data trade goods) can be defined
     * with just {@code material} + {@code model-data} + {@code display-name} instead of a
     * fully serialized item-stack. Model data is written to BOTH the float and string CMD
     * lists so it matches range_dispatch and select resource packs alike.
     */
    private void applyCustomMeta(ItemStack itemStack, String base) {
        if (itemStack == null) return;
        if (!items.contains(base + ".model-data") && !items.contains(base + ".display-name")) return;
        org.bukkit.inventory.meta.ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) return;
        if (items.contains(base + ".model-data")) {
            int md = items.getInt(base + ".model-data");
            org.bukkit.inventory.meta.components.CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
            cmd.setFloats(java.util.Collections.singletonList((float) md));
            cmd.setStrings(java.util.Collections.singletonList(String.valueOf(md)));
            meta.setCustomModelDataComponent(cmd);
        }
        if (items.contains(base + ".display-name")) {
            meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&', items.getString(base + ".display-name")));
        }
        itemStack.setItemMeta(meta);
    }

    public ItemStack getItemStackOfChild(String identifier, String childIdentifier) {

        ItemStack itemStack = null;

        if (items.contains("items." + identifier + ".child." + childIdentifier + ".item-stack"))
            itemStack = items.getSerializable("items." + identifier + ".child." + childIdentifier + ".item-stack", ItemStack.class);

        if (itemStack == null) {
            String matName = items.contains("items." + identifier + ".child." + childIdentifier + ".material")
                    ? items.getString("items." + identifier + ".child." + childIdentifier + ".material").toUpperCase()
                    : childIdentifier.replaceAll("\\d", "").toUpperCase();
            Material material = Material.getMaterial(matName);

            if (material == null) {
                Nascraft.getInstance().getLogger().severe("Couldn't load child item with identifier: " + childIdentifier);
                return null;
            }

            itemStack = new ItemStack(material);
        }

        applyCustomMeta(itemStack, "items." + identifier + ".child." + childIdentifier);

        return itemStack;
    }

    public String getAlias(String identifier) {
        if (!items.contains("items." + identifier + ".alias")) {
            return (Character.toUpperCase(identifier.charAt(0)) + identifier.substring(1)).replace("_", " ");
        } else {
            return items.getString("items." + identifier + ".alias");
        }
    }

    // GUI: port menu

    public int getPortMenuSize() {
        return inventorygui.getInt("port-menu.size", 54);
    }

    public List<Integer> getPortMenuItemSlots() {
        return inventorygui.getIntegerList("port-menu.items.slots");
    }

    public boolean getPortMenuInfoEnabled() {
        return inventorygui.getInt("port-menu.info.slot", -1) != -1;
    }

    public int getPortMenuInfoSlot() {
        return inventorygui.getInt("port-menu.info.slot", 4);
    }

    public Material getPortMenuInfoMaterial() {
        return materialOrDefault(inventorygui.getString("port-menu.info.material"), Material.LECTERN);
    }

    /** Custom-model-data for the port-info icon (0 = none; use the plain material). */
    public int getPortMenuInfoModelData() {
        return inventorygui.getInt("port-menu.info.model-data", 0);
    }

    public int getPortMenuNextSlot() {
        return inventorygui.getInt("port-menu.next-button.slot", 53);
    }

    public int getPortMenuBackSlot() {
        return inventorygui.getInt("port-menu.back-button.slot", 45);
    }

    public Material getPortMenuNavMaterial() {
        return materialOrDefault(inventorygui.getString("port-menu.nav-material"), Material.ARROW);
    }

    public HashMap<Material, List<Integer>> getPortMenuFillers() {

        HashMap<Material, List<Integer>> fills = new HashMap<>();

        ConfigurationSection section = inventorygui.getConfigurationSection("port-menu.fillers");

        if (section == null) return fills;

        for (String key : section.getKeys(false)) {
            Material material = Material.getMaterial(key.toUpperCase());
            if (material == null) continue;
            fills.put(material, inventorygui.getIntegerList("port-menu.fillers." + key));
        }

        return fills;
    }

    // GUI: ports directory menu

    public int getDirectoryMenuSize() {
        return inventorygui.getInt("directory-menu.size", 54);
    }

    public List<Integer> getDirectoryMenuPortSlots() {
        List<Integer> slots = inventorygui.getIntegerList("directory-menu.ports.slots");
        if (!slots.isEmpty()) return slots;
        return Arrays.asList(10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34);
    }

    public Material getDirectoryMenuEntryMaterial() {
        return materialOrDefault(inventorygui.getString("directory-menu.ports.material"), Material.PAPER);
    }

    /** Custom-model-data for each directory port entry (0 = none; use the plain material). */
    public int getDirectoryMenuEntryModelData() {
        return inventorygui.getInt("directory-menu.ports.model-data", 0);
    }

    public int getDirectoryMenuNextSlot() {
        return inventorygui.getInt("directory-menu.next-button.slot", 53);
    }

    public int getDirectoryMenuBackSlot() {
        return inventorygui.getInt("directory-menu.back-button.slot", 45);
    }

    public Material getDirectoryMenuNavMaterial() {
        return materialOrDefault(inventorygui.getString("directory-menu.nav-material"), Material.ARROW);
    }

    public HashMap<Material, List<Integer>> getDirectoryMenuFillers() {

        HashMap<Material, List<Integer>> fills = new HashMap<>();

        ConfigurationSection section = inventorygui.getConfigurationSection("directory-menu.fillers");

        if (section == null) return fills;

        for (String key : section.getKeys(false)) {
            Material material = Material.getMaterial(key.toUpperCase());
            if (material == null) continue;
            fills.put(material, inventorygui.getIntegerList("directory-menu.fillers." + key));
        }

        return fills;
    }

    // GUI: buy-sell menu

    public int getBuySellMenuSize() {
        return inventorygui.getInt("buy-sell.size", 45);
    }

    public int getBuySellMenuItemSlot() { return inventorygui.getInt("buy-sell.item.slot", 4); }

    public boolean getBuySellBackEnabled() {
        return inventorygui.getInt("buy-sell.back-button.slot", -1) != -1;
    }

    public int getBuySellBackSlot() {
        return inventorygui.getInt("buy-sell.back-button.slot", 36);
    }

    public Material getBuySellBackMaterial() {
        return materialOrDefault(inventorygui.getString("buy-sell.back-button.material"), Material.ARROW);
    }

    public List<Integer> getBuySellFillersSlots() {
        return inventorygui.getIntegerList("buy-sell.fillers.slots");
    }

    public Material getBuySellFillersMaterial() {
        return materialOrDefault(inventorygui.getString("buy-sell.fillers.material"), Material.GRAY_STAINED_GLASS_PANE);
    }

    public Material getBuySellBuyMaterial() {
        return materialOrDefault(inventorygui.getString("buy-sell.buy-buttons.material"), Material.LIME_STAINED_GLASS_PANE);
    }

    public HashMap<Integer, Integer> getBuySellBuySlots() {

        HashMap<Integer, Integer> buttons = new HashMap<>();

        ConfigurationSection section = inventorygui.getConfigurationSection("buy-sell.buy-buttons.buttons");

        if (section == null) return buttons;

        for (String weight : section.getKeys(false)) {
            int slot = inventorygui.getInt("buy-sell.buy-buttons.buttons." + weight + ".slot");
            buttons.put(Integer.valueOf(weight), slot);
        }

        return buttons;
    }

    public Material getBuySellSellMaterial() {
        return materialOrDefault(inventorygui.getString("buy-sell.sell-buttons.material"), Material.RED_STAINED_GLASS_PANE);
    }

    public HashMap<Integer, Integer> getBuySellSellSlots() {

        HashMap<Integer, Integer> buttons = new HashMap<>();

        ConfigurationSection section = inventorygui.getConfigurationSection("buy-sell.sell-buttons.buttons");

        if (section == null) return buttons;

        for (String weight : section.getKeys(false)) {
            int slot = inventorygui.getInt("buy-sell.sell-buttons.buttons." + weight + ".slot");
            buttons.put(Integer.valueOf(weight), slot);
        }

        return buttons;
    }

    private Material materialOrDefault(String name, Material def) {
        if (name == null) return def;
        Material material = Material.getMaterial(name.toUpperCase());
        return material == null ? def : material;
    }
}
