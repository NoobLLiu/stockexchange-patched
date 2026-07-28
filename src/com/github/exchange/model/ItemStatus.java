/*
 * Decompiled with CFR 0.152.
 */
package com.github.exchange.model;

import java.math.BigDecimal;

public class ItemStatus {
    private int itemId;
    private boolean suspended;
    private BigDecimal lastClose;
    private BigDecimal lastOpen;
    private BigDecimal highToday;
    private BigDecimal lowToday;
    private int volumeToday;
    private BigDecimal lowestSellCurrent;
    private BigDecimal lowestSellReference;
    private long lowestSellReferenceAt;
    private BigDecimal lowestSellReference7d;
    private long lowestSellReferenceAt7d;
    private BigDecimal lowestSellReference30d;
    private long lowestSellReferenceAt30d;

    public ItemStatus() {
    }

    public ItemStatus(int itemId, boolean suspended, BigDecimal lastClose, BigDecimal lastOpen, BigDecimal highToday, BigDecimal lowToday, int volumeToday) {
        this.itemId = itemId;
        this.suspended = suspended;
        this.lastClose = lastClose;
        this.lastOpen = lastOpen;
        this.highToday = highToday;
        this.lowToday = lowToday;
        this.volumeToday = volumeToday;
        this.lowestSellCurrent = BigDecimal.ZERO;
        this.lowestSellReference = BigDecimal.ZERO;
        this.lowestSellReferenceAt = 0L;
        this.lowestSellReference7d = BigDecimal.ZERO;
        this.lowestSellReferenceAt7d = 0L;
        this.lowestSellReference30d = BigDecimal.ZERO;
        this.lowestSellReferenceAt30d = 0L;
    }

    public int getItemId() {
        return this.itemId;
    }

    public void setItemId(int itemId) {
        this.itemId = itemId;
    }

    public boolean isSuspended() {
        return this.suspended;
    }

    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
    }

    public BigDecimal getLastClose() {
        return this.lastClose;
    }

    public void setLastClose(BigDecimal lastClose) {
        this.lastClose = lastClose;
    }

    public BigDecimal getLastOpen() {
        return this.lastOpen;
    }

    public void setLastOpen(BigDecimal lastOpen) {
        this.lastOpen = lastOpen;
    }

    public BigDecimal getHighToday() {
        return this.highToday;
    }

    public void setHighToday(BigDecimal highToday) {
        this.highToday = highToday;
    }

    public BigDecimal getLowToday() {
        return this.lowToday;
    }

    public void setLowToday(BigDecimal lowToday) {
        this.lowToday = lowToday;
    }

    public int getVolumeToday() {
        return this.volumeToday;
    }

    public void setVolumeToday(int volumeToday) {
        this.volumeToday = volumeToday;
    }

    public BigDecimal getLowestSellCurrent() {
        return this.lowestSellCurrent;
    }

    public void setLowestSellCurrent(BigDecimal lowestSellCurrent) {
        this.lowestSellCurrent = lowestSellCurrent;
    }

    public BigDecimal getLowestSellReference() {
        return this.lowestSellReference;
    }

    public void setLowestSellReference(BigDecimal lowestSellReference) {
        this.lowestSellReference = lowestSellReference;
    }

    public long getLowestSellReferenceAt() {
        return this.lowestSellReferenceAt;
    }

    public void setLowestSellReferenceAt(long lowestSellReferenceAt) {
        this.lowestSellReferenceAt = lowestSellReferenceAt;
    }

    public BigDecimal getLowestSellReference7d() {
        return this.lowestSellReference7d;
    }

    public void setLowestSellReference7d(BigDecimal lowestSellReference7d) {
        this.lowestSellReference7d = lowestSellReference7d;
    }

    public long getLowestSellReferenceAt7d() {
        return this.lowestSellReferenceAt7d;
    }

    public void setLowestSellReferenceAt7d(long lowestSellReferenceAt7d) {
        this.lowestSellReferenceAt7d = lowestSellReferenceAt7d;
    }

    public BigDecimal getLowestSellReference30d() {
        return this.lowestSellReference30d;
    }

    public void setLowestSellReference30d(BigDecimal lowestSellReference30d) {
        this.lowestSellReference30d = lowestSellReference30d;
    }

    public long getLowestSellReferenceAt30d() {
        return this.lowestSellReferenceAt30d;
    }

    public void setLowestSellReferenceAt30d(long lowestSellReferenceAt30d) {
        this.lowestSellReferenceAt30d = lowestSellReferenceAt30d;
    }
}
