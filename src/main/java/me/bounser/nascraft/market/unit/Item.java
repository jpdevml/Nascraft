package me.bounser.nascraft.market.unit;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.api.events.Action;
import me.bounser.nascraft.api.events.TransactionCompletedEvent;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.database.DatabaseManager;
import me.bounser.nascraft.api.events.BuyItemEvent;
import me.bounser.nascraft.api.events.SellItemEvent;
import me.bounser.nascraft.database.commands.resources.Trade;
import me.bounser.nascraft.discord.DiscordLog;
import me.bounser.nascraft.formatter.Formatter;
import me.bounser.nascraft.formatter.RoundUtils;
import me.bounser.nascraft.managers.InventoryManager;
import me.bounser.nascraft.managers.currencies.CurrenciesManager;
import me.bounser.nascraft.managers.currencies.Currency;
import me.bounser.nascraft.market.GoodSettings;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.managers.MoneyManager;
import me.bounser.nascraft.market.Port;
import me.bounser.nascraft.market.OrderBook;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.formatter.Style;
import me.bounser.nascraft.market.unit.stats.ItemStats;
import net.kyori.adventure.platform.bukkit.BukkitComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDateTime;
import java.util.*;

public class Item {

    private ItemStack itemStack;
    private final String identifier;
    private String alias;
    private String taggedAlias;
    private String formattedAlias;

    private final Port port;

    private final Price price;
    private final Currency currency;

    private int operations;

    private int volume;

    private float collectedTaxes;

    private int stock;
    private final int restockAmount;
    private final boolean playerOnly;

    private ItemStats itemStats;

    private final float multiplier;

    private final Item parent;

    private final List<Item> childs = new ArrayList<>();

    private final boolean restricted;

    public Item(ItemStack itemStack, String identifier, String alias, Port port, GoodSettings settings) {

        itemStack.setAmount(1);

        this.itemStack = itemStack;
        this.identifier = identifier;
        this.port = port;

        setupAlias(alias);

        this.currency = CurrenciesManager.getInstance().getDefaultCurrency();

        this.price = new Price(this, settings);

        this.restricted = settings.isRestricted();

        price.initializeHourValues(DatabaseManager.get().getDatabase().retrieveLastPrice(this));

        operations = 0;
        multiplier = 1;
        parent = null;

        this.stock = settings.getStartingStock();
        this.restockAmount = settings.getRestockAmount();
        this.playerOnly = settings.isPlayerOnly();

        itemStats = new ItemStats(this);
    }

    public Item(Item parent, float multiplier, ItemStack itemStack, String identifier, String alias) {

        this.currency = parent.getCurrency();

        itemStack.setAmount(1);

        this.parent = parent;
        this.port = parent.getPort();
        this.itemStack = itemStack;
        this.multiplier = multiplier;
        this.identifier = identifier;
        this.price = parent.getPrice();
        this.restricted = parent.isPriceRestricted();
        this.restockAmount = 0;
        this.playerOnly = parent.isPlayerOnly();

        setupAlias(alias);
    }

    public void setupAlias(String alias) {

        taggedAlias = alias;

        Component miniMessageAlias = MiniMessage.miniMessage().deserialize(alias);

        this.formattedAlias = BukkitComponentSerializer.legacy().serialize(miniMessageAlias);

        this.alias = extractPlainText(miniMessageAlias);

        if (alias.equals(formattedAlias)) {
            taggedAlias = Lang.get().message(Message.DEFAULT_ITEM_FORMAT).replace("[ALIAS]", alias);
            Component defaultMiniMessageAlias = MiniMessage.miniMessage().deserialize(taggedAlias);
            formattedAlias = BukkitComponentSerializer.legacy().serialize(defaultMiniMessageAlias);
        }
    }

    public String extractPlainText(Component component) {
        StringBuilder plainText = new StringBuilder();

        if (component instanceof TextComponent) {
            plainText.append(((TextComponent) component).content());
        }

        for (Component child : component.children()) {
            plainText.append(extractPlainText(child));
        }

        return plainText.toString();
    }

