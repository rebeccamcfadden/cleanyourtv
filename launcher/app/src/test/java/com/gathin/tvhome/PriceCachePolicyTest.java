package com.gathin.tvhome;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** When prices and deep links are fetched again, and the monthly request budget. */
public class PriceCachePolicyTest {

    private static final long H = PriceCachePolicy.HOUR;
    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void pricesAreKeptADay() {
        assertTrue(PriceCachePolicy.fresh(NOW - 23 * H, NOW, PriceCachePolicy.PRICE_TTL));
        assertFalse(PriceCachePolicy.fresh(NOW - 24 * H, NOW, PriceCachePolicy.PRICE_TTL));
        assertFalse(PriceCachePolicy.fresh(0, NOW, PriceCachePolicy.PRICE_TTL));
        // A clock that went backwards doesn't keep a stale answer forever.
        assertFalse(PriceCachePolicy.fresh(NOW + H, NOW, PriceCachePolicy.PRICE_TTL));
    }

    @Test
    public void deepLinksAreKeptAMonth() {
        assertTrue(PriceCachePolicy.fresh(NOW - 29 * 24 * H, NOW, PriceCachePolicy.DEEP_LINK_TTL));
        assertFalse(PriceCachePolicy.fresh(NOW - 30 * 24 * H, NOW, PriceCachePolicy.DEEP_LINK_TTL));
    }

    @Test
    public void budgetsStayUnderTheFreePlans() {
        assertEquals(2000, PriceCachePolicy.monthlyBudget(WatchmodePrices.ID));
        assertTrue(PriceCachePolicy.monthlyBudget(StreamingAvailabilityPrices.ID) < 1000);
        assertEquals(0, PriceCachePolicy.monthlyBudget(ItunesPrices.ID));
        assertEquals(3000, PriceCachePolicy.minIntervalMs(ItunesPrices.ID));
        assertEquals(0, PriceCachePolicy.minIntervalMs(WatchmodePrices.ID));
    }

    @Test
    public void theBudgetIsSpreadOverTheMonth() {
        LocalDate oct1 = LocalDate.of(2026, 10, 1);
        PriceCachePolicy.Ledger l = new PriceCachePolicy.Ledger(oct1, 0, 0);
        // 2,000 over 31 days: 64 a day.
        assertEquals(64, PriceCachePolicy.dailyAllowance(l, 2000, oct1));
        assertTrue(PriceCachePolicy.allowed(l, 2, 2000, PriceCachePolicy.Trigger.PREVIEW, oct1));
        l.record(62, oct1);
        assertTrue(PriceCachePolicy.allowed(l, 2, 2000, PriceCachePolicy.Trigger.PREVIEW, oct1));
        assertFalse(PriceCachePolicy.allowed(l, 4, 2000, PriceCachePolicy.Trigger.PREVIEW, oct1));
        // A list only gets half the day's share.
        assertFalse(PriceCachePolicy.allowed(l, 2, 2000, PriceCachePolicy.Trigger.LIST, oct1));
        PriceCachePolicy.Ledger fresh = new PriceCachePolicy.Ledger(oct1, 0, 0);
        fresh.record(30, oct1);
        assertTrue(PriceCachePolicy.allowed(fresh, 2, 2000, PriceCachePolicy.Trigger.LIST, oct1));
        assertFalse(PriceCachePolicy.allowed(fresh, 4, 2000, PriceCachePolicy.Trigger.LIST, oct1));
    }

    @Test
    public void quietDaysCarryOverAndANewMonthStartsAgain() {
        LocalDate oct30 = LocalDate.of(2026, 10, 30);
        PriceCachePolicy.Ledger l = new PriceCachePolicy.Ledger(LocalDate.of(2026, 10, 2), 100, 10);
        // 1,900 left for two days.
        assertEquals(950, PriceCachePolicy.dailyAllowance(l, 2000, oct30));
        assertEquals(0, l.dayUsed);
        l.record(2000 - 100 - 2, oct30);
        assertFalse(PriceCachePolicy.allowed(l, 4, 2000, PriceCachePolicy.Trigger.PREVIEW, oct30));
        LocalDate nov1 = LocalDate.of(2026, 11, 1);
        assertTrue(PriceCachePolicy.allowed(l, 4, 2000, PriceCachePolicy.Trigger.PREVIEW, nov1));
        assertEquals(0, l.monthUsed);
        assertEquals(nov1, l.day);
    }

    @Test
    public void unmeteredSourcesAreAlwaysAllowed() {
        PriceCachePolicy.Ledger l = new PriceCachePolicy.Ledger(LocalDate.of(2026, 10, 30), 99999, 99999);
        assertTrue(PriceCachePolicy.allowed(l, 1, 0, PriceCachePolicy.Trigger.LIST, LocalDate.of(2026, 10, 30)));
    }

    @Test
    public void theLedgerSurvivesAsText() {
        LocalDate today = LocalDate.of(2026, 10, 30);
        PriceCachePolicy.Ledger l = new PriceCachePolicy.Ledger(today, 123, 7);
        assertEquals("2026-10-30|123|7", l.encode());
        PriceCachePolicy.Ledger back = PriceCachePolicy.Ledger.decode(l.encode(), today);
        assertEquals(123, back.monthUsed);
        assertEquals(7, back.dayUsed);
        PriceCachePolicy.Ledger broken = PriceCachePolicy.Ledger.decode("garbage", today);
        assertEquals(0, broken.monthUsed);
        assertEquals(today, broken.day);
        assertEquals(0, PriceCachePolicy.Ledger.decode(null, today).monthUsed);
    }

    @Test
    public void itunesOnlyWhenNoApplePriceYet() {
        assertTrue(PriceCachePolicy.needsItunes(Collections.emptyList()));
        assertFalse(PriceCachePolicy.needsItunes(Collections.singletonList(
                new StorePrice("appletv", OfferType.RENT, Quality.HD, new Money(3900, "SEK"), 0, "watchmode"))));
        assertTrue(PriceCachePolicy.needsItunes(Collections.singletonList(
                new StorePrice("sfanytime", OfferType.RENT, Quality.HD, new Money(3900, "SEK"), 0, "watchmode"))));
    }
}
