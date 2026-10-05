package me.bounser.nascraft.market;

/**
 * Resolved economic parameters for one trade good at one port.
 * Resolution order: ports.yml good override -> items.yml -> config.yml defaults.
 */
public class GoodSettings {

    private final float initialPrice;
    private final float elasticity;
    private final float support;
    private final float resistance;
    private final float noiseIntensity;

    private final float taxBuy;   // stored as multiplier base (0.04 = 4%)
    private final float taxSell;

    private final double lowLimit;
    private final double highLimit;
    private final boolean restricted;

    private final int startingStock;
    private final int restockAmount;
    private final boolean playerOnly;

    public GoodSettings(float initialPrice,
                        float elasticity,
                        float support,
                        float resistance,
                        float noiseIntensity,
                        float taxBuy,
                        float taxSell,
                        double lowLimit,
                        double highLimit,
                        boolean restricted,
                        int startingStock,
                        int restockAmount,
                        boolean playerOnly) {
        this.initialPrice = initialPrice;
        this.elasticity = elasticity;
        this.support = support;
        this.resistance = resistance;
        this.noiseIntensity = noiseIntensity;
        this.taxBuy = taxBuy;
        this.taxSell = taxSell;
        this.lowLimit = lowLimit;
        this.highLimit = highLimit;
        this.restricted = restricted;
        this.startingStock = startingStock;
        this.restockAmount = restockAmount;
        this.playerOnly = playerOnly;
    }

    public float getInitialPrice() { return initialPrice; }
    public float getElasticity() { return elasticity; }
    public float getSupport() { return support; }
    public float getResistance() { return resistance; }
    public float getNoiseIntensity() { return noiseIntensity; }

    /** 1 + tax, ready to multiply on buys. */
    public float getBuyTaxMultiplier() { return 1 + taxBuy; }

    /** 1 - tax, ready to multiply on sells. */
    public float getSellTaxMultiplier() { return 1 - taxSell; }

    public double getLowLimit() { return lowLimit; }
    public double getHighLimit() { return highLimit; }
    public boolean isRestricted() { return restricted; }

    public int getStartingStock() { return startingStock; }
    public int getRestockAmount() { return restockAmount; }
    public boolean isPlayerOnly() { return playerOnly; }
}
