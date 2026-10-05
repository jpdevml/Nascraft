package me.bounser.nascraft.market;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** The shipped global Bazaar must have a complete, non-arbitrageable catalog. */
public class DefaultGlobalMarketTest {
    private YamlConfiguration resource(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream(name), StandardCharsets.UTF_8));
    }

    @Test public void globalCatalogMatchesGuiAndOnlyResourcesUsePlayerOrders() {
        YamlConfiguration ports = resource("ports.yml");
        YamlConfiguration items = resource("items.yml");
        assertTrue(ports.getConfigurationSection("ports").getKeys(false).isEmpty());
        Set<String> resources = Set.of("iron_ingot", "gold_ingot", "gunpowder", "coal");
        Set<String> listed = new HashSet<>();
        for (String category : List.of("resources", "blocks", "crops")) {
            for (String good : items.getStringList("bazaar-categories." + category + ".goods")) {
                assertTrue("Good appears in more than one GUI category: " + good, listed.add(good));
                assertTrue("Missing item definition: " + good, items.contains("items." + good + ".initial-price"));
                ConfigurationSection market = ports.getConfigurationSection("global-market.goods." + good);
                assertNotNull("Missing market listing: " + good, market);
                if (resources.contains(good)) {
                    assertEquals("player-only", market.getString("liquidity"));
                    assertFalse(market.contains("starting-stock"));
                    assertFalse(market.contains("restock-amount"));
                } else {
                    assertEquals("managed", market.getString("liquidity"));
                    assertTrue(market.getInt("starting-stock") > 0);
                    assertTrue(market.getInt("restock-amount") > 0);
                    assertEquals(0.0, market.getDouble("elasticity"), 0.0001);
                    assertEquals(0.0, market.getDouble("noise-intensity"), 0.0001);
                    double buy = items.getDouble("items." + good + ".initial-price") * (1 + market.getDouble("tax.buy"));
                    double sell = items.getDouble("items." + good + ".initial-price") * (1 - market.getDouble("tax.sell"));
                    assertTrue("Buying then selling must lose money: " + good, buy > sell);
                }
            }
        }
        assertEquals(resources, new HashSet<>(items.getStringList("bazaar-categories.resources.goods")));
        assertEquals(ports.getConfigurationSection("global-market.goods").getKeys(false), listed);
        assertTrue("Log/plank conversion must not make a profit",
                items.getDouble("items.oak_log.initial-price") * 1.2 >
                        4 * items.getDouble("items.oak_planks.initial-price") * 0.8);
    }
}
