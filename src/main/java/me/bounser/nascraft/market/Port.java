package me.bounser.nascraft.market;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.formatter.Formatter;
import me.bounser.nascraft.market.unit.Item;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A local market tied to a physical location. Each port owns its
 * own Item instances: prices and stock evolve independently per port,
 * so goods can be cheap where they are produced and expensive where
 * they are scarce.
 */
public class Port {

    private final String id;
    private final String displayName;       // MiniMessage
    private final String plainDisplayName;

    private final String worldName;
    private final double x;
    private final double z;
    private final double radius;

    private final int restockMinMinutes;
    private final int restockMaxMinutes;

    private final List<Item> items = new ArrayList<>();
    private final Map<String, Item> identifiers = new HashMap<>();

    public Port(String id, String displayName, String worldName, double x, double z, double radius,
                int restockMinMinutes, int restockMaxMinutes) {
        this.id = id;
        this.displayName = displayName;
        this.plainDisplayName = Formatter.extractPlainText(MiniMessage.miniMessage().deserialize(displayName));
        this.worldName = worldName;
        this.x = x;
        this.z = z;
        this.radius = radius;
        this.restockMinMinutes = restockMinMinutes;
        this.restockMaxMinutes = restockMaxMinutes;
    }

    public void setupGoods() {

        Config config = Config.getInstance();

        for (String identifier : config.getPortGoods(id)) {

            if (!config.getAllMaterials().contains(identifier)) {
                Nascraft.getInstance().getLogger().warning("Port " + id + " references good '" + identifier + "' which is not defined in items.yml. Skipping.");
                continue;
            }

            ItemStack itemStack = config.getItemStackOfItem(identifier);

            if (itemStack == null) {
                Nascraft.getInstance().getLogger().warning("Error with the itemStack of: " + identifier);
                continue;
            }

            Item item = new Item(
                    itemStack,
                    identifier,
                    config.getAlias(identifier),
                    this,
                    config.getGoodSettings(id, identifier)
            );

            items.add(item);
            identifiers.put(identifier, item);

            for (Item child : config.getChilds(item)) {
                item.addChildItem(child);
                items.add(child);
                identifiers.put(child.getIdentifier(), child);
            }
        }
    }

    public String getId() { return id; }

    public String getDisplayName() { return displayName; }

    public String getPlainDisplayName() { return plainDisplayName; }

    public String getWorldName() { return worldName; }

    public double getCenterX() { return x; }

    public double getCenterZ() { return z; }

    public double getRadius() { return radius; }

    public Location getCenter() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, x, world.getHighestBlockYAt((int) x, (int) z), z);
    }

    /** Horizontal distance check, Y is ignored: a port covers the full column. */
    public boolean isInside(Location location) {
        if (location.getWorld() == null || !location.getWorld().getName().equals(worldName)) return false;
        double dx = location.getX() - x;
        double dz = location.getZ() - z;
        return (dx * dx + dz * dz) <= radius * radius;
    }

    public int getRestockMinMinutes() { return restockMinMinutes; }

    public int getRestockMaxMinutes() { return restockMaxMinutes; }

    /** Adds each parent good's configured restock amount to its stock. */
    public void restock() {
        for (Item item : getParentItems())
            item.addStock(item.getRestockAmount());
    }

    public Item getItem(String identifier) { return identifiers.get(identifier); }

    public Item getItem(ItemStack itemStack) {
        for (Item item : items) if (itemStack.isSimilar(item.getItemStack())) return item;
        return null;
    }

    public boolean trades(ItemStack itemStack) { return getItem(itemStack) != null; }

    public List<Item> getAllItems() { return items; }

    public List<Item> getParentItems() {
        List<Item> parents = new ArrayList<>();
        for (Item item : items) if (item.isParent()) parents.add(item);
        return parents;
    }

    public List<Item> getParentItemsInAlphabeticalOrder() {
        List<Item> sorted = new ArrayList<>(getParentItems());
        sorted.sort(Comparator.comparing(Item::getName));
        return sorted;
    }
}