    public void addChildItem(Item item) {
        childs.add(item);
    }

    public List<Item> getChilds() {
        return childs;
    }

    public Port getPort() { return port; }

    public String getName() { return alias; }

    public String getTaggedName() { return taggedAlias; }

    public String getFormattedName() { return formattedAlias; }

    public double buyPrice(int amount) {
        if (playerOnly) return OrderBook.get().quote(this, amount, true);
        return price.getProjectedCost(-amount*multiplier, price.getBuyTaxMultiplier());
    }

    public double sellPrice(int amount) {
        if (playerOnly) return OrderBook.get().quote(this, amount, false);
        return price.getProjectedCost(amount*multiplier, price.getSellTaxMultiplier());
    }

    public double buy(int amount, UUID uuid, boolean feedback) {
        if (amount <= 0) return 0;
        if (playerOnly) {
            Player buyer = Bukkit.getPlayer(uuid);
            if (buyer == null || !buyer.isOnline()) return 0;
            BuyItemEvent event = new BuyItemEvent(buyer, this, amount);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return 0;
            double result = OrderBook.get().fill(buyer, this, amount, true, false);
            if (result > 0) completedPlayerTrade(buyer, amount, result, true);
            if (feedback && result > 0) buyer.sendMessage("Purchased " + amount + " " + getName() + " for " + result + ". Use /market claim to collect.");
            else if (feedback && result == -2) buyer.sendMessage("Settlement uncertain. Contact an administrator; do not retry until reconciled.");
            else if (feedback) buyer.sendMessage("No matching sell orders or payment failed.");
            return Math.max(0, result);
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !feedback) return 0;
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);

        boolean limitReached = !price.canStockChange(amount, true);

        if (limitReached && restricted) {
            if (player != null && feedback) Lang.get().message(player, Message.TOP_LIMIT_REACHED);
            return 0;
        }

        // The port can only sell what it has in stock.
        Item stockItem = parent != null ? parent : this;
        int stockNeeded = (int) Math.ceil(amount * multiplier);
        if (stockItem.stock < stockNeeded) {
            if (player != null && feedback) Lang.get().message(player, Message.MARKET_STOCK_FULL, "[STOCK]", String.valueOf(stockItem.stock), "[NAME]", stockItem.taggedAlias);
            return 0;
        }

