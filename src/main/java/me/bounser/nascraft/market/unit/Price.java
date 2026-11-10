package me.bounser.nascraft.market.unit;

import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.formatter.RoundUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Price {

    private Item item;

    private double value;

    private int precission;

    private double topLimit;
    private double lowLimit;

    private double upperStockThreshold;
    private double lowerStockThreshold;

    private double previousValue;

    private double initialValue;

    private float stock;

    private double support;
    private double resistance;
    private float noiseIntensity;

    private float elasticity;

    private double historicalHigh;
    private double historicalLow;

    private double hourHigh;
    private double hourLow;
    private final List<Double> dayHigh = new ArrayList<>();
    private final List<Double> dayLow = new ArrayList<>();

    private List<Double> hourValues;

    private final float taxBuy;
    private final float taxSell;

    private double chartDayHigh;
    private double monthHigh;
    private double yearHigh;
    private double allHigh;

    private double chartDayLow;
    private double monthLow;
    private double yearLow;
    private double allLow;

    // Market model. The quoted price is the supply curve times e^factor; factor wanders like a stock:
    // pulled back towards the current news shock, pushed by a trend (bull/bear/sideways) and by trades.
    private double factor;
    private double trend;
    private int trendTicks;
    private double shock;
    private final double volatility;
    private final double reversion;
    private final double depth;
    private static final java.util.Random RANDOM = new java.util.Random();

    private float dayChange;
    private float monthChange;
    private float yearChange;
    private float allChange;

    public Price(Item item, me.bounser.nascraft.market.GoodSettings settings) {

        this.item = item;

        precission = item.getCurrency().getDecimalPrecission();

        topLimit = settings.getHighLimit();
        if (topLimit < 0) topLimit = item.getCurrency().getTopLimit();

        lowLimit = settings.getLowLimit();
        if (lowLimit < 0) lowLimit = (float) Math.max(item.getCurrency().getLowLimit(), (float) 1.0/(Math.pow(10f, precission)));

        this.initialValue = settings.getInitialPrice();

        this.support = settings.getSupport();
        this.resistance = settings.getResistance();
        this.noiseIntensity = settings.getNoiseIntensity() * Config.getInstance().getNoiseMultiplier();
        this.elasticity = settings.getElasticity() * Config.getInstance().getElasticityMultiplier();

        taxBuy = settings.getBuyTaxMultiplier();
        taxSell = settings.getSellTaxMultiplier();

        volatility = settings.getVolatility();
        reversion = settings.getReversion();
        depth = settings.getDepth();

        upperStockThreshold = getStockFromValue(topLimit);
        lowerStockThreshold = getStockFromValue(lowLimit);

        updateValue();
        previousValue = value;

        hourHigh = value;
        hourLow = value;
        dayHigh.add(hourHigh);
        dayLow.add(hourLow);
    }

    public double getValue() { return value; }

    public double getBuyPrice() { return getProjectedCost(-1, taxBuy); }

    public double getSellPrice() { return getProjectedCost(1, taxSell); }

    public void setStock(float stock) {
        this.stock = stock;
        updateValue();
    }

    public void setDayHigh(double dayHigh) {
        this.chartDayHigh = dayHigh;
    }

    public double getChartDayHigh() {
        return chartDayHigh;
    }

    public void setMonthHigh(double monthHigh) {
        this.monthHigh = monthHigh;
    }

    public double getMonthHigh() {
        return monthHigh;
    }

    public void setYearHigh(double yearHigh) {
        this.yearHigh = yearHigh;
    }

    public double getYearHigh() {
        return yearHigh;
    }

    public void setAllHigh(double allHigh) {
        this.allHigh = allHigh;
    }

    public double getAllHigh() {
        return allHigh;
    }

    public void setDayLow(double dayLow) {
        this.chartDayLow = dayLow;
    }

    public double getChartDayLow() {
        return chartDayLow;
    }

    public void setMonthLow(double monthLow) {
        this.monthLow = monthLow;
    }

    public double getMonthLow() {
        return monthLow;
    }

    public void setYearLow(double yearLow) {
        this.yearLow = yearLow;
    }

    public double getYearLow() {
        return yearLow;
    }

    public void setAllLow(double allLow) {
        this.allLow = allLow;
    }

    public double getAllLow() {
        return allLow;
    }

    public void setDayChange(float dayChange) {
        this.dayChange = dayChange;
    }

    public float getDayChange() { return dayChange; }

    public void setMonthChange(float monthChange) {
        this.monthChange = monthChange;
    }

    public float getMonthChange() { return monthChange; }

    public void setYearChange(float yearChange) {
        this.yearChange = yearChange;
    }

    public float getYearChange() { return yearChange; }

    public void setAllChange(float allChange) {
        this.allChange = allChange;
    }

    public float getAllChange() { return allChange; }

    public float getStock() { return stock; }

    public float getElasticity() { return elasticity; }

    public double getUpperStockLimit() { return upperStockThreshold; }

    public double getLowerStockThreshold() { return lowerStockThreshold; }

    public boolean canStockChange(float change, boolean buy) {

        if (elasticity == 0) return true;

        float newStock = stock + change;

        if (!buy) return !(newStock > lowerStockThreshold);
        else return !(newStock < upperStockThreshold);
    }

    public double stockChangeUntilPriceReached(double priceToReach) {

        double stockToReach = getStockFromValue(priceToReach);

        if (priceToReach > value) {
            return Math.floor(Math.abs(stock - stockToReach));
        } else {
            return -Math.floor(Math.abs(stock - stockToReach));
        }
    }

    public void changeStock(float change) {

        if (Config.getInstance().takeIntoAccountTax()) {

            if (change > 0) {
                stock += change * (1 + (1-taxSell));
            } else {
                stock += change * taxBuy;
            }

        } else {
            stock += change;
        }

        updateValue();
    }

    public void enforceLimits() {
        value = Math.min(value, topLimit);
        value = Math.max(value, lowLimit);
    }

    public void applyNoise() {

        if (elasticity == 0 || noiseIntensity == 0) return;

        float prevStock = stock;

        if (support != 0 && value < support && Math.random() > 0.8) {

            stock -= (float) ((8 - 12 * Math.random()) * noiseIntensity);

        } else if (resistance != 0 && value > resistance && Math.random() > 0.8) {

            stock += (float) ((8 - 12 * Math.random()) * noiseIntensity);

        } else {

            stock += (float) ((10 - 20 * Math.random()) * noiseIntensity);

        }

        updateValue();

        item.addVolume(Math.abs(Math.round(stock - prevStock)));
    }

    public double getChange() {
        double change = -100 + 100*value/previousValue;
        previousValue = value;

        return change;
    }

    public double getHistoricalHigh() { return historicalHigh; }

    public double getHistoricalLow() { return historicalLow; }

    public void setHistoricalHigh(float newHistoricalHigh) { historicalHigh = newHistoricalHigh; }

    public void setHistoricalLow(float newHistoricalLow) { historicalLow = newHistoricalLow; }

    public double getDayHigh() {

        double high = dayHigh.get(0);

        for (double value : dayHigh) if (value > high) high = value;

        return Math.max(hourHigh, high);
    }

    public double getDayLow() {

        double low = dayLow.get(0);

        for (double value : dayLow) if (low > value) low = value;

        return Math.min(hourLow, low);
    }

    public void updateValue() {

        value = (float) (initialValue * Math.exp(-0.0005 * elasticity * stock) * Math.exp(factor));
        enforceLimits();
        updateLimits();

    }

    private void updateLimits() {
        if (value > historicalHigh) { historicalHigh = value; }
        if (value < historicalLow) { historicalLow = value; }

        if (value > hourHigh) { hourHigh = value; }
        if (value < hourLow) { hourLow = value; }
    }

    public void restartHourLimits() {

        if (dayHigh.size() == 24) {
            dayHigh.remove(0);
            dayLow.remove(0);

            dayHigh.add(hourHigh);
            dayLow.add(hourLow);
        }
        hourLow = value;
        hourHigh = value;
    }

    public void initializeHourValues(double value) {
        if (hourValues == null)
            hourValues = new ArrayList<>(Collections.nCopies(60, value));
    }

    public void addValueToShortTermStorage() {
        hourValues.remove(0);
        hourValues.add(value);
    }

    public float getValueChangeLastHour() {
        return RoundUtils.roundToOne((float) (-100 + 100*value/hourValues.get(0)));
    }

    public double getValueAnHourAgo() { return hourValues.get(0); }

    public List<Double> getValuesPastHour() { return hourValues; }

    public double getProjectedCost(float stockChange, float tax) {

        if (elasticity == 0)
            return roundToDecimals(Math.abs((value * stockChange * tax)), precission);

        float change;

        if (Config.getInstance().takeIntoAccountTax()) {

            if (stockChange > 0) {
                change = stockChange * (1 + (1-taxSell));
            } else {
                change = stockChange * taxBuy;
            }

        } else {
            change = stockChange;
        }

        double cost = integratePiecewise(stock, stock + change) * Math.exp(factor);

        return roundToDecimals(cost*tax, precission);
    }

    public double integratePiecewise(double start, double end) {

        double initialStock = start;
        double finalStock = end;

        if (start >= end) {
            initialStock = end;
            finalStock = start;
        }

        double totalIntegral = 0.0;

        double y1 = initialValue * Math.exp(-0.0005* elasticity * upperStockThreshold);
        double y2 = initialValue * Math.exp(-0.0005* elasticity * lowerStockThreshold);

        double segment1_end = Math.min(finalStock, upperStockThreshold);
        if (segment1_end > initialStock) {
            double width = segment1_end - initialStock;
            totalIntegral += y1 * width;
        }

        double segment2_start = Math.max(initialStock, upperStockThreshold);
        double segment2_end = Math.min(finalStock, lowerStockThreshold);
        if (segment2_end > segment2_start) {
            totalIntegral += integrateAnalytically(segment2_start, segment2_end);
        }

        double segment3_start = Math.max(initialStock, lowerStockThreshold);
        if (finalStock > segment3_start) {
            double width = finalStock - segment3_start;
            totalIntegral += y2 * width;
        }

        return totalIntegral;
    }

    public double integrateAnalytically(double x1, double x2) {

        final double k = 0.0005 * elasticity;

        double factor = initialValue / k;
        double expTerm1 = Math.exp(-k * x1);
        double expTerm2 = Math.exp(-k * x2);
        return factor * (expTerm1 - expTerm2);
    }

    public float getBuyTaxMultiplier() { return taxBuy; }
    public float getSellTaxMultiplier() { return taxSell; }

    public Item getItem() { return item; }

    public double getInitialValue() { return initialValue; }
    public float getNoiseIntensity() { return noiseIntensity; }
    public double getSupport() { return support; }
    public double getResistance() { return resistance; }

    public Price setInitialValue(double initialValue) { this.initialValue = initialValue; return this; }
    public Price setElasticity(float elasticity) {
        this.elasticity = elasticity * Config.getInstance().getElasticityMultiplier(); return this; }
    public Price setNoiseIntensity(float noiseIntensity) { this.noiseIntensity = noiseIntensity * Config.getInstance().getNoiseMultiplier(); return this; }
    public Price setSupport(double support) { this.support = support; return this; }
    public Price setResistance(double resistance) { this.resistance = resistance; return this; }

    public static float roundToDecimals(double value, int decimals) {
        BigDecimal bd = new BigDecimal(Double.toString(value));
        bd = bd.setScale(decimals, RoundingMode.HALF_UP); // Rounds up if the digit is >= 5
        return (float) bd.doubleValue();
    }

    // --- Market model ---

    public record News(String headline, double move) { }

    /**
     * One market tick (price-options.noise.time, a minute by default). Returns a headline when a news event hit.
     * The factor is an Ornstein-Uhlenbeck walk in log price: it reverts to the shock level at {@code reversion}
     * per tick, with gaussian moves of {@code volatility} and a trend that runs for 30-180 ticks.
     */
    public News marketTick() {
        if (volatility <= 0) return null;
        Config config = Config.getInstance();
        News news = null;

        if (--trendTicks <= 0) {
            trendTicks = 30 + RANDOM.nextInt(151);
            double roll = RANDOM.nextDouble();
            double size = volatility * (0.15 + 0.45 * RANDOM.nextDouble());
            double chance = config.getMarketTrendChance();
            trend = roll < chance / 2 ? size : roll < chance ? -size : 0;
        }

        if (RANDOM.nextDouble() < config.getMarketEventChance()) {
            double size = config.getMarketEventMin() + (config.getMarketEventMax() - config.getMarketEventMin()) * RANDOM.nextDouble();
            boolean up = RANDOM.nextBoolean();
            shock = Math.max(-1.5, Math.min(1.5, shock + (up ? size : -size)));
            factor += (up ? size : -size) * 0.5; // half lands at once, the rest drifts in
            news = new News(headline(up), up ? size : -size);
        }

        shock *= 0.985; // a story fades over a few hours
        factor += reversion * (shock - factor) + trend + volatility * RANDOM.nextGaussian();
        clampFactor();
        updateValue();
        item.addVolume((int) Math.round(Math.abs(volatility * RANDOM.nextGaussian()) * depth * 100));
        return news;
    }

    private static final String[] UP = {
            "shortage reported", "buyers flood the market", "caravan lost on the road", "guild hoards supply",
            "a warlord is buying up all of it", "a mine collapse cuts supply", "demand spikes in the capital"};
    private static final String[] DOWN = {
            "a glut hits the docks", "merchants dump their stock", "a new vein is found", "a bumper harvest",
            "a rival market undercuts prices", "warehouses overflow", "a smuggling ring floods the market"};

    private String headline(boolean up) {
        String[] pool = up ? UP : DOWN;
        return item.getName() + ": " + pool[RANDOM.nextInt(pool.length)];
    }

    private void clampFactor() {
        double max = Config.getInstance().getMarketMaxSwing();
        factor = Math.max(-max, Math.min(max, factor));
    }

    /** Price impact per unit (log), or 0 when impact is off. */
    public double impactPerUnit() { return depth > 0 ? 0.01 / depth : 0; }

    /** A trade of {@code units} (positive = bought from the market) pushes the price. */
    public void impact(double units) {
        double k = impactPerUnit();
        if (k == 0 || units == 0) return;
        factor += k * units;
        clampFactor();
        updateValue();
    }

    /** Mid price of {@code units} shares walked along the impact curve (before fees). */
    public double shareCost(long units, boolean buy) {
        double k = impactPerUnit();
        if (units <= 0) return 0;
        if (k == 0) return value * units;
        return buy ? value * (Math.exp(k * units) - 1) / k : value * (1 - Math.exp(-k * units)) / k;
    }

    public double getFactor() { return factor; }
    public double getTrend() { return trend; }
    public int getTrendTicks() { return trendTicks; }
    public double getShock() { return shock; }
    public double getVolatility() { return volatility; }

    public void setMarketState(double factor, double trend, int trendTicks, double shock) {
        this.factor = factor;
        this.trend = trend;
        this.trendTicks = trendTicks;
        this.shock = shock;
        updateValue();
    }

    /**
     * A stored row's model state and stock together, with ONE price recompute: setting them one after the other
     * would record an intermediate price (new factor, old stock) in the hour/historical high and low.
     */
    public void setMarketStateAndStock(double factor, double trend, int trendTicks, double shock, float stock) {
        this.factor = factor;
        this.trend = trend;
        this.trendTicks = trendTicks;
        this.shock = shock;
        this.stock = stock;
        updateValue();
    }

    /** Bull, bear or steady: what the trend is doing, for the GUI. */
    public String getRegime() {
        if (volatility <= 0) return "Fixed";
        if (shock > 0.08) return "Shortage";
        if (shock < -0.08) return "Glut";
        if (trend > volatility * 0.1) return "Bull run";
        if (trend < -volatility * 0.1) return "Slump";
        return "Steady";
    }

    public double getStockFromValue(double value) {
        return (Math.log(value / initialValue) / (-0.0005 * elasticity));
    }

}