        BuyItemEvent event = new BuyItemEvent(player, this, amount);
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) return 0;

        if (!MarketManager.getInstance().getActive()) {
            if (player != null && feedback) Lang.get().message(player, Message.SHOP_CLOSED);
            return 0;
        }

        double worth = price.getProjectedCost(-amount*multiplier, price.getBuyTaxMultiplier());

        if (!checkBalance(offlinePlayer, player, feedback, worth)) return 0;
        if (!InventoryManager.checkInventory(player, feedback, itemStack, amount)) return 0;

        // Charge first; only hand the items over once the money is confirmed taken.
        if (!MoneyManager.getInstance().withdraw(offlinePlayer, currency, worth)) {
            if (player != null && feedback) Lang.get().message(player, currency.getNotEnoughMessage());
            return 0;
        }

        if (player != null && feedback) {
            InventoryManager.addItemsToInventory(player, itemStack, amount);
        }

        if (player != null && feedback) Lang.get().message(player, Message.BUY_MESSAGE, Formatter.format(currency, worth, Style.ROUND_BASIC), String.valueOf(amount), taggedAlias);

        if (!limitReached) {
            stockItem.updateInternalValues(amount,
                    amount*price.getValue(),
                    -amount*multiplier,
                    price.getValue()*(price.getBuyTaxMultiplier()-1)*amount*multiplier);
        }

        stockItem.addStock(-stockNeeded);

        completedPlayerTrade(player, amount, worth, true);

        return worth;
    }

    private void completedPlayerTrade(Player player, int amount, double worth, boolean buy) {
        // The fill is already committed: logging and third-party listeners must not
        // cause the caller to return escrow to a seller on an exception.
        try {
            Trade trade = new Trade(this, LocalDateTime.now(), worth, amount, buy, false, player.getUniqueId());
            DatabaseManager.get().getDatabase().saveTrade(trade);
            me.bounser.nascraft.managers.TradeLogger.getInstance().log(trade);
            if (Config.getInstance().getDiscordEnabled() && Config.getInstance().getLogChannelEnabled())
                DiscordLog.getInstance().sendTradeLog(trade);
            MarketManager.getInstance().addOperation();
            Bukkit.getPluginManager().callEvent(new TransactionCompletedEvent(player, this, amount,
                    buy ? Action.BUY : Action.SELL, worth));
        } catch (Exception ex) {
            Nascraft.getInstance().getLogger().warning("Trade notification failed after committed bazaar fill: " + ex);
        }
    }

    public boolean checkBalance(OfflinePlayer offlinePlayer, Player player, boolean feedback, double money) {
        if (!MoneyManager.getInstance().hasEnoughMoney(offlinePlayer, money)) {
            if (player != null && feedback) Lang.get().message(player, currency.getNotEnoughMessage());
            return false;
        }
        return true;
    }

    public double sell(int amount, UUID uuid, boolean feedback) {
        return sellInternal(amount, uuid, feedback, false);
    }

    /** Only the deposit GUI should call this, after taking ownership of a tracked ORIGINAL stack. */
    public double sellEscrowed(ItemStack original, UUID uuid) {
        if (original == null || !original.isSimilar(itemStack) || original.getAmount() <= 0) return -1;
        return sellInternal(original.getAmount(), uuid, false, true);
    }

    private double sellInternal(int amount, UUID uuid, boolean feedback, boolean escrowed) {
        if (amount <= 0) return -1;
        if (playerOnly) {
            Player seller = Bukkit.getPlayer(uuid);
            if (seller == null || !seller.isOnline()) return -1;
            SellItemEvent event = new SellItemEvent(seller, this, amount);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return -1;
            double result = OrderBook.get().fill(seller, this, amount, false, escrowed);
            if (result > 0) completedPlayerTrade(seller, amount, result, false);
            if (feedback && result > 0) seller.sendMessage("Sold " + amount + " " + getName() + " for " + result + ". Use /market claim to collect.");
            else if (feedback && result == -2) seller.sendMessage("Settlement uncertain. Contact an administrator; do not retry until reconciled.");
            else if (feedback) seller.sendMessage("No matching buy orders or escrow failed.");
            return result;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return -1;
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);

        boolean limitReached = !price.canStockChange(amount, false);

        if (limitReached && restricted) {
            if (player != null && feedback) Lang.get().message(player, Message.BOTTOM_LIMIT_REACHED);
            return -1;
        }

        SellItemEvent event = new SellItemEvent(player, this, amount);
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) return -1;

        if (!MarketManager.getInstance().getActive()) {
            if (player != null && feedback) Lang.get().message(player, Message.SHOP_CLOSED);
            return -1;
        }

        ItemStack operationItemStack = itemStack.clone();

        operationItemStack.setAmount(1);

        if (!escrowed && !player.getInventory().containsAtLeast(operationItemStack, amount)) {
            Lang.get().message(player, Message.NOT_ENOUGH_ITEMS);
            return -1;
        }

        double worth = price.getProjectedCost(amount*multiplier, price.getSellTaxMultiplier());

        if (!escrowed) {
            operationItemStack.setAmount(amount);
            Map<Integer, ItemStack> remaining = player.getInventory().removeItem(operationItemStack);
            if (!remaining.isEmpty()) {
                int notRemoved = remaining.values().stream().mapToInt(ItemStack::getAmount).sum();
                if (notRemoved < amount) InventoryManager.addItemsToInventory(player, operationItemStack, amount - notRemoved);
                return -1;
            }
        }

        if (!MoneyManager.getInstance().deposit(offlinePlayer, currency, worth)) {
            // Vault's failure response cannot prove that nothing was paid. Returning
            // the inventory here could duplicate items AND money; quarantine instead.
            Nascraft.getInstance().getLogger().severe("Managed sale payout uncertain for " + uuid + ": "
                    + amount + "x " + identifier + " at " + port.getId() + ". Reconcile before refunding.");
            if (feedback) player.sendMessage("Payout uncertain; contact an administrator before retrying.");
            return -2;
        }

        Item stockItem = parent != null ? parent : this;

        if (!limitReached) {
            stockItem.updateInternalValues(amount,
                    amount*price.getValue(),
                    amount*multiplier,
                    price.getValue()*(1-price.getSellTaxMultiplier())*amount*multiplier);
        }

        stockItem.addStock((int) (amount * multiplier));

        worth = RoundUtils.round(worth);

        if (player != null && feedback) Lang.get().message(player, Message.SELL_MESSAGE, Formatter.format(currency, worth, Style.ROUND_BASIC), String.valueOf(amount), taggedAlias);

        completedPlayerTrade(player, amount, worth, false);

        return worth;
    }

    public List<Double> getValuesPastHour() {
        return price.getValuesPastHour();
    }

    private void updateInternalValues(int operations, double volume, float stockChange, double taxes) {
        this.operations += operations;
        this.volume += volume;
        this.price.changeStock(stockChange);
        this.collectedTaxes += taxes;
    }

    public String getIdentifier() { return identifier; }

    public List<Material> getParentAndChildsMaterials() {
        List<Material> materials = new ArrayList<>();

        materials.add(itemStack.getType());

        for (Item item : childs)
            materials.add(item.getItemStack().getType());

        return materials;
    }

    public boolean isParent() { return parent == null; }

    public Item getParent() { return parent; }

    public float getMultiplier() { return multiplier; }

    public Price getPrice() { return price; }

    public Currency getCurrency() { return currency; }

    public int getOperations() { return operations; }

    public void lowerOperations() {
        if (operations > 10) {
            operations -= Math.round((float) operations/60f);
            operations -= 3;
        } else if (operations > 1){
            operations -= 1;
        }
    }

    public int getVolume() { return volume; }

    public float getCollectedTaxes() { return collectedTaxes; }

    public void setCollectedTaxes(float newCollectedTaxes) { collectedTaxes = newCollectedTaxes; }

    public void addVolume(int volume) { this.volume += volume; }

    public void restartVolume() { volume = 0; }

    public ItemStats getItemStats() { return itemStats; }

    public ItemStack getItemStack() { return itemStack.clone(); }

    public ItemStack getItemStack(int quantity) {
        ItemStack clonedItemStack = itemStack.clone();
        clonedItemStack.setAmount(quantity);
        return clonedItemStack;
    }

    public boolean isPriceRestricted() { return restricted; }

    public boolean isPlayerOnly() { return playerOnly; }

    public int getStock() { return parent != null ? parent.getStock() : playerOnly ? (int) Math.min(Integer.MAX_VALUE, OrderBook.get().available(this, true)) : stock; }

    public int getRestockAmount() { return restockAmount; }

    public void setStock(int stock) {
        if (parent != null) { parent.setStock(stock); return; }
        if (playerOnly) return;
        this.stock = Math.max(0, stock);
    }

    public void addStock(int amount) {
        if (parent != null) { parent.addStock(amount); return; }
        if (playerOnly) return;
        this.stock = Math.max(0, stock + amount);
    }

    public boolean hasStock() { return getStock() > 0; }

    public double getChangeLastDay() {

        List<me.bounser.nascraft.market.unit.stats.Instant> dayPrices = DatabaseManager.get().getDatabase().getDayPrices(this);

        if (dayPrices == null || dayPrices.isEmpty()) return 0;

        double firstValue = dayPrices.get(0).getPrice();

        if (firstValue == 0) return 0;

        return ((price.getValue() - firstValue) / firstValue);
    }
}
